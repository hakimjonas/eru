package net.ghoula.eru

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import net.ghoula.eru.CorePrelude.*

/** Comprehensive testing specification for advanced Eru methods.
  *
  * This specification provides extensive coverage for mapError, ensure, and bracket methods to
  * achieve EXCELLENT status through property-based testing, edge case coverage, and comprehensive
  * behavioral verification.
  */
class EruAdvancedMethodsSpec extends ScalaCheckSuite {

  /** Generator for small positive integers to control test complexity. */
  private val smallInts: Gen[Int] = Gen.choose(1, 100)

  /** Generator for error strings representing typed failures. */
  private val errorStrings: Gen[String] =
    Gen.oneOf("error1", "error2", "network failure", "timeout", "validation error")

  /** Generator for successful Eru effects with integer values. */
  private val successfulErus: Gen[Eru[String, Int]] =
    smallInts.map(Eru.succeed)

  /** Generator for failed Eru effects with string errors. */
  private val failedErus: Gen[Eru[String, Int]] =
    errorStrings.map(Eru.fail)

  /** Generator for arbitrary Eru effects (success or failure). */
  private val arbitraryErus: Gen[Eru[String, Int]] =
    Gen.oneOf(successfulErus, failedErus)

  /** Generator for error transformation functions. */
  private val errorMappers: Gen[String => String] = Gen.oneOf(
    (e: String) => e.toUpperCase,
    (e: String) => s"Mapped: $e",
    (e: String) => e.reverse,
    (e: String) => s"ERROR[$e]"
  )

  test("mapError transforms error values without affecting success") {
    val success: Eru[String, Int] = Eru.succeed(42)
    val mapped = success.mapError(_.toUpperCase)
    assertEquals(mapped.unsafeRunSync(), 42)
  }

  test("mapError transforms error values in failed computations") {
    val failure = Eru.fail("original error")
    val mapped = failure.mapError(_.toUpperCase)

    interceptMessage[EruException[String]]("ORIGINAL ERROR") {
      mapped.unsafeRunSync()
    }
  }

  test("mapError preserves success type while changing error type") {
    val stringError: Eru[String, Int] = Eru.fail("string error")
    val intError: Eru[Int, Int] = stringError.mapError(_.length)

    interceptMessage[EruException[Int]]("12") {
      intError.unsafeRunSync()
    }
  }

  test("mapError composes correctly with multiple transformations") {
    val failure = Eru.fail("error")
    val mapped = failure
      .mapError(_.toUpperCase)
      .mapError(s => s"[$s]")
      .mapError(_ + "!")

    interceptMessage[EruException[String]]("[ERROR]!") {
      mapped.unsafeRunSync()
    }
  }

  test("mapError with side effects executes only on failure") {
    var sideEffectCount = 0
    val mapper = (e: String) => {
      sideEffectCount += 1
      e.toUpperCase
    }

    Eru.succeed(42).mapError(mapper).unsafeRunSync()
    assertEquals(sideEffectCount, 0)

    try {
      Eru.fail("error").mapError(mapper).unsafeRunSync()
    } catch {
      case _: EruException[String] =>
    }
    assertEquals(sideEffectCount, 1)
  }

  property("mapError preserves success values unchanged") {
    forAll(successfulErus, errorMappers) { (successEru, mapper) =>
      val original = successEru.attempt.unsafeRunSync()
      val mapped = successEru.mapError(mapper).attempt.unsafeRunSync()
      original == mapped
    }
  }

  property("mapError transforms all error values") {
    forAll(failedErus, errorMappers) { (failedEru, mapper) =>
      val originalResult = failedEru.attempt.unsafeRunSync()
      val mappedResult = failedEru.mapError(mapper).attempt.unsafeRunSync()

      (originalResult, mappedResult) match {
        case (Result.Failure(originalError), Result.Failure(mappedError)) =>
          mappedError == mapper(originalError)
        case _ => false
      }
    }
  }

