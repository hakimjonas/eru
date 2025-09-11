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
  */
trait CountDownLatch {

  /** Decrements the count of the latch, releasing all waiting fibers if the count reaches zero.
    *
    * If the current count is already zero, this operation has no effect.
    *
    * @return
    *   an effect that succeeds when the count is decremented
    */
  def countDown: Eru[Nothing, Unit]

  /** Awaits until the latch count reaches zero.
    *
    * If the count is already zero, this returns immediately. Otherwise, the calling fiber will
    * suspend until the count reaches zero due to `countDown` invocations.
    *
    * @return
    *   an effect that succeeds when the count reaches zero
    */
  def await: Eru[Nothing, Unit]

  /** Returns the current count value.
    *
    * This is useful for debugging or metrics, but should not be relied upon for control flow as the
    * value may change immediately after this call returns.
    *
    * @return
    *   an effect that yields the current count
    */
  def getCount: Eru[Nothing, Int]

  /** Checks whether the count has reached zero.
    *
    * @return
    *   an effect that yields `true` if the count is zero, `false` otherwise
    */
  def isZero: Eru[Nothing, Boolean] = getCount.map(_ == 0)
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
    Eru.succeed(new RuntimeCountDownLatch(count, runtime))
  }

  private final class RuntimeCountDownLatch(initialCount: Int, runtime: EruRuntime) extends CountDownLatch {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicInteger

    // Current count - when it reaches 0, all waiters are notified
    private val count = new AtomicInteger(initialCount)
    // Queue of waiting callbacks
    private val waiters = new ConcurrentLinkedQueue[Either[Nothing, Unit] => Unit]()

    /** Pure function to notify all waiters when count reaches zero. */
    private def notifyAllWaiters: Eru[Nothing, Unit] = {
      @annotation.tailrec
      def drainWaiters(acc: List[Either[Nothing, Unit] => Unit]): List[Either[Nothing, Unit] => Unit] = {
        Option(waiters.poll()) match {
          case Some(waiter) => drainWaiters(waiter :: acc)
          case None => acc
        }
      }

      Eru.effect {
        val waitersToNotify = drainWaiters(Nil)
        waitersToNotify.foreach(_(Right(())))
      }.attempt.map(_ => ())
    }

    def countDown: Eru[Nothing, Unit] = {
      val decrementAndCheck = Eru.effect {
        // Use compareAndSet loop to prevent count from going below zero
        @annotation.tailrec
        def decrementIfPositive(): Boolean = {
          val current = count.get()
          if (current <= 0) {
            false // Already at zero, no decrement
          } else {
            val newCount = current - 1
            if (count.compareAndSet(current, newCount)) {
              newCount == 0 // Return true if we just hit zero
            } else {
              decrementIfPositive() // Retry due to race condition
            }
          }
        }
        decrementIfPositive()
      }.attempt.map {
        case Result.Success(hitZero) => hitZero
        case Result.Failure(_) => false
      }

      decrementAndCheck.flatMap { hitZero =>
        if (hitZero) notifyAllWaiters
        else Eru.unit
      }
    }

    def getCount: Eru[Nothing, Int] =
      Eru.succeed(count.get())

    /** Pure function to register a callback with proper race condition handling. */
    private def safeRegisterCallback(callback: Either[Nothing, Unit] => Unit): Eru[Nothing, Unit] = {
      def checkAndRegister: Eru[Nothing, Unit] =
        Eru.succeed(count.get()).flatMap { currentCount =>
          if (currentCount == 0) {
            // Already at zero - invoke callback immediately
            Eru.effect(callback(Right(()))).attempt.map(_ => ())
          } else {
            // Not at zero - register callback and double-check
            val registerEffect = Eru.effect(waiters.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(count.get()).flatMap { newCount =>
              if (newCount == 0) {
                // Race condition: reached zero after registration
                Eru.effect {
                  if (waiters.remove(callback)) {
                    callback(Right(()))
                  }
                  // If remove failed, callback will be invoked by countdown
                }.attempt.map(_ => ())
              } else {
                // Still not zero - callback will be invoked by countdown
                Eru.unit
              }
            }
            registerEffect.flatMap(_ => doubleCheck)
          }
        }

      checkAndRegister
    }

    def await: Eru[Nothing, Unit] =
      Eru.succeed(count.get()).flatMap { currentCount =>
        if (currentCount == 0) {
          // Already at zero - return immediately
          Eru.unit
        } else {
          // Not at zero - suspend until countdown reaches zero
          runtime
            .suspend[Nothing, Unit](safeRegisterCallback)
            .attempt
            .map {
              case Result.Success(_) => ()
              case Result.Failure(_) =>
                // This should never happen in a correctly implemented CountDownLatch
                throw new IllegalStateException("CountDownLatch await encountered unexpected error")
            }
        }
      }
  }
}
