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
    *   an effect that succeeds when all parties have reached the barrier
    */
  def await: Eru[Nothing, Unit]

  /** Returns the number of parties required to trip this barrier.
    *
    * @return
    *   an effect that yields the number of parties
    */
  def getParties: Eru[Nothing, Int]

  /** Returns the number of parties currently waiting at the barrier.
    *
    * This is useful for debugging or metrics, but should not be relied upon for control flow as the
    * value may change immediately after this call returns.
    *
    * @return
    *   an effect that yields the number of waiting parties
    */
  def getNumberWaiting: Eru[Nothing, Int]

  /** Checks whether the barrier is currently broken.
    *
    * A barrier becomes broken if one of the waiting fibers is interrupted or if an error occurs.
    * This implementation doesn't support barrier breaking, so this always returns false.
    *
    * @return
    *   an effect that yields false (barriers don't break in this implementation)
    */
  def isBroken: Eru[Nothing, Boolean] = Eru.succeed(false)
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
    Eru.succeed(new RuntimeCyclicBarrier(parties, runtime))
  }

  private final class RuntimeCyclicBarrier(parties: Int, runtime: EruRuntime) extends CyclicBarrier {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicInteger

    private val currentGeneration = new AtomicInteger(0)
    private val partiesCurrentlyWaiting = new AtomicInteger(0)
    private val generationWaiters = new ConcurrentLinkedQueue[(Int, Either[Nothing, Unit] => Unit)]()

    /** Pure function to notify all waiters of the current generation. */
    private def notifyAllWaiters(currentGeneration: Int): Eru[Nothing, Unit] = {
      @annotation.tailrec
      def drainWaiters(acc: List[(Int, Either[Nothing, Unit] => Unit)]): List[(Int, Either[Nothing, Unit] => Unit)] = {
        Option(generationWaiters.poll()) match {
          case Some(waiter) => drainWaiters(waiter :: acc)
          case None => acc
        }
      }

      Eru.effect {
        val waitersToNotify = drainWaiters(Nil)
        waitersToNotify
          .filter(_._1 == currentGeneration)
          .foreach(_._2(Right(())))
      }.attempt.map(_ => ())
    }

    def getParties: Eru[Nothing, Int] =
      Eru.succeed(parties)

    def getNumberWaiting: Eru[Nothing, Int] =
      Eru.succeed(partiesCurrentlyWaiting.get())

    /** Pure function to register a callback with proper race condition handling. */
    private def safeRegisterCallback(
      currentGen: Int,
      callback: Either[Nothing, Unit] => Unit
    ): Eru[Nothing, Unit] = {
      def checkAndRegister: Eru[Nothing, Unit] = {
        val currentWaiting = partiesCurrentlyWaiting.incrementAndGet()
        if (currentWaiting == parties) {
          val tripBarrier = Eru.effect {
            partiesCurrentlyWaiting.set(0)
            currentGeneration.incrementAndGet()
          }.attempt.map(_ => ())

          val notifyAll = notifyAllWaiters(currentGen)
          val notifySelf = Eru.effect(callback(Right(()))).attempt.map(_ => ())

          tripBarrier.flatMap(_ => notifyAll).flatMap(_ => notifySelf)
        } else {
          val registerEffect = Eru.effect(generationWaiters.offer((currentGen, callback))).attempt.map(_ => ())
          val doubleCheck = Eru.succeed(currentGeneration.get()).flatMap { newGeneration =>
            if (newGeneration > currentGen) {
              Eru.effect {
                if (generationWaiters.remove((currentGen, callback))) {
                  callback(Right(()))
                }
              }.attempt.map(_ => ())
            } else {
              Eru.unit
            }
          }
          registerEffect.flatMap(_ => doubleCheck)
        }
      }

      checkAndRegister
    }

    def await: Eru[Nothing, Unit] = {
      val currentGen = currentGeneration.get()

      if (parties == 1) {
        Eru.unit
      } else {
        runtime
          .suspend[Nothing, Unit](callback => safeRegisterCallback(currentGen, callback))
          .attempt
          .map {
            case Result.Success(_) => ()
            case Result.Failure(_) =>
              throw new IllegalStateException("CyclicBarrier await encountered unexpected error")
          }
      }
    }
  }
}
