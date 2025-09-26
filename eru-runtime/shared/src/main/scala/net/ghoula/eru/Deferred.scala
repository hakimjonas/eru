package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A single-assignment asynchronous variable.
  *
  * A `Deferred[A]` starts empty and can be completed exactly once with a value of type `A`. Once
  * completed, all waiters are notified and subsequent await calls return immediately.
  *
  * This implementation is built entirely on Eru primitives (Ref), demonstrating pure functional
  * concurrency without any Java concurrent utilities. Deferred is essentially a simplified Promise
  * that only supports successful completion.
  */
trait Deferred[A] {

  /** Completes this `Deferred` with the provided value if it has not been completed yet.
    *
    * @param a
    *   the value to complete the deferred with
    * @return
    *   an effect that yields `true` if this invocation completed the deferred, or `false` if it was
    *   already completed
    */
  def complete(a: A): Eru[Nothing, Boolean]

  /** Awaits completion, returning the value when available.
    *
    * This operation suspends until the deferred is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   an effect that yields the completed value
    */
  def await: Eru[Nothing, A]

  /** Checks whether this deferred has been completed.
    *
    * @return
    *   an effect that yields `true` if the deferred is completed, `false` otherwise
    */
  def isDone: Eru[Nothing, Boolean]

  /** Attempts to retrieve the current value without suspending.
    *
    * @return
    *   an effect that yields `Some(value)` if completed, or `None` if still pending
    */
  def poll: Eru[Nothing, Option[A]]
}

object Deferred {

  /** Creates a new, empty `Deferred[A]`.
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the created deferred
    */
  def make[A](using runtime: EruRuntime): Eru[Nothing, Deferred[A]] =
    for {
      stateRef <- Ref.make(DeferredState.empty[A])
    } yield new RuntimeDeferred[A](stateRef, runtime)

  /** Internal state representation for Deferred. */
  private sealed trait DeferredState[A] {
    def isCompleted: Boolean
    def value: Option[A]
    def waiters: List[A => Unit]
  }

  private object DeferredState {

    /** An empty deferred with no value and potentially waiting callbacks. */
    case class Pending[A](waiters: List[A => Unit]) extends DeferredState[A] {
      def isCompleted: Boolean = false
      def value: Option[A] = None
    }

    /** A completed deferred with a value and no waiters. */
    case class Completed[A](result: A) extends DeferredState[A] {
      def isCompleted: Boolean = true
      def value: Option[A] = Some(result)
      def waiters: List[A => Unit] = Nil
    }

    def empty[A]: DeferredState[A] = Pending(Nil)
  }

  private final class RuntimeDeferred[A](stateRef: Ref[DeferredState[A]], runtime: EruRuntime) extends Deferred[A] {
    import DeferredState.*

    def complete(a: A): Eru[Nothing, Boolean] = {
      stateRef.modify {
        case Pending(waiters) =>
          // Transition to completed and return waiters to notify
          (Completed(a), (true, waiters))
        case completed: Completed[A] =>
          // Already completed, no change
          (completed, (false, Nil))
      }.flatMap { case (wasCompleted, waitersToNotify) =>
        if (wasCompleted && waitersToNotify.nonEmpty) {
          // Notify all waiters
          Eru.effect {
            waitersToNotify.foreach(_(a))
          }.attempt.map(_ => true)
        } else {
          Eru.succeed(wasCompleted)
        }
      }
    }

    def isDone: Eru[Nothing, Boolean] =
      stateRef.get.map(_.isCompleted)

    def poll: Eru[Nothing, Option[A]] =
      stateRef.get.map(_.value)

    def await: Eru[Nothing, A] = {
      // First check if already completed
      stateRef.get.flatMap {
        case Completed(value) =>
          // Already completed - return immediately without suspension
          Eru.succeed(value)
        case Pending(_) =>
          // Not completed - need to suspend
          runtime
            .suspend[Nothing, A] { callback =>
              // Create a wrapper callback that matches the expected type
              val wrappedCallback: A => Unit = (value: A) => callback(Right(value))

              // Register callback in a pure way
              val registerCallback = stateRef.modify {
                case Pending(waiters) =>
                  // Add callback to waiters
                  (Pending(wrappedCallback :: waiters), None)
                case Completed(value) =>
                  // Race condition: completed while registering
                  // Return the value to invoke callback immediately
                  (Completed(value), Some(value))
              }

              registerCallback.flatMap {
                case Some(value) =>
                  // Deferred was completed during registration
                  Eru.effect(wrappedCallback(value)).attempt.map(_ => ())
                case None =>
                  // Successfully registered, will be called on completion
                  Eru.unit
              }
            }
            .attempt
            .map {
              case Result.Success(value) => value
              case Result.Failure(throwable) =>
                throw new IllegalStateException("Deferred await encountered unexpected error", throwable)
            }
      }
    }
  }
}
