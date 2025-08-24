package net.ghoula.eru.patterns

import munit.FunSuite

import java.time.{Duration, Instant}

import net.ghoula.eru.CorePrelude.*

class ErrorHandlingSpec extends FunSuite {

  test("RetryPolicy.conditional retries on matching errors") {

    val policy = RetryPolicy.conditional[String](
      shouldRetryError = _.startsWith("retry"),
      maxAttempts = 3,
      baseDelay = Duration.ofMillis(10)
    )

    val context = RetryContext(Instant.now(), 0)

    assert(policy.shouldRetry("retry-error", AttemptCount(0), context))
    assert(policy.shouldRetry("retry-again", AttemptCount(1), context))
    assert(!policy.shouldRetry("fatal-error", AttemptCount(0), context))
    assert(!policy.shouldRetry("retry-error", AttemptCount(3), context)) // Max attempts reached
  }

  test("RetryPolicy.conditional calculates exponential backoff") {

    val policy = RetryPolicy.conditional[String](
      _ => true,
      maxAttempts = 5,
      baseDelay = Duration.ofMillis(100),
      maxDelay = Duration.ofSeconds(1)
    )

    assertEquals(policy.delayFor(AttemptCount(0)), Duration.ofMillis(100))
    assertEquals(policy.delayFor(AttemptCount(1)), Duration.ofMillis(200))
    assertEquals(policy.delayFor(AttemptCount(2)), Duration.ofMillis(400))
    assertEquals(policy.delayFor(AttemptCount(3)), Duration.ofMillis(800))

    // Should cap at maxDelay
    val longDelay = policy.delayFor(AttemptCount(10))
    assert(longDelay.toMillis <= 1000)
  }

  test("RetryPolicy.jitteredExponential adds jitter") {

    val policy = RetryPolicy.jitteredExponential(
      maxAttempts = 3,
      baseDelay = Duration.ofMillis(100),
      jitterFactor = 0.1
    )

    val delay1 = policy.delayFor(AttemptCount(1))
    val _ = policy.delayFor(AttemptCount(1)) // Second delay for jitter comparison

    // With jitter, delays should vary slightly
    val baseExpected = 200L // 100 * 2^1
    assert(delay1.toMillis >= (baseExpected * 0.9).toLong)
    assert(delay1.toMillis <= (baseExpected * 1.1).toLong)
  }

  test("RetryPolicy.timeBasedCircuitBreaker respects time limits") {

    val timeLimit = Duration.ofSeconds(1)
    val policy = RetryPolicy.timeBasedCircuitBreaker(timeLimit, Duration.ofMillis(100))

    val recentContext = RetryContext(Instant.now().minusMillis(500), 10)
    val oldContext = RetryContext(Instant.now().minusSeconds(2), 10)

    assert(policy.shouldRetry("error", AttemptCount(10), recentContext)) // Within time limit
    assert(!policy.shouldRetry("error", AttemptCount(10), oldContext)) // Exceeded time limit
  }

  test("RetryContext tracks errors and elapsed time") {

    val startTime = Instant.now().minusSeconds(5)
    val context = RetryContext(startTime, 3)
      .withError("error1")
      .withError("error2")
      .withError("error3")

    assertEquals(context.totalAttempts, 3)
    assertEquals(context.lastErrors.size, 3)
    assert(context.lastErrors.contains("error1"))
    assert(context.lastErrors.contains("error2"))
    assert(context.lastErrors.contains("error3"))

    val elapsed = context.elapsedTime
    assert(elapsed.getSeconds >= 5)
  }

  test("CircuitBreaker starts in Closed state") {

    val breaker = new CircuitBreaker(
      failureThreshold = FailureThreshold(3),
      recoveryTimeout = Duration.ofSeconds(1),
      successThreshold = 1
    )

    assertEquals(breaker.currentState, CircuitState.Closed)
  }

  test("CircuitBreaker opens after threshold failures") {

    val breaker = new CircuitBreaker(
      failureThreshold = FailureThreshold(2),
      recoveryTimeout = Duration.ofSeconds(1),
      successThreshold = 1
    )

    // First failure
    val _ = breaker.protect(Eru.fail("error1")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Closed)

    // Second failure - should open circuit
    val _ = breaker.protect(Eru.fail("error2")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Open)
  }

  test("CircuitBreaker fast-fails when open") {

    val breaker = new CircuitBreaker(
      failureThreshold = FailureThreshold(1),
      recoveryTimeout = Duration.ofSeconds(10), // Long timeout
      successThreshold = 1
    )

    // Cause failure to open circuit
    breaker.protect(Eru.fail("error")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Open)

    // Should fast-fail
    val result = breaker.protect(Eru.succeed(42)).attempt.unsafeRunSync()
    result match {
      case Result.Failure(CircuitBreakerOpen(_)) => () // Expected
      case other => fail(s"Expected CircuitBreakerOpen, got $other")
    }
  }

  test("CircuitBreaker resets on success in closed state") {

    val breaker = new CircuitBreaker(
      failureThreshold = FailureThreshold(3),
      recoveryTimeout = Duration.ofSeconds(1),
      successThreshold = 1
    )

    // One failure
    breaker.protect(Eru.fail("error")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Closed)

    // Success should reset failure count
    breaker.protect(Eru.succeed(42)).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Closed)

