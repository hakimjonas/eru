package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A synchronization primitive that allows a set of fibers to wait for each other to reach a common
  * barrier point.
  *
  * A `CyclicBarrier` is initialized with a fixed number of parties. When a fiber calls `await`, it
  * will block until all parties have called `await`. Once all parties reach the barrier, they are
  * all released simultaneously and the barrier can be reused for the next cycle.
  *
  * This is useful for coordinating multiple concurrent operations that need to synchronize at
  * specific points during their execution.
  *
  * This implementation is built entirely on Eru primitives (Ref), demonstrating pure functional
  * concurrency without any Java concurrent utilities.
  */
trait CyclicBarrier {

  /** Waits until all parties have invoked `await` on this barrier.
    *
    * If the current fiber is the last to arrive, all waiting fibers are released and this method
    * returns immediately. Otherwise, the fiber will suspend until all parties have arrived.
    *
    * After all parties are released, the barrier is reset for the next cycle.
    *
    * @return
    *   a suspending effect that succeeds when all parties have reached the barrier
    */
  def await: Suspending[Nothing, Unit]

  /** Returns the number of parties required to trip this barrier.
    *
    * @return
    *   an immediate effect that yields the number of parties
    */
  def getParties: Immediate[Nothing, Int]

  /** Returns the number of parties currently waiting at the barrier.
    *
    * This is useful for debugging or metrics, but should not be relied upon for control flow as the
    * value may change immediately after this call returns.
    *
    * @return
    *   an immediate effect that yields the number of waiting parties
    */
  def getNumberWaiting: Immediate[Nothing, Int]

  /** Checks whether the barrier is currently broken.
    *
    * A barrier becomes broken if one of the waiting fibers is interrupted or if an error occurs.
    * This implementation doesn't support barrier breaking, so this always returns false.
    *
    * @return
    *   an immediate effect that yields false (barriers don't break in this implementation)
    */
  def isBroken: Immediate[Nothing, Boolean] = new Immediate(Eru.succeed(false))
}

object CyclicBarrier {

  /** Creates a new `CyclicBarrier` that will trip when the given number of parties arrive.
    *
    * @param parties
    *   the number of parties required to trip the barrier, must be positive
    * @return
    *   an effect that yields the created barrier
    * @throws IllegalArgumentException
    *   if parties is not positive
    */
  def make(parties: Int)(using runtime: EruRuntime): Eru[Nothing, CyclicBarrier] = {
    require(parties > 0, "CyclicBarrier parties must be positive")
    for {
      stateRef <- Ref.make(BarrierState(0, 0, Nil))
    } yield new RuntimeCyclicBarrier(parties, stateRef, runtime)
  }

  /** Internal state representation for CyclicBarrier. */
  private case class BarrierState(
    generation: Int,
    waiting: Int,
    waiters: List[(Int, Unit => Unit)]
  )

  private final class RuntimeCyclicBarrier(parties: Int, stateRef: Ref[BarrierState], runtime: EruRuntime)
      extends CyclicBarrier {

    def getParties: Immediate[Nothing, Int] =
      new Immediate(Eru.succeed(parties))

    def getNumberWaiting: Immediate[Nothing, Int] =
      new Immediate(stateRef.get.map(_.waiting))

    def await: Suspending[Nothing, Unit] = new Suspending({
      if (parties == 1) {
        // Single party - no need to wait
        Eru.unit
      } else {
        runtime
          .suspend[Nothing, Unit] { callback =>
            // Create a wrapper callback
            val wrappedCallback: Unit => Unit = (_: Unit) => callback(Right(()))

            stateRef.modify { state =>
              val currentGen = state.generation
              val newWaiting = state.waiting + 1

              if (newWaiting == parties) {
                // Last party to arrive - trip the barrier
                // Release all waiters from this generation and reset
                val waitersToRelease = state.waiters.filter(_._1 == currentGen).map(_._2)
                val newState = BarrierState(
                  generation = currentGen + 1,
                  waiting = 0,
                  waiters = state.waiters.filterNot(_._1 == currentGen)
                )
                (newState, (true, waitersToRelease))
              } else {
                // Not the last party - add to waiters
                val newState = state.copy(
                  waiting = newWaiting,
                  waiters = (currentGen, wrappedCallback) :: state.waiters
                )
                (newState, (false, Nil))
              }
            }.flatMap { case (isLastParty, waitersToNotify) =>
              if (isLastParty) {
                // Notify all waiters and ourselves
                Eru.effectTotal {
                  waitersToNotify.foreach(callback => callback(()))
                  wrappedCallback(())
                }
              } else {
                // Successfully registered, will be notified when barrier trips
                Eru.unit
              }
            }
          }
          .attempt
          .map {
            case Result.Success(_) => ()
            case Result.Failure(_) =>
              throw new IllegalStateException("CyclicBarrier await encountered unexpected error")
          }
      }
    })
  }
}
