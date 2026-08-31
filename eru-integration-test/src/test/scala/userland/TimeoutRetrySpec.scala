package userland

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTest

/** Integration test suite for timeout and retry functionality in real-world scenarios.
  *
  * Validates timeout operations, retry policies, and their composition in practical use cases that
  * reflect production application patterns. These tests ensure that timeout and retry mechanisms
  * provide reliable behavior for handling unreliable external dependencies, network operations, and
  * other failure-prone interactions while maintaining correctness and resource safety guarantees.
  */
final class TimeoutRetrySpec extends TestProgressReporter {

  given runtime: EruRuntime = EruRuntime.create()

  /** Validates that timeoutTo preserves successful values or yields fallback values.
    *
    * This is a smoke test at real wall-clock timings; the deterministic logic test uses TestClock
    * below. Durations are chosen well above the HashedTimerWheel's 10ms tick resolution so ordering
    * is reliable: a fast completion preserves the original value, and a 500ms sleep raced against a
    * 50ms timeout yields the fallback value with ~10x headroom over wheel granularity plus dispatch
    * latency.
    */
  test("timeoutTo either preserves value or yields fallback") {
    val fast = Eru.succeed(1)
    val timedFast = fast.timeoutTo(Duration.ofMillis(100), 0)
    assertEquals(timedFast.runExit(), Exit.Success(1))

    val slow = runtime.sleep(Duration.ofMillis(500)).map(_ => 1)
    val timedSlow = slow.timeoutTo(Duration.ofMillis(50), 0)
    assertEquals(timedSlow.runExit(), Exit.Success(0))
  }

  /** Validates that retryN mechanism re-executes failing computations until success.
    *
    * Tests the retry functionality by creating a computation that fails initially but succeeds
    * after a specified number of attempts, ensuring the retry mechanism works correctly.
    */
  test("retryN re-executes typed failures until success") {
    var attempts = 0
    val flaky: Eru[Throwable | String, Int] =
      Eru.effect { attempts += 1; attempts }
        .flatMap(n => if (n < 3) Eru.fail("boom") else Eru.succeed(42))
    val retried = flaky.retryN(5)
    assertEquals(retried.runExit(), Exit.Success(42), "Retry should succeed after 3 attempts")
  }

  /** Deterministic TestClock version of the timeoutTo test.
    *
    * Fast completion preserves the original value without any clock advancement. For the slow
    * effect, a 5ms timeout is raced against a 20ms sleep: advancing the clock past the deadline
    * (but not the sleep target) makes the timeout win deterministically.
    */
  test("timeoutTo either preserves value or yields fallback - TestClock version (deterministic)") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)

      val fast = Eru.succeed(1)
      val timedFast = fast.timeoutTo(Duration.ofMillis(10), 0)
      assertEquals(timedFast.runExit(), Exit.Success(1))

      val slow = runtime.sleep(Duration.ofMillis(20)).map(_ => 1)
      val timedSlow = slow.timeoutTo(Duration.ofMillis(5), 0)
      val fiber = runtime.fork(timedSlow).unsafeRunSync()

      var spins = 0
      while (clock.pendingCount < 2 && spins < 2000) {
        Thread.sleep(1L)
        spins += 1
      }
      clock.advance(Duration.ofMillis(6))

      fiber.await.unsafeRunSync() match {
        case Exit.Success(0) => ()
        case other => fail(s"Expected the timeout fallback (0), got: $other")
      }
    }
  }
}
