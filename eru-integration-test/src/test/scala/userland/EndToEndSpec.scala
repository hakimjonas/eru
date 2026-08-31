package userland

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTest

/** Comprehensive end-to-end integration test suite for complete Eru workflows.
  *
  * Validates complex compositions of all Eru features including concurrency, resource management,
  * error handling, observability, and runtime operations in realistic application scenarios.
  */
final class EndToEndSpec extends munit.FunSuite {

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
      f1 <- Eru.succeed(1).flatMap(n => ref.update(n :: _)).fork
      f2 <- Eru.succeed(2).flatMap(n => ref.update(n :: _)).fork
      _ <- f1.await
      _ <- f2.await
      l <- ref.get
      ok <- Eru.succeed(l.sum).retryWithBackoff(Duration.ofMillis(10), maxRetries = 2)
      out <- Eru.succeed(ok).timeoutTo(Duration.ofSeconds(1), -1)
      _ <- Eru.succeed(()).ensure(Eru.effect(()))
    } yield out

    val result = program.runWith(observer)
    assert(result >= 3)
  }

  /** TestClock version of the composition test, giving deterministic timing for retry/timeout
    * logic.
    *
    * A TestClock-backed sleep completes only by advancing the clock. The program therefore parks on
    * its 10ms sleep, so it is run in a fiber and the clock is driven until the fiber completes.
    */
  test("end-to-end composition - TestClock version (deterministic timing for retry/timeout logic)") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)

      val events = java.util.concurrent.ConcurrentLinkedQueue[EruObserver.EruEvent]()
      val observer = new EruObserver {
        def onEvent(e: EruObserver.EruEvent): Unit = events.offer(e)
      }

      val program = for {
        ref <- Eru.ref(List.empty[Int])
        _ <- Eru.succeed(1).flatMap(n => ref.update(n :: _)).fork
        _ <- Eru.succeed(2).flatMap(n => ref.update(n :: _)).fork
        _ <- runtime.sleep(Duration.ofMillis(10))
        l <- ref.get
        ok <- Eru.succeed(l.sum).retryWithBackoff(Duration.ofMillis(10), maxRetries = 2)
        out <- Eru.succeed(ok).timeoutTo(Duration.ofSeconds(1), -1)
        _ <- Eru.succeed(()).ensure(Eru.effect(()))
      } yield out

      val fiber = program.forkWithObserver(observer).unsafeRunSync()

      var spins = 0
      while (clock.pendingCount == 0 && spins < 2000) {
        Thread.sleep(1L)
        spins += 1
      }
      var steps = 0
      while (clock.pendingCount > 0 && steps < 1000) {
        clock.advance(Duration.ofMillis(1))
        steps += 1
      }

      fiber.await.unsafeRunSync() match {
        case Exit.Success(result) => assert(result >= 3)
        case other => fail(s"Expected successful composition, got: $other")
      }
    }
  }
}
