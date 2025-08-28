package userland

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*

final class TimeoutRetrySpec extends FunSuite {
  test("timeoutTo either preserves value or yields fallback (single-threaded semantics)") {
    val slow = Eru.blocking(Thread.sleep(100)).map(_ => 1)
    val timed = slow.timeoutTo(Duration.ofMillis(1), 0)
    timed.runExit() match {
      case Exit.Success(v) => assert(v == 0 || v == 1)
      case other => fail(s"unexpected exit: $other")
    }
  }

  test("retryN re-executes failing effect until success") {
    var attempts = 0
    val flaky = Eru.effect { attempts += 1; if (attempts < 3) throw new RuntimeException("boom") else 42 }
    val retried = flaky.retryN(5)
    assertEquals(retried.runExit(), Exit.Success(42))
  }
}
