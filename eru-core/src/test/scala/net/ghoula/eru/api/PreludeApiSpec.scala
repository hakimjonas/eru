package net.ghoula.eru.api

import net.ghoula.eru.CorePrelude.*

/** Test suite for PreludeApi object.
  *
  * Validates the public facade that re-exports extension methods for the Eru core API. Tests ensure
  * that all extension methods are properly exported and accessible through the PreludeApi
  * interface, providing users with a clean import path for core functionality.
  */
class PreludeApiSpec extends munit.FunSuite {

  test("PreludeApi exports Result extension methods") {
    import PreludeApi.*

    val result = Result.Success(42)

    // Test map extension method
    val mapped = result.map(_ * 2)
    assertEquals(mapped, Result.Success(84))

    // Test flatMap extension method
    val flatMapped = result.flatMap(x => Result.Success(x + 1))
    assertEquals(flatMapped, Result.Success(43))

    // Test fold extension method
    val folded = result.fold(
      ifFailure = _ => "error",
      ifSuccess = _.toString
    )
    assertEquals(folded, "42")

    // Test isSuccess extension method
    assert(result.isSuccess)
    assert(!result.isFailure)

    // Test conversion methods
    val toEruResult = result.toEru
    val toExitResult = result.toExit
    assertEquals(toEruResult.unsafeRunSync(), 42)
    assertEquals(toExitResult, Exit.Success(42))
  }

  test("PreludeApi exports Result extension methods for failures") {
    import PreludeApi.*

    val result = Result.Failure("error")

    // Test map preserves failure
    val mapped = result.map((x: Int) => x * 2)
    assertEquals(mapped, Result.Failure("error"))

    // Test flatMap preserves failure
    val flatMapped = result.flatMap((x: Int) => Result.Success(x + 1))
    assertEquals(flatMapped, Result.Failure("error"))

    // Test fold handles failure
    val folded = result.fold(
      ifFailure = identity,
      ifSuccess = (x: Int) => x.toString
    )
    assertEquals(folded, "error")

    // Test isFailure extension method
    assert(result.isFailure)
    assert(!result.isSuccess)
  }

  test("PreludeApi exports Eru resource safety extensions") {
    var cleanupCalled = false
    val resource = succeed(42)

    // Test ensure extension method (available from CorePrelude)
    val withCleanup = resource.ensure(Eru.effect { cleanupCalled = true; () })
    val result = withCleanup.unsafeRunSync()
    assertEquals(result, 42)
    assert(cleanupCalled, "Cleanup should have been called")
  }

  test("PreludeApi exports Eru error handling extensions") {
    val failingEffect: Eru[String, Int] = Eru.fail("error")

    // Test recover extension method (available from CorePrelude)
    val recovered = failingEffect.recover {
      case "error" => 42
      case _ => 0
    }
    assertEquals(recovered.unsafeRunSync(), 42)

    // Test recoverWith extension method (available from CorePrelude)
    val recoveredWith = failingEffect.recoverWith {
      case "error" => succeed(99)
      case _ => succeed(0)
    }
    assertEquals(recoveredWith.unsafeRunSync(), 99)

    // Test mapError extension method (available from CorePrelude)
    val mappedError = failingEffect.mapError(e => s"Mapped: $e")
    val exception = intercept[EruException[String]] {
      mappedError.unsafeRunSync()
    }
    assertEquals(exception.error, "Mapped: error")
  }

  test("PreludeApi exports Eru debugging extensions") {
    val effect = succeed(42)

    // Test debug extension method (available from CorePrelude)
    val debugged = effect.debug("test step")
    val result = debugged.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("PreludeApi exports Eru bracket extension method") {
    var acquired = false
    var released = false

    val effect = Eru.effect { acquired = true; "resource" }
      .bracket(_ => Eru.effect { released = true; () }) { resource =>
        succeed(resource.length)
      }

    val result = effect.unsafeRunSync()
    assertEquals(result, 8)
    assert(acquired, "Resource should have been acquired")
    assert(released, "Resource should have been released")
  }

  test("PreludeApi exports Eru attempt extension method") {
    val successEffect = succeed(42)
    val successResult = successEffect.attempt.unsafeRunSync()
    assertEquals(successResult, Result.Success(42))

    val failEffect: Eru[String, Int] = Eru.fail("error")
    val failResult = failEffect.attempt.unsafeRunSync()
    assertEquals(failResult, Result.Failure("error"))
  }

  test("PreludeApi exports additional Result convenience methods") {
    import PreludeApi.*

    // Test with complex Result transformations using exported extensions
    val result1 = Result.Success(10)
    val result2 = Result.Success(20)

    val combined = result1.flatMap(a => result2.map(b => a + b))
    assertEquals(combined, Result.Success(30))

    // Test error propagation using exported extensions
    val errorResult = Result.Failure("first error")
    val chainedResult = errorResult.flatMap((x: Int) => Result.Success(x * 2)).map(x => x + 1)

    assertEquals(chainedResult, Result.Failure("first error"))
  }

  test("PreludeApi exports advanced Eru extensions") {
    import PreludeApi.*

    // Test ensureAll extension method from exported extensions
    var cleanup1Called = false
    var cleanup2Called = false

    val effect = succeed(42).ensureAll(
      Eru.effect { cleanup1Called = true; () },
      Eru.effect { cleanup2Called = true; () }
    )

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(cleanup1Called, "First cleanup should have been called")
    assert(cleanup2Called, "Second cleanup should have been called")
  }

  test("PreludeApi provides clean import path") {
    val effect = for {
      result1 <- succeed(10).debug("first step")
      result2 <- succeed(20).debug("second step")
      combined = result1 + result2
      _ <- Eru.effect { () }.debug("cleanup step")
    } yield combined

    val finalResult = effect.unsafeRunSync()
    assertEquals(finalResult, 30)
  }

  test("PreludeApi maintains referential transparency") {
    val baseEffect = succeed(42)
    val effect1 = baseEffect.map(_ * 2)
    val effect2 = baseEffect.map(_ * 2)

    // Both should produce the same result
    assertEquals(effect1.unsafeRunSync(), effect2.unsafeRunSync())
    assertEquals(effect1.unsafeRunSync(), 84)
  }
}