    // Should take 3 more failures to open (not 2)
    breaker.protect(Eru.fail("error1")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Closed)
    breaker.protect(Eru.fail("error2")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Closed)
    breaker.protect(Eru.fail("error3")).attempt.unsafeRunSync()
    assertEquals(breaker.currentState, CircuitState.Open)
  }

  test("ErrorAccumulator accumulates errors") {

    val acc1 = ErrorAccumulator.empty[String]
    assert(acc1.errors.isEmpty)
    assert(!acc1.nonEmpty)
    assert(acc1.headOption.isEmpty)

    val acc2 = acc1.add("error1", AttemptCount(1)).add("error2", AttemptCount(2))
    assertEquals(acc2.errors.size, 2)
    assert(acc2.nonEmpty)
    assertEquals(acc2.headOption, Some("error1")) // First error added

    val allErrors = acc2.allErrors
    assertEquals(allErrors, List("error1", "error2")) // Chronological order
  }

  test("ErrorAccumulator combines with other accumulators") {

    val acc1 = ErrorAccumulator.empty[String].add("error1", AttemptCount(1)).add("error2", AttemptCount(2))
    val acc2 = ErrorAccumulator.empty[String].add("error3", AttemptCount(3)).add("error4", AttemptCount(4))

    val combined = acc1.combine(acc2)
    assertEquals(combined.errors.size, 4)
    assertEquals(combined.allErrors, List("error3", "error4", "error1", "error2"))
  }

  test("withCircuitBreaker extension method works") {

    val breaker = new CircuitBreaker(
      failureThreshold = FailureThreshold(2),
      recoveryTimeout = Duration.ofSeconds(1),
      successThreshold = 1
    )

    val result1 = Eru.succeed(42).withCircuitBreaker(breaker).unsafeRunSync()
    assertEquals(result1, 42)
    assertEquals(breaker.currentState, CircuitState.Closed)
  }

  test("accumulateErrors extension method combines successes") {
    val effect1 = Eru.succeed(10)
    val effect2 = Eru.succeed(20)

    val result = effect1.accumulateErrors(effect2).unsafeRunSync()
    assertEquals(result, (10, 20))
  }

  test("accumulateErrors extension method accumulates single error") {

    val effect1 = Eru.fail("error1")
    val effect2 = Eru.succeed(20)

    val exception = intercept[EruException[ErrorAccumulator[String]]] {
      effect1.accumulateErrors(effect2).unsafeRunSync()
    }
    assertEquals(exception.error.allErrors, List("error1"))
  }

  test("accumulateErrors extension method accumulates multiple errors") {

    val effect1 = Eru.fail("error1")
    val effect2 = Eru.fail("error2")

    val exception = intercept[EruException[ErrorAccumulator[String]]] {
      effect1.accumulateErrors(effect2).unsafeRunSync()
    }
    assertEquals(exception.error.allErrors, List("error1", "error2"))
  }

  test("validate extension method passes all validations") {
    val effect = Eru.succeed(10)
    val validation1 = (x: Int) => if (x > 0) Eru.unit else Eru.fail("not positive")
    val validation2 = (x: Int) => if (x < 100) Eru.unit else Eru.fail("too large")

    val result = effect.validate(validation1, validation2).unsafeRunSync()
    assertEquals(result, 10)
  }

  test("validate extension method accumulates validation failures") {

    val effect = Eru.succeed(-5)
    val validation1 = (x: Int) => if (x > 0) Eru.unit else Eru.fail("not positive")
    val validation2 = (x: Int) => if (x % 2 == 0) Eru.unit else Eru.fail("not even")

    val exception = intercept[EruException[ErrorAccumulator[String]]] {
      effect.validate(validation1, validation2).unsafeRunSync()
    }

    val errors = exception.error.allErrors
    assert(errors.contains("not positive"))
    assert(errors.contains("not even"))
  }

  test("fallback extension method provides fallback values") {
    val effect = Eru.fail("not found")
    val fallbacks: PartialFunction[String, String] = {
      case "not found" => "default value"
      case "timeout" => "cached value"
    }

    val result = effect.fallback(fallbacks).unsafeRunSync()
    assertEquals(result, "default value")
  }

  test("fallback extension method preserves unhandled errors") {
    val effect = Eru.fail("fatal error")
    val fallbacks: PartialFunction[String, String] = { case "not found" =>
      "default value"
    }

    val exception = intercept[EruException[String]] {
      effect.fallback(fallbacks).unsafeRunSync()
    }
    assertEquals(exception.error, "fatal error")
  }

  test("contextualizeError extension method adds context to errors") {
    val effect = Eru.fail("original error")
    val contextualizer = (error: String) => s"Context: $error"

    val exception = intercept[EruException[String]] {
      effect.contextualizeError(contextualizer).unsafeRunSync()
    }
    assertEquals(exception.error, "Context: original error")
  }

  test("failAfter extension method placeholder works") {
    val effect = Eru.succeed(42)
    val result = effect.failAfter(Duration.ofSeconds(1), "timeout").unsafeRunSync()
    assertEquals(result, 42) // Should work as placeholder
  }

  test("error handling extensions compose with other extensions") {

    val breaker = new CircuitBreaker(FailureThreshold(2), Duration.ofSeconds(1), 1)
    val _ = RetryPolicy.conditional[String](_.startsWith("retry"), 3, Duration.ofMillis(1))

    val effect = Eru
      .succeed("resource")
      .autoCleanup(_ => Eru.unit)
      .withCircuitBreaker(breaker)
      .fallback { case CircuitBreakerOpen(_) =>
        "circuit-open-fallback"
      }

    val result = effect.unsafeRunSync()
    assertEquals(result, "resource")
  }
}
