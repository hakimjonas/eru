package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A single-assignment asynchronous variable that can complete with either success or failure.
  *
  * A `Promise[E, A]` starts empty and can be completed exactly once with either a success value of
  * type `A` or a failure value of type `E`. Once completed, all waiters are notified and subsequent
  * await calls return immediately with the completed result.
  *
  * Unlike `Deferred[A]` which only handles successful completion, `Promise[E, A]` provides full
  * typed error handling capabilities, making it suitable for coordination patterns where failures
  * need to be propagated between fibers.
  *
  * This implementation is built entirely on Eru primitives (Ref), demonstrating pure functional
  * concurrency without any Java concurrent utilities.
  */
trait Promise[E, A] {

  /** Completes this `Promise` with a success value if it has not been completed yet.
    *
    * @param value
    *   the success value to complete the promise with
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def succeed(value: A): Eru[Nothing, Boolean]

  /** Completes this `Promise` with a failure value if it has not been completed yet.
    *
    * @param error
    *   the failure value to complete the promise with
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def fail(error: E): Eru[Nothing, Boolean]

  /** Completes this `Promise` with the result of another effect if it has not been completed yet.
    *
    * @param effect
    *   the effect whose result will be used to complete the promise
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def complete(effect: Eru[E, A]): Eru[Nothing, Boolean] =
    effect.attempt.flatMap {
      case Result.Success(value) => succeed(value)
      case Result.Failure(error) => fail(error)
    }

  /** Awaits completion, returning the result when available.
    *
    * This operation suspends until the promise is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   an effect that yields the completed result (success or failure)
    */
  def await: Eru[E, A]

  /** Checks whether this promise has been completed.
    *
    * @return
    *   an effect that yields `true` if the promise is completed, `false` otherwise
    */
  def isDone: Eru[Nothing, Boolean]

  /** Attempts to retrieve the current result without suspending.
    *
    * @return
    *   an effect that yields `Some(result)` if completed, or `None` if still pending
    */
  def poll: Eru[Nothing, Option[Exit[E, A]]]
}

object Promise {

  /** Creates a new, empty `Promise[E, A]`.
    *
    * @tparam E
    *   the error type
    * @tparam A
    *   the success value type
    * @return
    *   an effect that yields the created promise
    */
  def make[E, A](using runtime: EruRuntime): Eru[Nothing, Promise[E, A]] =
    for {
      stateRef <- Ref.make(PromiseState.empty[E, A])
    } yield new RuntimePromise[E, A](stateRef, runtime)

  /** Internal state representation for Promise. */
  private sealed trait PromiseState[E, A] {
    def isCompleted: Boolean
    def result: Option[Exit[E, A]]
    def waiters: List[Either[E, A] => Unit]
  }

  private object PromiseState {

    /** An empty promise with no result and potentially waiting callbacks. */
    case class Pending[E, A](waiters: List[Either[E, A] => Unit]) extends PromiseState[E, A] {
      def isCompleted: Boolean = false
      def result: Option[Exit[E, A]] = None
    }

    /** A completed promise with a result and no waiters. */
    case class Completed[E, A](exit: Exit[E, A]) extends PromiseState[E, A] {
      def isCompleted: Boolean = true
      def result: Option[Exit[E, A]] = Some(exit)
      def waiters: List[Either[E, A] => Unit] = Nil
    }

    def empty[E, A]: PromiseState[E, A] = Pending(Nil)
  }

  private final class RuntimePromise[E, A](stateRef: Ref[PromiseState[E, A]], runtime: EruRuntime)
      extends Promise[E, A] {
    import PromiseState.*

    /** Helper to convert Exit to Either for callbacks. */
    private def exitToEither(exit: Exit[E, A]): Either[E, A] = exit match {
      case Exit.Success(value) => Right(value)
      case Exit.Failure(error) => Left(error)
      case Exit.Die(_) | Exit.Interrupt(_, _) =>
        throw new IllegalStateException("Promise completed with unexpected exit type")
    }

    /** Attempts to complete the promise and notifies waiters if successful. */
    private def attemptComplete(exit: Exit[E, A]): Eru[Nothing, Boolean] = {
      stateRef.modify {
        case Pending(waiters) =>
          // Transition to completed and return waiters to notify
          (Completed(exit), (true, waiters))
        case completed: Completed[E, A] =>
          // Already completed, no change
          (completed, (false, Nil))
      }.flatMap { case (wasCompleted, waitersToNotify) =>
        if (wasCompleted && waitersToNotify.nonEmpty) {
          // Notify all waiters
          val result = exitToEither(exit)
          Eru.effect {
            waitersToNotify.foreach(_(result))
          }.attempt.map(_ => true)
        } else {
          Eru.succeed(wasCompleted)
        }
      }
    }

    def succeed(value: A): Eru[Nothing, Boolean] =
      attemptComplete(Exit.Success(value))

    def fail(error: E): Eru[Nothing, Boolean] =
      attemptComplete(Exit.Failure(error))

    def isDone: Eru[Nothing, Boolean] =
      stateRef.get.map(_.isCompleted)

    def poll: Eru[Nothing, Option[Exit[E, A]]] =
      stateRef.get.map(_.result)

    def await: Eru[E, A] = {
      // First check if already completed
      stateRef.get.flatMap {
        case Completed(exit) =>
          // Already completed - return immediately without suspension
          exit match {
            case Exit.Success(value) => Eru.succeed(value)
            case Exit.Failure(error) => Eru.fail(error)
            case Exit.Die(_) | Exit.Interrupt(_, _) =>
              throw new IllegalStateException("Promise completed with unexpected exit type")
          }
        case Pending(_) =>
          // Not completed - need to suspend
          runtime
            .suspend[Nothing, Either[E, A]] { callback =>
              // Create a wrapper callback that matches the expected type
              val wrappedCallback: Either[E, A] => Unit = (result: Either[E, A]) => callback(Right(result))

              // Register callback in a pure way
              val registerCallback = stateRef.modify {
                case Pending(waiters) =>
                  // Add callback to waiters
                  (Pending(wrappedCallback :: waiters), None)
                case completed @ Completed(exit) =>
                  // Race condition: completed while registering
                  // Return the result to invoke callback immediately
                  (completed, Some(exitToEither(exit)))
              }

              registerCallback.flatMap {
                case Some(result) =>
                  // Promise was completed during registration
                  Eru.effect(wrappedCallback(result)).attempt.map(_ => ())
                case None =>
                  // Successfully registered, will be called on completion
                  Eru.unit
              }
            }
            .attempt
            .flatMap {
              case Result.Success(either) =>
                either match {
                  case Left(error) => Eru.fail(error)
                  case Right(value) => Eru.succeed(value)
                }
              case Result.Failure(_) =>
                // This should never happen in a correctly implemented Promise
                throw new IllegalStateException("Promise await encountered unexpected error")
            }
      }
    }
  }
}
