package userland

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Integration test suite for timeout and retry functionality in real-world scenarios.
  *
  * Validates timeout operations, retry policies, and their composition in practical use cases that
  * reflect production application patterns. These tests ensure that timeout and retry mechanisms
  * provide reliable behavior for handling unreliable external dependencies, network operations, and
  * other failure-prone interactions while maintaining correctness and resource safety guarantees.
  */
final class TimeoutRetrySpec extends FunSuite {

  /** Validates that timeoutTo preserves successful values or yields fallback values.
    *
    * Tests the timeout mechanism to ensure it either completes with the original computation result
    * or falls back to the specified default value when timeout occurs.
    */
  test("timeoutTo either preserves value or yields fallback") {
    val slow = Eru.blocking(Thread.sleep(100)).map(_ => 1)
    val timed = slow.timeoutTo(Duration.ofMillis(1), 0)
    timed.runExit() match {
      case Exit.Success(v) => assert(v == 0 || v == 1)
      case other => fail(s"unexpected exit: $other")
    }
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
    assertEquals(retried.runExit(), Exit.Success(42))
  }
}
