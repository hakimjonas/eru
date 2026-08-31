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

    val mapped = result.map(_ * 2)
    assertEquals(mapped, Result.Success(84))

    val flatMapped = result.flatMap(x => Result.Success(x + 1))
    assertEquals(flatMapped, Result.Success(43))

    val folded = result.fold(
      ifFailure = _ => "error",
      ifSuccess = _.toString
    )
    assertEquals(folded, "42")

    assert(result.isSuccess)
    assert(!result.isFailure)

    val toEruResult = result.toEru
    val toExitResult = result.toExit
    assertEquals(toEruResult.unsafeRunSync(), 42)
    assertEquals(toExitResult, Exit.Success(42))
  }

  test("PreludeApi exports Result extension methods for failures") {
    import PreludeApi.*

    val result = Result.Failure("error")

    val mapped = result.map((x: Int) => x * 2)
    assertEquals(mapped, Result.Failure("error"))

    val flatMapped = result.flatMap((x: Int) => Result.Success(x + 1))
    assertEquals(flatMapped, Result.Failure("error"))

    val folded = result.fold(
      ifFailure = identity,
      ifSuccess = (x: Int) => x.toString
    )
    assertEquals(folded, "error")

    assert(result.isFailure)
    assert(!result.isSuccess)
  }

  test("PreludeApi exports Eru resource safety extensions") {
    var cleanupCalled = false
    val resource = succeed(42)

    val withCleanup = resource.ensure(Eru.effect { cleanupCalled = true; () })
    val result = withCleanup.unsafeRunSync()
    assertEquals(result, 42)
    assert(cleanupCalled, "Cleanup should have been called")
  }

  test("PreludeApi exports Eru error handling extensions") {
    val failingEffect: Eru[String, Int] = Eru.fail("error")

    val recovered = failingEffect.recover {
      case "error" => 42
      case _ => 0
    }
    assertEquals(recovered.unsafeRunSync(), 42)

    val recoveredWith = failingEffect.recoverWith {
      case "error" => succeed(99)
      case _ => succeed(0)
    }
    assertEquals(recoveredWith.unsafeRunSync(), 99)

    val mappedError = failingEffect.mapError(e => s"Mapped: $e")
    val exception = intercept[EruException[String]] {
      mappedError.unsafeRunSync()
    }
    assertEquals(exception.error, "Mapped: error")
  }

  test("PreludeApi exports Eru debugging extensions") {
    val effect = succeed(42)

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

    val result1 = Result.Success(10)
    val result2 = Result.Success(20)

    val combined = result1.flatMap(a => result2.map(b => a + b))
    assertEquals(combined, Result.Success(30))

    val errorResult = Result.Failure("first error")
    val chainedResult = errorResult.flatMap((x: Int) => Result.Success(x * 2)).map(x => x + 1)

    assertEquals(chainedResult, Result.Failure("first error"))
  }

  test("PreludeApi exports advanced Eru extensions") {
    import PreludeApi.*

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

}
