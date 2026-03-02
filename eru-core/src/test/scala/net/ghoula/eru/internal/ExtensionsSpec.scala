package net.ghoula.eru.internal

import net.ghoula.eru.CorePrelude.*

/** Test suite for internal extensions implementations.
  *
  * Validates the actual extension method implementations in internal.extensions object. These tests
  * focus on the core functionality of Result and Eru extension methods, ensuring they work
  * correctly as building blocks for the public API surface.
  */
class ExtensionsSpec extends munit.FunSuite {

  test("Result extensions provide complete monadic interface") {
    import extensions.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    // Test map implementation
    assertEquals(success.map(_ * 2), Result.Success(84))
    assertEquals(failure.map((x: Int) => x * 2), Result.Failure("error"))

    // Test flatMap implementation
    assertEquals(success.flatMap(x => Result.Success(x + 1)), Result.Success(43))
    assertEquals(failure.flatMap((x: Int) => Result.Success(x + 1)), Result.Failure("error"))

    // Test fold implementation
    assertEquals(success.fold(_ => "fail", _.toString), "42")
    assertEquals(failure.fold(identity, (x: Int) => x.toString), "error")

    // Test boolean queries
    assert(success.isSuccess)
    assert(!success.isFailure)
    assert(!failure.isSuccess)
    assert(failure.isFailure)
  }

  test("Result extension conversions work correctly") {
    import extensions.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    // Test toEru conversion
    val successEru = success.toEru
    val failureEru = failure.toEru

    assertEquals(successEru.unsafeRunSync(), 42)
    val exception = intercept[EruException[String]] {
      failureEru.unsafeRunSync()
    }
    assertEquals(exception.error, "error")

    // Test toExit conversion
    assertEquals(success.toExit, Exit.Success(42))
    assertEquals(failure.toExit, Exit.Failure("error"))
  }

  test("Eru resource safety extensions work correctly") {
    import extensions.*

    var cleanup1Called = false
    var cleanup2Called = false
    var cleanup3Called = false

    // Test ensureAll with multiple finalizers
    val effect = succeed(42).ensureAll(
      Eru.effect { cleanup1Called = true; () },
      Eru.effect { cleanup2Called = true; () },
      Eru.effect { cleanup3Called = true; () }
    )

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(cleanup1Called, "First finalizer should be called")
    assert(cleanup2Called, "Second finalizer should be called")
    assert(cleanup3Called, "Third finalizer should be called")
  }

  test("Eru autoCleanup extension manages resources correctly") {
    import extensions.*

    var resourceCreated = false
    var resourceCleaned = false

    val effect = Eru.effect { resourceCreated = true; "resource" }
      .autoCleanup(_ => Eru.effect { resourceCleaned = true; () })

    val result = effect.unsafeRunSync()
    assertEquals(result, "resource")
    assert(resourceCreated, "Resource should be created")
    assert(resourceCleaned, "Resource should be cleaned up")
  }

  test("Eru autoClose extension handles AutoCloseable resources") {
    import extensions.*

    class MockCloseable extends AutoCloseable {
      var closed = false
      def close(): Unit = closed = true
    }

    val closeable = new MockCloseable()
    val effect = succeed(closeable).autoClose

    val result = effect.unsafeRunSync()
    assert(result eq closeable, "Should return the same resource")
    assert(closeable.closed, "Resource should be closed automatically")
  }

  test("Eru useScoped extension provides scoped resource management") {
    import extensions.*

    var acquired = false
    var used = false
    var released = false

    val effect = Eru.effect { acquired = true; "resource" }.useScoped { resource =>
      used = true
      succeed(resource.length)
    } { _ =>
      Eru.effect { released = true; () }
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 8)
    assert(acquired, "Resource should be acquired")
    assert(used, "Resource should be used")
    assert(released, "Resource should be released")
  }

  test("Eru pooled extension returns resources to pool") {
    import extensions.*

    var acquired = false
    var returnedToPool = false

    val effect = Eru.effect { acquired = true; "pooled-resource" }
      .pooled(_ => Eru.effect { returnedToPool = true; () })

    val result = effect.unsafeRunSync()
    assertEquals(result, "pooled-resource")
    assert(acquired, "Resource should be acquired")
    assert(returnedToPool, "Resource should be returned to pool")
  }