  property("mapError composition follows function composition laws") {
    forAll(failedErus, errorMappers, errorMappers) { (failedEru, f, g) =>
      val composed = failedEru.mapError(f.andThen(g)).attempt.unsafeRunSync()
      val sequential = failedEru.mapError(f).mapError(g).attempt.unsafeRunSync()
      composed == sequential
    }
  }

  test("ensure executes finalizer after successful computation") {
    var finalizerExecuted = false
    val eru = Eru.succeed(42).ensure {
      finalizerExecuted = true
      Eru.unit
    }

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(finalizerExecuted, "Finalizer should execute after success")
  }

  test("ensure executes finalizer after failed computation") {
    var finalizerExecuted = false
    val eru = Eru.fail("error").ensure {
      finalizerExecuted = true
      Eru.unit
    }

    try {
      eru.unsafeRunSync()
    } catch {
      case _: EruException[String] =>
    }
    assert(finalizerExecuted, "Finalizer should execute after failure")
  }

  test("ensure finalizer failure does not affect main computation result") {
    val eru = Eru.succeed(42).ensure {
      Eru.effect(throw new RuntimeException("finalizer error"))
    }

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("ensure with multiple finalizers executes all in reverse order") {
    val executionOrder = scala.collection.mutable.ListBuffer.empty[String]

    val eru = Eru
      .succeed(42)
      .ensure {
        executionOrder += "first"
        Eru.unit
      }
      .ensure {
        executionOrder += "second"
        Eru.unit
      }
      .ensure {
        executionOrder += "third"
        Eru.unit
      }

    eru.unsafeRunSync()
    assertEquals(executionOrder.toList, List("third", "second", "first"))
  }

  test("ensure with nested computations") {
    var outerFinalized = false
    var innerFinalized = false

    val nested = Eru.succeed(10).ensure {
      innerFinalized = true
      Eru.unit
    }

    val outer = nested.flatMap(x => Eru.succeed(x * 2)).ensure {
      outerFinalized = true
      Eru.unit
    }

    val result = outer.unsafeRunSync()
    assertEquals(result, 20)
    assert(innerFinalized, "Inner finalizer should execute")
    assert(outerFinalized, "Outer finalizer should execute")
  }

  property("ensure always executes finalizer regardless of success or failure") {
    forAll(arbitraryErus) { eru =>
      var finalizerExecuted = false
      val withFinalizer = eru.ensure {
        finalizerExecuted = true
        Eru.unit
      }

      try {
        withFinalizer.unsafeRunSync()
      } catch {
        case _: EruException[_] =>
      }

      finalizerExecuted
    }
  }

  property("ensure preserves original computation result") {
    forAll(arbitraryErus) { eru =>
      val original = eru.attempt.unsafeRunSync()
      val withFinalizer = eru.ensure(Eru.unit).attempt.unsafeRunSync()
      original == withFinalizer
    }
  }

  test("bracket executes use function with acquired resource") {
    val resource = "test-resource"
    val eru = Eru.succeed(resource)

    val result = eru.bracket(_ => Eru.unit) { res =>
      Eru.succeed(s"used-$res")
    }

    assertEquals(result.unsafeRunSync(), "used-test-resource")
  }

  test("bracket executes release function after use completes") {
    var resourceReleased = false
    val resource = "test-resource"

    val result = Eru
      .succeed(resource)
      .bracket { _ =>
        resourceReleased = true
        Eru.unit
      } { res =>
        Eru.succeed(s"used-$res")
      }

    val value = result.unsafeRunSync()
    assertEquals(value, "used-test-resource")
    assert(resourceReleased, "Resource should be released after use")
  }

  test("bracket executes release function even when use fails") {
    var resourceReleased = false
    val resource = "test-resource"

    val result = Eru
      .succeed(resource)
      .bracket { _ =>
        resourceReleased = true
        Eru.unit
      } { _ =>
        Eru.fail("use failed")
      }

    try {
      result.unsafeRunSync()
    } catch {
      case _: EruException[String] =>
    }
    assert(resourceReleased, "Resource should be released even when use fails")
  }

  test("bracket release failure does not mask use result") {
    val resource = "test-resource"

    val result = Eru
      .succeed(resource)
      .bracket { _ =>
        Eru.effect(throw new RuntimeException("release failed"))
      } { res =>
        Eru.succeed(s"used-$res")
      }

    val value = result.unsafeRunSync()
    assertEquals(value, "used-test-resource")
  }

  test("bracket with resource acquisition failure") {
    var releaseCalled = false
    var useCalled = false

    val result = Eru
      .fail("acquire failed")
      .bracket { _ =>
        releaseCalled = true
        Eru.unit
      } { _ =>
        useCalled = true
        Eru.succeed("should not reach")
      }

    try {
      result.unsafeRunSync()
    } catch {
      case _: EruException[String] =>
    }

    assert(!releaseCalled, "Release should not be called if acquire fails")
    assert(!useCalled, "Use should not be called if acquire fails")
  }

  test("bracket with complex resource patterns") {
    val acquired = scala.collection.mutable.ListBuffer.empty[String]
    val released = scala.collection.mutable.ListBuffer.empty[String]

    def acquireResource(name: String) = Eru.effect {
      acquired += name
      name
    }

    def releaseResource(name: String) = Eru.effect {
      released += name
      ()
    }

    val computation = for {
      r1 <- acquireResource("resource1")
      result <- Eru.succeed(r1).bracket(releaseResource) { r1 =>
        for {
          r2 <- acquireResource("resource2")
          finalResult <- Eru.succeed(r2).bracket(releaseResource) { r2 =>
            Eru.succeed(s"$r1-$r2")
          }
        } yield finalResult
      }
    } yield result

    val result = computation.unsafeRunSync()
    assertEquals(result, "resource1-resource2")
    assertEquals(acquired.toList, List("resource1", "resource2"))
    assertEquals(
      released.toList,
      List("resource1", "resource2")
    )
  }

  property("bracket always releases acquired resources") {
    forAll(smallInts) { resourceId =>
      var resourceReleased = false
      val resource = s"resource-$resourceId"

      val result = Eru
        .succeed(resource)
        .bracket { _ =>
          resourceReleased = true
          Eru.unit
        } { res =>
          if (resourceId % 2 == 0) Eru.succeed(res) else Eru.fail("use failed")
        }

      try {
        result.unsafeRunSync()
      } catch {
        case _: EruException[_] =>
      }

      resourceReleased
    }
  }

  property("bracket preserves use function result when successful") {
    forAll(smallInts) { value =>
      val resource = s"resource-$value"
      val expected = s"processed-$value"

      val result = Eru.succeed(resource).bracket(_ => Eru.unit) { res =>
        Eru.succeed(s"processed-${res.split("-")(1)}")
      }

      result.unsafeRunSync() == expected
    }
  }

  test("mapError, ensure, and bracket work together") {
    var finalizerExecuted = false
    var resourceReleased = false

    val computation = Eru
      .succeed("resource")
      .bracket { _ =>
        resourceReleased = true
        Eru.unit
      } { _ =>
        Eru
          .fail("processing failed")
          .mapError(error => s"Enhanced: $error")
      }
      .ensure {
        finalizerExecuted = true
        Eru.unit
      }

    try {
      computation.unsafeRunSync()
    } catch {
      case ex: EruException[_] =>
        ex.error match {
          case errorString: String => assertEquals(errorString, "Enhanced: processing failed")
          case _ => fail(s"Expected String error, but got: ${ex.error}")
        }
    }

    assert(resourceReleased, "Resource should be released")
    assert(finalizerExecuted, "Finalizer should execute")
  }
}
