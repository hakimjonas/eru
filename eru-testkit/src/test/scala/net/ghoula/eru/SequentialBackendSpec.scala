package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.TimeoutException

import net.ghoula.eru.internal.SharedSynchronousBackend
import net.ghoula.eru.prelude.*

/** Contract tests for the sequential fallback backend.
  *
  * The sequential backend cannot preempt a running effect (single-threaded by design), so its
  * timeouts cannot interrupt — but they must not lie either: a timeout over an effect that exceeds
  * its deadline reports `TimeoutException`, and a race must prefer the first effect while falling
  * back to the second when the first fails. These tests pin that honest contract; the previous
  * implementation silently ran the first effect to completion and ignored the deadline entirely.
  */
class SequentialBackendSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.withBackend(SharedSynchronousBackend)

  test("timeout reports TimeoutException when the effect exceeds the deadline") {
    val slow = runtime.sleep(Duration.ofMillis(200)).map(_ => 42)

    runtime.timeout(Duration.ofMillis(10))(slow).attempt.unsafeRunSync() match {
      case Result.Failure(_: TimeoutException) => ()
      case other => fail(s"Expected a TimeoutException, got: $other")
    }
  }

  test("timeout preserves fast successes") {
    val result = runtime.timeout(Duration.ofSeconds(1))(Eru.succeed(42)).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("timeout preserves typed failures") {
    runtime.timeout(Duration.ofSeconds(1))(Eru.fail("boom")).attempt.unsafeRunSync() match {
      case Result.Failure("boom") => ()
      case other => fail(s"Expected the typed failure, got: $other")
    }
  }

  test("race prefers the first effect when it succeeds") {
    val result = runtime.race(Eru.succeed(1), Eru.succeed(2)).unsafeRunSync()
    assertEquals(result, Left(1))
  }

  test("race falls back to the second effect when the first fails") {
    val result = runtime.race(Eru.effect(throw new RuntimeException("a")), Eru.succeed(2)).unsafeRunSync()
    assertEquals(result, Right(2))
  }

  test("race reports the second effect's failure when both fail") {
    runtime
      .race(Eru.effect(throw new RuntimeException("a")), Eru.effect(throw new RuntimeException("b")))
      .attempt
      .unsafeRunSync() match {
      case Result.Failure(e: RuntimeException) if e.getMessage == "b" => ()
      case other => fail(s"Expected the second effect's failure, got: $other")
    }
  }
}
