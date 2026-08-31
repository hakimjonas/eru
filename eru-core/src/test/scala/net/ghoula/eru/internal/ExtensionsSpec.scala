package net.ghoula.eru.internal

import net.ghoula.eru.CorePrelude.*

/** Test suite for internal extensions implementations.
  *
  * Validates the actual extension method implementations in internal.Extensions object. These tests
  * focus on the core functionality of Result and Eru extension methods, ensuring they work
  * correctly as building blocks for the public API surface.
  */
class ExtensionsSpec extends munit.FunSuite {

  test("Result extensions provide complete monadic interface") {
    import Extensions.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    assertEquals(success.map(_ * 2), Result.Success(84))
    assertEquals(failure.map((x: Int) => x * 2), Result.Failure("error"))

    assertEquals(success.flatMap(x => Result.Success(x + 1)), Result.Success(43))
    assertEquals(failure.flatMap((x: Int) => Result.Success(x + 1)), Result.Failure("error"))

    assertEquals(success.fold(_ => "fail", _.toString), "42")
    assertEquals(failure.fold(identity, (x: Int) => x.toString), "error")

    assert(success.isSuccess)
    assert(!success.isFailure)
    assert(!failure.isSuccess)
    assert(failure.isFailure)
  }

  test("Result extension conversions work correctly") {
    import Extensions.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    val successEru = success.toEru
    val failureEru = failure.toEru

    assertEquals(successEru.unsafeRunSync(), 42)
    val exception = intercept[EruException[String]] {
      failureEru.unsafeRunSync()
    }
    assertEquals(exception.error, "error")

    assertEquals(success.toExit, Exit.Success(42))
    assertEquals(failure.toExit, Exit.Failure("error"))
  }

  test("Eru resource safety extensions work correctly") {
    import Extensions.*

    var cleanup1Called = false
    var cleanup2Called = false
    var cleanup3Called = false

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
    import Extensions.*

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
    import Extensions.*

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
    import Extensions.*

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
    import Extensions.*

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
    import Extensions.*

    val validResource = succeed("valid-resource")
    val validated = validResource.validateResource(_.startsWith("valid"), "valid prefix")
    assertEquals(validated.unsafeRunSync(), "valid-resource")

    val invalidValidated = validResource.validateResource(_.startsWith("invalid"), "invalid prefix")
    val exception = intercept[EruException[String | String]] {
      invalidValidated.unsafeRunSync()
    }
    assert(exception.error.toString.contains("Resource validation failed"))
  }

  test("Eru error handling extensions work correctly") {
    import Extensions.*

    val failingEffect: Eru[String, Int] = Eru.fail("base-error")

    val withFallback = failingEffect.fallback {
      case "base-error" => 99
      case _ => 88
    }
    assertEquals(withFallback.unsafeRunSync(), 99)

    val contextualized = failingEffect.contextualizeError(err => s"Context: $err")
    val exception = intercept[EruException[String]] {
      contextualized.unsafeRunSync()
    }
    assertEquals(exception.error, "Context: base-error")
  }

  test("Eru accumulateErrors extension accumulates multiple errors") {
    import Extensions.*

    val success1 = succeed(10)
    val success2 = succeed(20)
    val combined = success1.accumulateErrors(success2)
    assertEquals(combined.unsafeRunSync(), (10, 20))

    val fail1: Eru[String, Int] = Eru.fail("error1")
    val fail2: Eru[String, Int] = Eru.fail("error2")
    val accumulated = fail1.accumulateErrors(fail2)

    val failure = intercept[EruException[?]] {
      accumulated.unsafeRunSync()
    }
    assert(failure.error.toString.contains("error"))
  }

  test("Eru validate extension validates with multiple validators") {
    import Extensions.*

    val effect = succeed(42)

    val validated = effect.validate(
      value => if (value > 0) succeed(()) else Eru.fail("not positive"),
      value => if (value < 100) succeed(()) else Eru.fail("too large")
    )
    assertEquals(validated.unsafeRunSync(), 42)

    val failValidated = effect.validate(value => if (value < 0) succeed(()) else Eru.fail("not negative"))

    val exception = intercept[EruException[?]] {
      failValidated.unsafeRunSync()
    }
    assert(exception.error.toString.contains("not negative"))
  }

  test("Eru tracing extensions add observability") {
    import Extensions.*

    val effect = succeed(42)

    val traced = effect.traced("test-operation", Map("key" -> "value"))
    assertEquals(traced.unsafeRunSync(), 42)

    val withEvent = effect.traceEvent("milestone", Map("step" -> "1"))
    assertEquals(withEvent.unsafeRunSync(), 42)

    val withBaggage = effect.withTraceBaggage("requestId", "123")
    assertEquals(withBaggage.unsafeRunSync(), 42)
  }

  test("Eru macro extensions provide compile-time analysis") {
    import Extensions.*

    val effect = succeed(42)

    val validated = effect.validated
    assertEquals(validated.unsafeRunSync(), 42)

    val optimized = effect.optimize
    assertEquals(optimized.unsafeRunSync(), 42)
  }

  test("Extensions maintain referential transparency") {
    import Extensions.*

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
    import Extensions.*

    val emptyFinalizers = succeed(42).ensureAll()
    assertEquals(emptyFinalizers.unsafeRunSync(), 42)

    val result = Result.Success(42)
    val folded = result.fold(_ => 0, identity)
    assertEquals(folded, 42)

    val noValidation = succeed(42).validate()
    assertEquals(noValidation.unsafeRunSync(), 42)
  }
}
