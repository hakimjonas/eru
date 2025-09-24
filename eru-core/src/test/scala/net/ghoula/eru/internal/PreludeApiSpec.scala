package net.ghoula.eru.internal

import net.ghoula.eru.CorePrelude.*

/** Test suite for internal PreludeApi object.
  *
  * Validates the unified facade that delegates to extensions for internal organization. Tests
  * ensure that all extension methods are properly delegated to internal.extensions and maintain API
  * stability even if internal organization changes.
  */
class PreludeApiSpec extends munit.FunSuite {

  test("internal PreludeApi delegates Result extension methods correctly") {
    import PreludeApi.*

    val result = Result.Success(42)

    // Test map delegation
    val mapped = result.map(_ * 2)
    assertEquals(mapped, Result.Success(84))

    // Test flatMap delegation
    val flatMapped = result.flatMap(x => Result.Success(x + 1))
    assertEquals(flatMapped, Result.Success(43))

    // Test fold delegation
    val folded = result.fold(
      ifFailure = _ => "error",
      ifSuccess = _.toString
    )
    assertEquals(folded, "42")

    // Test boolean queries
    assert(result.isSuccess)
    assert(!result.isFailure)

    // Test conversions
    val toEruResult = result.toEru
    val toExitResult = result.toExit
    assertEquals(toEruResult.unsafeRunSync(), 42)
    assertEquals(toExitResult, Exit.Success(42))
  }

  test("internal PreludeApi delegates Result failure cases") {
    import PreludeApi.*

    val result = Result.Failure("error")

    // Test map preserves failure
    val mapped = result.map((x: Int) => x * 2)
    assertEquals(mapped, Result.Failure("error"))

    // Test fold handles failure case
    val folded = result.fold(
      ifFailure = identity,
      ifSuccess = (x: Int) => x.toString
    )
    assertEquals(folded, "error")

    // Test boolean queries
    assert(!result.isSuccess)
    assert(result.isFailure)
  }

  test("internal PreludeApi delegates Eru resource extensions") {
    import PreludeApi.*

    var cleanupCalled = false

    // Test ensureAll delegation
    val effect = succeed(42).ensureAll(
      Eru.effect { cleanupCalled = true; () }
    )

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(cleanupCalled, "ensureAll should delegate properly")
  }

  test("internal PreludeApi delegates Eru resource management extensions") {
    import PreludeApi.*

    var acquired = false
    var released = false

    // Test autoCleanup delegation
    val resourceEffect = Eru.effect { acquired = true; "resource" }
      .autoCleanup(_ => Eru.effect { released = true; () })

    val result = resourceEffect.unsafeRunSync()
    assertEquals(result, "resource")
    assert(acquired, "Resource should have been acquired")
    assert(released, "autoCleanup should delegate properly")
  }

  test("internal PreludeApi delegates Eru scoped resource extensions") {
    import PreludeApi.*

    var acquired = false
    var used = false
    var cleaned = false

    val effect = Eru.effect { acquired = true; "resource" }.useScoped { resource =>
      used = true
      succeed(resource.length)
    } { _ =>
      Eru.effect { cleaned = true; () }
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 8)
    assert(acquired, "Resource should have been acquired")
    assert(used, "Resource should have been used")
    assert(cleaned, "useScoped should delegate properly")
  }

  test("internal PreludeApi delegates Eru resource validation extensions") {
    import PreludeApi.*

    val resource = succeed("valid-resource")

    // Test validateResource delegation
    val validated = resource.validateResource(_.startsWith("valid"), "valid prefix")
    val result = validated.unsafeRunSync()
    assertEquals(result, "valid-resource")

    // Test validation failure
    val invalidated = resource.validateResource(_.startsWith("invalid"), "invalid prefix")
    val exception = intercept[EruException[String | String]] {
      invalidated.unsafeRunSync()
    }
    assert(exception.error.toString.contains("Resource validation failed"))
  }

  test("internal PreludeApi delegates Eru error accumulation extensions") {
    import PreludeApi.*

    val effect1 = succeed(10)
    val effect2 = succeed(20)

    // Test accumulateErrors delegation for success case
    val combined = effect1.accumulateErrors(effect2)
    val result = combined.unsafeRunSync()
    assertEquals(result, (10, 20))

    // Test error accumulation
    val failEffect1: Eru[String, Int] = Eru.fail("error1")
    val failEffect2: Eru[String, Int] = Eru.fail("error2")
    val accumulated = failEffect1.accumulateErrors(failEffect2)

    val failure = intercept[EruException[?]] {
      accumulated.unsafeRunSync()
    }
    // Error accumulator should contain both errors
    val accumulator = failure.error
    assert(accumulator.toString.contains("error"))
  }

