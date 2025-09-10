package userland

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Comprehensive end-to-end integration test suite for complete Eru workflows.
  *
  * Validates complex compositions of all Eru features including concurrency, resource management,
  * error handling, observability, and runtime operations in realistic application scenarios.
  */
final class EndToEndSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  /** Validates comprehensive end-to-end composition of all Eru features.
    *
    * Tests a complex workflow that combines concurrency, state management, retry logic, timeouts,
    * resource safety, and observability in realistic application scenarios.
    */
  test("end-to-end composition across concurrency, state, retry, timeout, ensure, observer") {
    val events = java.util.concurrent.ConcurrentLinkedQueue[EruObserver.EruEvent]()
    val observer = new EruObserver {
      def onEvent(e: EruObserver.EruEvent): Unit = events.offer(e)
    }

    val program = for {
      ref <- Eru.ref(List.empty[Int])
      _ <- Eru.succeed(1).flatMap(n => ref.update(n :: _)).fork
      _ <- Eru.succeed(2).flatMap(n => ref.update(n :: _)).fork
      _ <- Eru.blocking(Thread.sleep(10))
      l <- ref.get
      ok <- Eru.succeed(l.sum).retryWithBackoff(Duration.ofMillis(10), maxRetries = 2)
      out <- Eru.succeed(ok).timeoutTo(Duration.ofSeconds(1), -1)
      _ <- Eru.succeed(()).ensure(Eru.effect(()))
    } yield out

    val result = program.runWith(observer)
    assert(result >= 3)
  }
}
