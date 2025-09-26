package net.ghoula.eru.test

import java.time.Duration

import net.ghoula.eru.*

/** Test helpers for Queue operations.
  *
  * These helpers provide utilities for testing Queue operations safely.
  */
object SuspensionSafetyHelpers {

  /** Runs an effect synchronously.
    *
    * @param effect
    *   the effect to run
    * @return
    *   the result of the effect
    */
  def safeSyncRun[E, A](effect: Eru[E, A]): A =
    effect.unsafeRunSync()

  /** Safely tests a potentially suspending effect using forking.
    *
    * @param effect
    *   the potentially suspending effect
    * @return
    *   an effect that can be safely run in tests
    */
  def safeAsyncTest[E, A](effect: Eru[E, A])(using runtime: EruRuntime): Eru[E | Throwable, A] = {
    import RuntimeExtensions.fork
    effect.fork.flatMap(_.await).flatMap(Eru.fromExit)
  }

  /** Runs a suspending effect with a timeout to prevent hanging.
    *
    * @param effect
    *   the suspending effect
    * @param timeoutMs
    *   timeout in milliseconds
    * @return
    *   either the result or a timeout error
    */
  def runWithTimeout[E, A](
    effect: Eru[E, A],
    timeoutMs: Long = 1000
  )(using runtime: EruRuntime): Eru[E | TimeoutError | Throwable, A] = {
    val timeout = runtime
      .sleep(Duration.ofMillis(timeoutMs))
      .map(_ => None: Option[A])

    val computation = effect.map(Some(_))

    runtime.race(computation, timeout).flatMap {
      case Left(Some(value)) => Eru.succeed(value)
      case Right(None) => Eru.fail(TimeoutError(s"Operation timed out after ${timeoutMs}ms"))
      case _ => Eru.fail(TimeoutError("Unexpected race result"))
    }
  }

  /** Error type for timeout operations. */
  case class TimeoutError(message: String) extends Exception(message)

  /** Test utilities for queue operations. */
  object QueueTestUtils {

    /** Safely tests non-blocking queue operations. */
    def testNonBlocking[A](queue: Queue[A]): Unit = {
      safeSyncRun(queue.tryPut(null.asInstanceOf[A]))
      safeSyncRun(queue.tryTake)
      safeSyncRun(queue.poll)
      safeSyncRun(queue.size)
      safeSyncRun(queue.isEmpty)
      safeSyncRun(queue.remainingCapacity)
    }

    /** Safely tests blocking queue operations with proper coordination. */
    def testBlocking[A](queue: Queue[A], item: A)(using runtime: EruRuntime): Eru[Throwable, A] = {
      for {
        // Fork the suspending take operation
        takeFiber <- runtime.fork(safeAsyncTest(queue.take))

        // Give it time to register
        _ <- runtime.sleep(Duration.ofMillis(10))

        // Now put an item to unblock it
        _ <- safeAsyncTest(queue.put(item))

        // Await the result
        result <- takeFiber.await.flatMap(Eru.fromExit)
      } yield result
    }

    /** Tests timeout operations which are inherently safe. */
    def testTimeouts[A](queue: Queue[A], item: A): Unit = {
      val timeout = Duration.ofMillis(100)

      // These have bounded wait times
      safeSyncRun(queue.putWithin(item, timeout).recover { case _: Throwable => false })
      safeSyncRun(queue.takeWithin(timeout).recover { case _: Throwable => None })
      safeSyncRun(queue.putAllWithin(Seq(item), timeout).recover { case _: Throwable => 0 })
      safeSyncRun(queue.takeUpToWithin(5, timeout).recover { case _: Throwable => Nil })
    }
  }
}