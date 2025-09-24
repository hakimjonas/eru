package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for CorePrelude exports and accessibility.
  *
  * Validates that all exported types, factory methods, and extension methods from CorePrelude are
  * correctly accessible and functional. CorePrelude serves as the main entry point for the Eru
  * effect system, providing a curated set of imports for common usage patterns.
  */
class CorePreludeSpec extends munit.FunSuite {

  test("CorePrelude exports Eru type and companion object") {
    // Type alias works
    val effect: Eru[String, Int] = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)

    // Companion object methods accessible
    val failed = Eru.fail("error")
    val exception = intercept[EruException[String]] {
      failed.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("CorePrelude exports Result type and companion object") {
    // Type alias works
    val success: Result[String, Int] = Result.Success(42)
    val failure: Result[String, Int] = Result.Failure("error")

    // Pattern matching works
    success match {
      case Result.Success(value) => assertEquals(value, 42)
      case Result.Failure(_) => fail("Expected Success")
    }

    failure match {
      case Result.Failure(error) => assertEquals(error, "error")
      case Result.Success(_) => fail("Expected Failure")
    }
  }

  test("CorePrelude exports Eru factory methods directly") {
    // Direct access to factory methods without Eru prefix
    val s = succeed(42)
    assertEquals(s.unsafeRunSync(), 42)

    val f = Eru.fail("error")
    val ex = intercept[EruException[String]] {
      f.unsafeRunSync()
    }
    assertEquals(ex.error, "error")

    val e = effect(21 * 2)
    assertEquals(e.unsafeRunSync(), 42)

    val b = blocking(10 + 32)
    assertEquals(b.unsafeRunSync(), 42)

    val either: Either[String, Int] = Right(42)
    val fromE = fromEither(either)
    assertEquals(fromE.unsafeRunSync(), 42)

    val fromOpt = fromOption(Some(42), "none")
    assertEquals(fromOpt.unsafeRunSync(), 42)

    val u = unit
    assertEquals(u.unsafeRunSync(), ())
  }

  test("CorePrelude exports Result factory methods with qualified names") {
    // Result factory methods exported with prefix to avoid conflicts
    val s = resultSucceed(42)
    assertEquals(s, Result.Success(42))

    val f = resultFail("error")
    assertEquals(f, Result.Failure("error"))
  }

  test("CorePrelude exports domain types") {
    // AttemptCount accessible
    val attempts = AttemptCount(3)
    // Opaque types don't have direct accessors
    val attemptsValue: AttemptCount = attempts
    assert(attemptsValue == AttemptCount(3))

    // JitterFactor accessible
    val jitter = JitterFactor(0.5)
    val jitterValue: JitterFactor = jitter
    assert(jitterValue == JitterFactor(0.5))

    // FailureThreshold accessible
    val threshold = FailureThreshold(10)
    val thresholdValue: FailureThreshold = threshold
    assert(thresholdValue == FailureThreshold(10))
  }

  test("CorePrelude exports EruException type and companion") {
    val error = "test error"
    val exception: EruException[String] = EruException(error)
    assertEquals(exception.error, error)
    assertEquals(exception.getMessage, "test error")
  }

  test("CorePrelude exports Exit types") {
    // Exit constructors accessible
    val success = Exit.Success(42)
    val _: Exit[String, Nothing] = Exit.Failure("error")
    val _: Exit[Nothing, Nothing] = Exit.Die(new RuntimeException("boom"))
    val _: Exit[Nothing, Nothing] = Exit.Interrupt(FiberId.fresh(), InterruptCause.Cancelled())

    // Pattern matching works
    success match {
      case Exit.Success(value) => assertEquals(value, 42)
      case _ => fail("Expected Success")
    }

    // InterruptCause variants accessible
    val cancelled = InterruptCause.Cancelled(Some("user request"))
    val _: InterruptCause = InterruptCause.Timeout(java.time.Duration.ofSeconds(30))
    val _: InterruptCause = InterruptCause.ResourceExhausted("memory")

    cancelled match {
      case InterruptCause.Cancelled(reason) => assertEquals(reason, Some("user request"))
      case _ => fail("Expected Cancelled")
    }
  }

  test("CorePrelude provides access to extension methods") {
    // Resource safety extensions should be available through PreludeApi
    val effect = succeed(42)

    // Ensure method available
    val withFinalizer = effect.ensure(succeed(()))
    assertEquals(withFinalizer.unsafeRunSync(), 42)

    // Bracket method available
    val resource = succeed("resource")
    val used = resource.bracket(_ => succeed(()))(res => succeed(res.length))
    assertEquals(used.unsafeRunSync(), 8)

    // Debug method available
    val debugged = effect.debug("test label")
    assertEquals(debugged.unsafeRunSync(), 42)
  }

  test("CorePrelude exports compose without conflicts") {
    // Multiple imports should work together
    val effect1: Eru[String, Int] = succeed(42)
    val result1: Result[String, Int] = resultSucceed(42)

    // Can convert Result to Eru using extension methods
    val eruFromResult = result1.toEru
    assertEquals(eruFromResult.unsafeRunSync(), 42)

    // Can chain operations
    val chained = effect1
      .map(_ * 2)
      .flatMap(x => succeed(x + 10))
      .recover { case _ => 999 }

    assertEquals(chained.unsafeRunSync(), 94)
  }

  test("CorePrelude exports work in for-comprehensions") {
    // All necessary implicits and types available
    val computation = for {
      x <- succeed(10)
      y <- effect(20)
      z <- fromEither(Right(12): Either[String, Int])
    } yield x + y + z

    assertEquals(computation.unsafeRunSync(), 42)
  }

  test("CorePrelude provides complete prelude for typical usage") {
    // Simulates typical user code using only CorePrelude import
    def businessLogic(): Eru[String | Throwable, Int] = {
      val result = for {
        value <- succeed(10)
        doubled <- effect(value * 2)
        validated <- if (doubled > 15) succeed(doubled) else fail("too small")
        finalValue <- fromOption(Some(validated * 2), "missing")
      } yield finalValue

      result.recover { case "too small" => 100 }
        .ensure(succeed(()))
    }

    assertEquals(businessLogic().unsafeRunSync(), 40)
  }

  test("CorePrelude error handling patterns are accessible") {
    // Should have access to error handling utilities through exports
    val _: AttemptCount = AttemptCount(3)
    val _: FailureThreshold = FailureThreshold(5)

    // Basic patterns work
    val effect = succeed(42).recover { case _ => 0 }
    assertEquals(effect.unsafeRunSync(), 42)
  }

  test("CorePrelude tracing functionality is accessible") {
    // Should have access to tracing types
    val spanId = SpanId.fresh()
    val _: TraceId = TraceId.fresh()

    // IDs should be unique
    val spanId2 = SpanId.fresh()
    assertNotEquals(spanId, spanId2)
  }
}