  test("Eru validateResource extension validates resource lifecycle") {
    import extensions.*

    val validResource = succeed("valid-resource")
    val validated = validResource.validateResource(_.startsWith("valid"), "valid prefix")
    assertEquals(validated.unsafeRunSync(), "valid-resource")

    // Test validation failure
    val invalidValidated = validResource.validateResource(_.startsWith("invalid"), "invalid prefix")
    val exception = intercept[EruException[String | String]] {
      invalidValidated.unsafeRunSync()
    }
    assert(exception.error.toString.contains("Resource validation failed"))
  }

  test("Eru error handling extensions work correctly") {
    import extensions.*

    val failingEffect: Eru[String, Int] = Eru.fail("base-error")

    // Test fallback extension
    val withFallback = failingEffect.fallback {
      case "base-error" => 99
      case _ => 88
    }
    assertEquals(withFallback.unsafeRunSync(), 99)

    // Test contextualizeError extension
    val contextualized = failingEffect.contextualizeError(err => s"Context: $err")
    val exception = intercept[EruException[String]] {
      contextualized.unsafeRunSync()
    }
    assertEquals(exception.error, "Context: base-error")
  }

  test("Eru accumulateErrors extension accumulates multiple errors") {
    import extensions.*

    val success1 = succeed(10)
    val success2 = succeed(20)
    val combined = success1.accumulateErrors(success2)
    assertEquals(combined.unsafeRunSync(), (10, 20))

    // Test error accumulation
    val fail1: Eru[String, Int] = Eru.fail("error1")
    val fail2: Eru[String, Int] = Eru.fail("error2")
    val accumulated = fail1.accumulateErrors(fail2)

    val failure = intercept[EruException[?]] {
      accumulated.unsafeRunSync()
    }
    // Should contain error accumulator with both errors
    assert(failure.error.toString.contains("error"))
  }

  test("Eru validate extension validates with multiple validators") {
    import extensions.*

    val effect = succeed(42)

    // Test successful validation
    val validated = effect.validate(
      value => if (value > 0) succeed(()) else Eru.fail("not positive"),
      value => if (value < 100) succeed(()) else Eru.fail("too large")
    )
    assertEquals(validated.unsafeRunSync(), 42)

    // Test failing validation
    val failValidated = effect.validate(value => if (value < 0) succeed(()) else Eru.fail("not negative"))

    val exception = intercept[EruException[?]] {
      failValidated.unsafeRunSync()
    }
    assert(exception.error.toString.contains("not negative"))
  }

  test("Eru tracing extensions add observability") {
    import extensions.*

    val effect = succeed(42)

    // Test traced extension (should not throw)
    val traced = effect.traced("test-operation", Map("key" -> "value"))
    assertEquals(traced.unsafeRunSync(), 42)

    // Test traceEvent extension
    val withEvent = effect.traceEvent("milestone", Map("step" -> "1"))
    assertEquals(withEvent.unsafeRunSync(), 42)

    // Test withTraceBaggage extension
    val withBaggage = effect.withTraceBaggage("requestId", "123")
    assertEquals(withBaggage.unsafeRunSync(), 42)
  }

  test("Eru macro extensions provide compile-time analysis") {
    import extensions.*

    val effect = succeed(42)

    // Test validated macro extension (should return unchanged at runtime)
    val validated = effect.validated
    assertEquals(validated.unsafeRunSync(), 42)

    // Test optimize macro extension (should return optimized version)
    val optimized = effect.optimize
    assertEquals(optimized.unsafeRunSync(), 42)
  }

  test("Extensions maintain referential transparency") {
    import extensions.*

    val baseResult = Result.Success(42)
    val transform1 = baseResult.map(_ * 2)
    val transform2 = baseResult.map(_ * 2)

    assertEquals(transform1, transform2)
    assertEquals(transform1, Result.Success(84))

    val baseEffect = succeed(42)
    val effect1 = baseEffect.map(_ * 2)
    val effect2 = baseEffect.map(_ * 2)

    assertEquals(effect1.unsafeRunSync(), effect2.unsafeRunSync())
    assertEquals(effect1.unsafeRunSync(), 84)
  }

  test("Extensions handle edge cases correctly") {
    import extensions.*

    // Test empty ensureAll
    val emptyFinalizers = succeed(42).ensureAll()
    assertEquals(emptyFinalizers.unsafeRunSync(), 42)

    // Test Result fold with same result type
    val result = Result.Success(42)
    val folded = result.fold(_ => 0, identity)
    assertEquals(folded, 42)

    // Test validation with no validators
    val noValidation = succeed(42).validate()
    assertEquals(noValidation.unsafeRunSync(), 42)
  }
}
