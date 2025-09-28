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
  def succeed(value: A): Immediate[Nothing, Boolean]

  /** Completes this `Promise` with a failure value if it has not been completed yet.
    *
    * @param error
    *   the failure value to complete the promise with
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def fail(error: E): Immediate[Nothing, Boolean]

  /** Completes this `Promise` with the result of another effect if it has not been completed yet.
    *
    * @param effect
    *   the effect whose result will be used to complete the promise
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def complete(effect: Eru[E, A]): Immediate[Nothing, Boolean] = new Immediate(effect.attempt.flatMap {
    case Result.Success(value) => succeed(value).eru
    case Result.Failure(error) => fail(error).eru
  })

  /** Awaits completion, returning the result when available.
    *
    * This operation suspends until the promise is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   an effect that yields the completed result (success or failure)
    */
  def await: Suspending[E, A]

  /** Checks whether this promise has been completed.
    *
    * @return
    *   an effect that yields `true` if the promise is completed, `false` otherwise
    */
  def isDone: Immediate[Nothing, Boolean]

  /** Attempts to retrieve the current result without suspending.
    *
    * @return
    *   an immediate effect that yields `Some(result)` if completed, or `None` if still pending
    */
  def poll: Immediate[Nothing, Option[Exit[E, A]]]

  /** Alias for poll for consistency with suspension naming conventions.
    *
    * @return
    *   an immediate effect that yields `Some(result)` if completed, or `None` if still pending
    */
  def tryGet: Immediate[Nothing, Option[Exit[E, A]]] = poll
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
    Ref.make(PromiseState.empty[E, A]).map(stateRef => new RuntimePromise[E, A](stateRef, runtime))

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

    /** A promise completed with a success value, storing the Eru effect directly. */
    case class CompletedSuccess[E, A](value: A) extends PromiseState[E, A] {
      def isCompleted: Boolean = true
      def result: Option[Exit[E, A]] = Some(Exit.Success(value))
      def waiters: List[Either[E, A] => Unit] = Nil
    }

    /** A promise completed with a failure, storing the full Exit for error cases. */
    case class CompletedFailure[E, A](exit: Exit[E, A]) extends PromiseState[E, A] {
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
      val newState: PromiseState[E, A] = exit match {
        case Exit.Success(value) => CompletedSuccess(value)
        case other => CompletedFailure(other)
      }

      stateRef.modify {
        case Pending(waiters) =>
          (newState, (true, waiters))
        case completed =>
          (completed, (false, Nil))
      }.flatMap { case (wasCompleted, waitersToNotify) =>
        if (wasCompleted && waitersToNotify.nonEmpty) {
          val result = exitToEither(exit)
          Eru.effectTotal {
            waitersToNotify.foreach(_(result))
            true
          }
        } else {
          Eru.succeed(wasCompleted)
        }
      }
    }

    def succeed(value: A): Immediate[Nothing, Boolean] = new Immediate(attemptComplete(Exit.Success(value)))

    def fail(error: E): Immediate[Nothing, Boolean] = new Immediate(attemptComplete(Exit.Failure(error)))

    def isDone: Immediate[Nothing, Boolean] = new Immediate(stateRef.get.map(_.isCompleted))

    def poll: Immediate[Nothing, Option[Exit[E, A]]] = new Immediate(stateRef.get.map(_.result))

    def await: Suspending[E, A] = new Suspending({
      // Fast path: check state first without defer
      stateRef.get.flatMap {
        // Direct return for success case - no wrapping needed!
        case CompletedSuccess(value) =>
          Eru.succeed(value)
        case CompletedFailure(Exit.Failure(error)) =>
          Eru.fail(error)
        case CompletedFailure(exit) =>
          // Rare case: Die or Interrupt
          throw new IllegalStateException(s"Promise completed with unexpected exit type: $exit")
        case Pending(_) =>
          runtime
            .suspend[Nothing, Either[E, A]] { callback =>
              val wrappedCallback: Either[E, A] => Unit = (result: Either[E, A]) => callback(Right(result))

              val registerCallback = stateRef.modify {
                case Pending(waiters) =>
                  (Pending(wrappedCallback :: waiters), None)
                case completed @ CompletedSuccess(value) =>
                  (completed, Some(Right(value)))
                case completed @ CompletedFailure(exit) =>
                  (completed, Some(exitToEither(exit)))
              }

              registerCallback.flatMap {
                case Some(result) =>
                  Eru.effectTotal {
                    wrappedCallback(result)
                  }
                case None =>
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
                throw new IllegalStateException("Promise await encountered unexpected error")
            }
      }
    })
  }
}