  test("internal PreludeApi delegates Eru validation extensions") {
    import PreludeApi.*

    val effect = succeed(42)

    // Test validate delegation with passing validations
    val validated = effect.validate(
      value => if (value > 0) succeed(()) else Eru.fail("negative"),
      value => if (value < 100) succeed(()) else Eru.fail("too large")
    )

    val result = validated.unsafeRunSync()
    assertEquals(result, 42)

    // Test validate delegation with failing validation
    val failValidated = effect.validate(value => if (value < 0) succeed(()) else Eru.fail("not negative"))

    val validationFailure = intercept[EruException[?]] {
      failValidated.unsafeRunSync()
    }
    val accumulator = validationFailure.error
    assert(accumulator.toString.contains("not negative"))
  }

  test("internal PreludeApi delegates Eru fallback extensions") {
    import PreludeApi.*

    val failingEffect: Eru[String, Int] = Eru.fail("recoverable")

    // Test fallback delegation
    val withFallback = failingEffect.fallback { case "recoverable" =>
      99
    }

    val result = withFallback.unsafeRunSync()
    assertEquals(result, 99)

    // Test fallback with non-matching error
    val noFallback = failingEffect.fallback { case "different" =>
      88
    }

    val exception = intercept[EruException[String]] {
      noFallback.unsafeRunSync()
    }
    assertEquals(exception.error, "recoverable")
  }

  test("internal PreludeApi delegates Eru error contextualization extensions") {
    import PreludeApi.*

    val failingEffect: Eru[String, Int] = Eru.fail("base error")

    // Test contextualizeError delegation
    val contextualized = failingEffect.contextualizeError(error => s"Context: $error")

    val exception = intercept[EruException[String]] {
      contextualized.unsafeRunSync()
    }
    assertEquals(exception.error, "Context: base error")
  }

  test("internal PreludeApi delegates Eru tracing extensions") {
    import PreludeApi.*

    val effect = succeed(42)

    // Test traced delegation (should not throw)
    val traced = effect.traced("test-operation", Map("key" -> "value"))
    val result = traced.unsafeRunSync()
    assertEquals(result, 42)

    // Test traceEvent delegation
    val withEvent = effect.traceEvent("milestone", Map("step" -> "1"))
    val eventResult = withEvent.unsafeRunSync()
    assertEquals(eventResult, 42)

    // Test withTraceBaggage delegation
    val withBaggage = effect.withTraceBaggage("requestId", "123")
    val baggageResult = withBaggage.unsafeRunSync()
    assertEquals(baggageResult, 42)
  }

  test("internal PreludeApi delegates Eru macro extensions") {
    import PreludeApi.*

    val effect = succeed(42)

    // Test validated delegation (compile-time, should return unchanged)
    val validated = effect.validated
    val result = validated.unsafeRunSync()
    assertEquals(result, 42)

    // Test optimize delegation (compile-time, should return optimized version)
    val optimized = effect.optimize
    val optimizedResult = optimized.unsafeRunSync()
    assertEquals(optimizedResult, 42)
  }

  test("internal PreludeApi maintains delegation stability") {
    import PreludeApi.*

    // Test that multiple imports work consistently
    val result1 = Result.Success(10)
    val result2 = Result.Success(20)

    val combined1 = result1.flatMap(a => result2.map(b => a + b))
    val combined2 = result1.flatMap(a => result2.map(b => a + b))

    assertEquals(combined1, combined2)
    assertEquals(combined1, Result.Success(30))
  }

  test("internal PreludeApi provides complete extension coverage") {
    import PreludeApi.*

    // Test Result extensions are available
    val result = Result.Success(42)
    assert(result.isSuccess)
    assertEquals(result.map(_ * 2), Result.Success(84))

    // Test Eru extensions are available
    val effect = succeed(42)
    assertEquals(effect.map(_ * 2).unsafeRunSync(), 84)

    // Test resource extensions are available
    var cleaned = false
    val withCleanup = effect.ensure(Eru.effect { cleaned = true; () })
    withCleanup.unsafeRunSync()
    assert(cleaned)
  }
}
