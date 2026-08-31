package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A single-assignment asynchronous variable.
  *
  * A `Deferred[A]` starts empty and can be completed exactly once with a value of type `A`. Once
  * completed, all waiters are notified and subsequent await calls return immediately.
  *
  * This implementation is built entirely on Eru primitives (Ref), demonstrating pure functional
  * concurrency without any Java concurrent utilities. Deferred is a simplified Promise that only
  * supports successful completion.
  */
trait Deferred[A] {

  /** Completes this `Deferred` with the provided value if it has not been completed yet.
    *
    * @param a
    *   the value to complete the deferred with
    * @return
    *   an immediate effect that yields `true` if this invocation completed the deferred, or `false`
    *   if it was already completed
    */
  def complete(a: A): Immediate[Nothing, Boolean]

  /** Awaits completion, returning the value when available.
    *
    * This operation suspends until the deferred is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   a suspending effect that yields the completed value
    */
  def await: Suspending[Nothing, A]

  /** Checks whether this deferred has been completed.
    *
    * @return
    *   an immediate effect that yields `true` if the deferred is completed, `false` otherwise
    */
  def isDone: Immediate[Nothing, Boolean]

  /** Attempts to retrieve the current value without suspending.
    *
    * @return
    *   an immediate effect that yields `Some(value)` if completed, or `None` if still pending
    */
  def poll: Immediate[Nothing, Option[A]]
}

object Deferred {

  /** Creates a new, empty `Deferred[A]`.
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the created deferred
    */
  def make[A](using runtime: EruRuntime): Eru[Nothing, Deferred[A]] =
    Ref.make(DeferredState.empty[A]).map(stateRef => new RuntimeDeferred[A](stateRef, runtime))

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

    def complete(a: A): Immediate[Nothing, Boolean] = new Immediate({
      stateRef.modify {
        case Pending(waiters) =>
          (Completed(a), (true, waiters))
        case completed: Completed[A] =>
          (completed, (false, Nil))
      }.flatMap { case (wasCompleted, waitersToNotify) =>
        if (wasCompleted && waitersToNotify.nonEmpty) {
          Eru.effectTotal {
            waitersToNotify.foreach(_(a))
            true
          }
        } else {
          Eru.succeed(wasCompleted)
        }
      }
    })

    def isDone: Immediate[Nothing, Boolean] =
      new Immediate(stateRef.get.map(_.isCompleted))

    def poll: Immediate[Nothing, Option[A]] =
      new Immediate(stateRef.get.map(_.value))

    def await: Suspending[Nothing, A] = new Suspending({
      stateRef.get.flatMap {
        case Completed(value) =>
          Eru.succeed(value)
        case Pending(_) =>
          runtime
            .suspend[Nothing, A] { callback =>
              val wrappedCallback: A => Unit = (value: A) => callback(Right(value))

              val registerCallback = stateRef.modify {
                case Pending(waiters) =>
                  (Pending(wrappedCallback :: waiters), None)
                case Completed(value) =>
                  (Completed(value), Some(value))
              }

              registerCallback.flatMap {
                case Some(value) =>
                  Eru.effectTotal {
                    wrappedCallback(value)
                  }
                case None =>
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
    })
  }
}
