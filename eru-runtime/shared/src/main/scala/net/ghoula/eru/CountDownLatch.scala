package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A synchronization primitive that allows fibers to wait until a set of operations complete.
  *
  * A `CountDownLatch` is initialized with a given count. The `await` methods block until the count
  * reaches zero due to `countDown` invocations, after which all waiting fibers are released and any
  * subsequent await calls return immediately.
  *
  * This is useful for coordinating multiple concurrent operations where you need to wait for all of
  * them to complete before proceeding.
  *
  * This implementation is built entirely on Eru primitives (Ref), demonstrating pure functional
  * concurrency without any Java concurrent utilities.
  */
trait CountDownLatch {

  /** Decrements the count of the latch, releasing all waiting fibers if the count reaches zero.
    *
    * If the current count is already zero, this operation has no effect.
    *
    * @return
    *   an effect that succeeds when the count is decremented
    */
  def countDown: Immediate[Nothing, Unit]

  /** Awaits until the latch count reaches zero.
    *
    * If the count is already zero, this returns immediately. Otherwise, the calling fiber will
    * suspend until the count reaches zero due to `countDown` invocations.
    *
    * @return
    *   an effect that succeeds when the count reaches zero
    */
  def await: Suspending[Nothing, Unit]

  /** Returns the current count value.
    *
    * This is useful for debugging or metrics, but should not be relied upon for control flow as the
    * value may change immediately after this call returns.
    *
    * @return
    *   an effect that yields the current count
    */
  def getCount: Immediate[Nothing, Int]

  /** Checks whether the count has reached zero.
    *
    * @return
    *   an effect that yields `true` if the count is zero, `false` otherwise
    */
  def isZero: Immediate[Nothing, Boolean] = new Immediate(getCount.eru.map(_ == 0))
}

object CountDownLatch {

  /** Creates a new `CountDownLatch` initialized with the given count.
    *
    * @param count
    *   the initial count value, must be non-negative
    * @return
    *   an effect that yields the created latch
    * @throws IllegalArgumentException
    *   if count is negative
    */
  def make(count: Int)(using runtime: EruRuntime): Eru[Nothing, CountDownLatch] = {
    require(count >= 0, "CountDownLatch count must be non-negative")
    for {
      stateRef <- Ref.make(LatchState(count, Nil))
    } yield new RuntimeCountDownLatch(stateRef, runtime)
  }

  /** Internal state representation for CountDownLatch. */
  private case class LatchState(
    count: Int,
    waiters: List[Unit => Unit]
  )

  private final class RuntimeCountDownLatch(stateRef: Ref[LatchState], runtime: EruRuntime) extends CountDownLatch {

    def countDown: Immediate[Nothing, Unit] = new Immediate({
      stateRef.modify { state =>
        if (state.count <= 0) {
          // Already at zero, no change
          (state, (false, Nil))
        } else {
          val newCount = state.count - 1
          if (newCount == 0) {
            // Just hit zero - clear waiters and return them to notify
            (LatchState(0, Nil), (true, state.waiters))
          } else {
            // Just decrement
            (state.copy(count = newCount), (false, Nil))
          }
        }
      }.flatMap { case (hitZero, waitersToNotify) =>
        if (hitZero && waitersToNotify.nonEmpty) {
          // Notify all waiters
          Eru.effect {
            waitersToNotify.foreach(callback => callback(()))
          }.attempt.map(_ => ())
        } else {
          Eru.unit
        }
      }
    })

    def getCount: Immediate[Nothing, Int] = new Immediate(stateRef.get.map(_.count))

    def await: Suspending[Nothing, Unit] = new Suspending({
      // First check if already at zero
      stateRef.get.flatMap { state =>
        if (state.count == 0) {
          // Already at zero - return immediately
          Eru.unit
        } else {
          // Not at zero - suspend until countdown reaches zero
          runtime
            .suspend[Nothing, Unit] { callback =>
              // Create a wrapper callback
              val wrappedCallback: Unit => Unit = (_: Unit) => callback(Right(()))

              // Register callback in a pure way
              val registerCallback = stateRef.modify { state =>
                if (state.count == 0) {
                  // Race condition: reached zero while registering
                  (state, true)
                } else {
                  // Add callback to waiters
                  (state.copy(waiters = wrappedCallback :: state.waiters), false)
                }
              }

              registerCallback.flatMap { reachedZero =>
                if (reachedZero) {
                  // Latch reached zero during registration
                  Eru.effect(wrappedCallback(())).attempt.map(_ => ())
                } else {
                  // Successfully registered, will be called when count reaches zero
                  Eru.unit
                }
              }
            }
            .attempt
            .flatMap {
              case Result.Success(_) => Eru.unit
              case Result.Failure(_) =>
                // Fall back to polling mode when suspend fails
                pollUntilZero()
            }
        }
      }
    })

    /** Polling fallback for backends that don't support suspend. */
    private def pollUntilZero(): Eru[Nothing, Unit] = {
      def checkAndRepeat: Eru[Nothing, Unit] =
        stateRef.get.flatMap { state =>
          if (state.count == 0) {
            Eru.unit
          } else {
            // Small delay to avoid busy-waiting, then check again
            Eru.effect(Thread.`yield`()).attempt.flatMap(_ => checkAndRepeat)
          }
        }
      checkAndRepeat
    }
  }
}
