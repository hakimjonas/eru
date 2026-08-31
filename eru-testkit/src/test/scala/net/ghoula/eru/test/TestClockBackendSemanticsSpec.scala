package net.ghoula.eru.test

import java.time.{Duration, Instant}
import java.util.concurrent.TimeoutException

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Deterministic logical-time semantics of TestClockBackend.
  *
  * Every time-dependent outcome (sleep completion, timeout, race winners, retry delays) must be
  * decided by logical clock advancement alone: no real-time fallback, no swallowed suspension
  * failures. Tests that exercise the old real-time fallback path are marked and bounded by wall
  * time so the regression is caught deterministically (the fallback took ~2.1s of real time).
  */
final class TestClockBackendSemanticsSpec extends munit.FunSuite {

  /** Waits (real time, up to 2s) until the clock has exactly `count` scheduled callbacks.
    *
    * Forked fibers start asynchronously, so callbacks are registered slightly after forking. This
    * poll is a pure synchronization mechanism: it does not decide any outcome, it only waits for
    * the fiber to reach its scheduling point.
    */
  private def awaitScheduled(clock: TestClock, count: Int): Unit = {
    var spins = 0
    while (clock.pendingCount != count && spins < 2000) {
      Thread.sleep(1L)
      spins += 1
    }
    assertEquals(clock.pendingCount, count, s"expected $count scheduled callbacks")
  }

  private def withBackend[A](body: (TestClock, EruRuntime) => A): A = {
    val clock = TestClock.create(Instant.parse("2026-01-01T00:00:00Z"))
    val runtime = EruRuntime.withBackend(TestClockBackend(clock))
    try body(clock, runtime)
    finally { val _ = clock.completeAll }
  }

  private def awaitExit[E, A](fiber: Fiber[E, A]): Exit[E, A] =
    fiber.await.unsafeRunSync()

  test("timeout fails with TimeoutException exactly when the clock advances past the deadline") {
    withBackend { (clock, runtime) =>
      val effect = runtime.sleep(Duration.ofSeconds(10)).map(_ => "completed")
      val fiber = runtime.fork(runtime.timeout(Duration.ofSeconds(5))(effect)).unsafeRunSync()

      awaitScheduled(clock, 2)
      clock.advance(Duration.ofSeconds(4))
      assertEquals(clock.pendingCount, 2, "deadline and sleep must be pending before the deadline passes")

      clock.advance(Duration.ofSeconds(2))
      awaitExit(fiber) match {
        case Exit.Die(_: TimeoutException) => ()
        case Exit.Failure(_: TimeoutException) => ()
        case other => fail(s"Expected a TimeoutException outcome, got: $other")
      }
    }
  }

  test("timeout lets a fast effect win without any clock advancement") {
    withBackend { (_, runtime) =>
      val quick = Eru.succeed(42)
      val result = runtime.timeout(Duration.ofSeconds(5))(quick).unsafeRunSync()
      assertEquals(result, 42)
    }
  }

  test("race decides the winner by logical time, without real-time fallback") {
    withBackend { (clock, runtime) =>
      val slow = runtime.sleep(Duration.ofSeconds(5)).map(_ => "slow")
      val fast = runtime.sleep(Duration.ofSeconds(1)).map(_ => "fast")
      val fiber = runtime.fork(runtime.race(slow, fast)).unsafeRunSync()

      awaitScheduled(clock, 2)
      clock.advance(Duration.ofSeconds(2))

      val t0 = System.nanoTime()
      val exit = awaitExit(fiber)
      val wallMs = (System.nanoTime() - t0) / 1_000_000L

      exit match {
        case Exit.Success(Right("fast")) => ()
        case other => fail(s"Expected the 1s side to win, got: $other")
      }
      assert(wallMs < 1000L, s"Race resolved via real-time fallback (${wallMs}ms)")
    }
  }

  test("a sleeping fiber never completes without clock advancement") {
    withBackend { (clock, runtime) =>
      val fiber = runtime.fork(runtime.sleep(Duration.ofHours(1))).unsafeRunSync()

      awaitScheduled(clock, 1)
      Thread.sleep(2500L)

      fiber match {
        case uf: UnifiedFiber[?, ?] =>
          uf.currentState match {
            case UnifiedFiberState.Active(_, _, _, _, _, _) => ()
            case UnifiedFiberState.Completed(_) =>
              fail("Sleep completed without clock advancement (real-time fallback)")
          }
        case other => fail(s"Unexpected fiber type: $other")
      }
      assertEquals(clock.pendingCount, 1, "the sleep callback must stay pending")
    }
  }

  test("sleep completes when the clock reaches its target, and not before") {
    withBackend { (clock, runtime) =>
      val fiber = runtime.fork(runtime.sleep(Duration.ofSeconds(10)).map(_ => "awake")).unsafeRunSync()

      awaitScheduled(clock, 1)
      clock.advance(Duration.ofSeconds(9))
      assertEquals(clock.pendingCount, 1, "sleep must not complete before its target")

      clock.advance(Duration.ofSeconds(1))
      awaitExit(fiber) match {
        case Exit.Success("awake") => ()
        case other => fail(s"Expected success after reaching the target, got: $other")
      }
      assertEquals(clock.pendingCount, 0)
    }
  }

  test("retry with exponential backoff honors logical delays") {
    withBackend { (clock, runtime) =>
      var attempts = 0
      val failing = Eru.effect { attempts += 1; () }.flatMap(_ => Eru.fail("boom"))
      val policy = EruRuntime.Policy.Exponential(Duration.ofMillis(100), 2)

      val t0 = System.nanoTime()
      val fiber = runtime.fork(runtime.retry(policy)(failing)).unsafeRunSync()

      awaitScheduled(clock, 1)
      clock.advance(Duration.ofMillis(150))
      awaitScheduled(clock, 1)
      clock.advance(Duration.ofMillis(200))

      val exit = awaitExit(fiber)
      val wallMs = (System.nanoTime() - t0) / 1_000_000L

      exit match {
        case Exit.Failure("boom") => ()
        case other => fail(s"Expected the typed failure after retries, got: $other")
      }
      assertEquals(attempts, 3)
      assert(wallMs < 1000L, s"Retry delays resolved via real-time fallback (${wallMs}ms)")
    }
  }

  test("timeout and race outcomes are identical across 50 runs") {
    (1 to 50).foreach { _ =>
      withBackend { (clock, runtime) =>
        val slow = runtime.sleep(Duration.ofSeconds(5)).map(_ => "slow")
        val fast = runtime.sleep(Duration.ofSeconds(1)).map(_ => "fast")
        val raceFiber = runtime.fork(runtime.race(slow, fast)).unsafeRunSync()
        awaitScheduled(clock, 2)
        clock.advance(Duration.ofSeconds(2))
        awaitExit(raceFiber) match {
          case Exit.Success(Right("fast")) => ()
          case other => fail(s"Race outcome drifted between runs: $other")
        }

        val timed = runtime.timeout(Duration.ofSeconds(5))(
          runtime.sleep(Duration.ofSeconds(10)).map(_ => "late")
        )
        val timeoutFiber = runtime.fork(timed).unsafeRunSync()
        awaitScheduled(clock, 2)
        clock.advance(Duration.ofSeconds(6))
        awaitExit(timeoutFiber) match {
          case Exit.Die(_: TimeoutException) => ()
          case Exit.Failure(_: TimeoutException) => ()
          case other => fail(s"Timeout outcome drifted between runs: $other")
        }
      }
    }
  }
}
