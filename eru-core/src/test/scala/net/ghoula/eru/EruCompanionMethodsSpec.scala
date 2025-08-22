package net.ghoula.eru

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.util.{Failure, Success, Try}

import net.ghoula.eru.CorePrelude.*

/** Comprehensive testing specification for Eru companion object methods.
  *
  * This specification ensures complete coverage of all Eru companion methods including blocking,
  * fromEither, fromTry, fromOption, and unit, providing both example-based and property-based
  * testing to achieve EXCELLENT status for all methods.
  */
class EruCompanionMethodsSpec extends ScalaCheckSuite {

  /** Generator for small positive integers to control test complexity. */
  private val smallInts: Gen[Int] = Gen.choose(-100, 100)

  /** Generator for error strings representing typed failures. */
  private val errorStrings: Gen[String] =
    Gen.oneOf("error1", "error2", "network failure", "timeout", "validation error")

  /** Generator for Either values (success or failure). */
  private val arbitraryEithers: Gen[Either[String, Int]] =
    Gen.oneOf(
      smallInts.map(Right(_)),
      errorStrings.map(Left(_))
    )

  /** Generator for Try values (success or failure). */
  private val arbitraryTries: Gen[Try[Int]] =
    Gen.oneOf(
      smallInts.map(Success(_)),
      errorStrings.map(error => Failure(new RuntimeException(error)))
    )

  /** Generator for Option values (some or none). */
  private val arbitraryOptions: Gen[Option[Int]] =
    Gen.oneOf(
      smallInts.map(Some(_)),
      Gen.const(None)
    )

  // ===== BLOCKING METHOD TESTS =====

  test("Eru.blocking executes computation lazily") {
    var executed = false
    val eru = Eru.blocking {
      executed = true
      42
    }

    assert(!executed, "Blocking computation should not execute immediately")

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(executed, "Blocking computation should execute when run")
  }

  test("Eru.blocking captures exceptions as typed errors") {
    val exception = new RuntimeException("blocking error")
    val eru = Eru.blocking {
      throw exception
    }

    val result = eru.attempt.unsafeRunSync()
    result match {
      case Result.Failure(caught) => assertEquals(caught, exception)
      case Result.Success(_) => fail("Should have captured exception")
    }
  }

  test("Eru.blocking handles side effects correctly") {
    var counter = 0
    val eru = Eru.blocking {
      counter += 1
      counter
    }

    // Multiple runs should execute the side effect each time
    assertEquals(eru.unsafeRunSync(), 1)
    assertEquals(eru.unsafeRunSync(), 2)
    assertEquals(eru.unsafeRunSync(), 3)
  }

  property("Eru.blocking preserves successful computations") {
    forAll(smallInts) { value =>
      val eru = Eru.blocking(value)
      eru.unsafeRunSync() == value
    }
  }

  property("Eru.blocking is equivalent to Eru.effect for pure computations") {
    forAll(smallInts) { value =>
      val blocking = Eru.blocking(value).attempt.unsafeRunSync()
      val effect = Eru.effect(value).attempt.unsafeRunSync()
      blocking == effect
    }
  }

  // ===== FROM_EITHER METHOD TESTS =====

  test("Eru.fromEither converts Right values to Success") {
    val right = Right(42)
    val eru = Eru.fromEither(right)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.fromEither converts Left values to Failure") {
    val left = Left("error")
    val eru = Eru.fromEither(left)

    interceptMessage[EruException[String]]("error") {
      eru.unsafeRunSync()
    }
  }

  test("Eru.fromEither preserves error and success types") {
    val stringError: Either[String, Int] = Left("string error")
    val intSuccess: Either[String, Int] = Right(123)

    val eruFromError: Eru[String, Int] = Eru.fromEither(stringError)
    val eruFromSuccess: Eru[String, Int] = Eru.fromEither(intSuccess)

    assertEquals(eruFromSuccess.unsafeRunSync(), 123)
    interceptMessage[EruException[String]]("string error") {
      eruFromError.unsafeRunSync()
    }
  }

  property("Eru.fromEither preserves Either semantics") {
    forAll(arbitraryEithers) { either =>
      val eru = Eru.fromEither(either)
      val result = eru.attempt.unsafeRunSync()

      either match {
        case Right(value) => result == Result.Success(value)
        case Left(error) => result == Result.Failure(error)
      }
    }
  }

  property("Eru.fromEither round-trip with attempt preserves values") {
    forAll(arbitraryEithers) { either =>
      val eru = Eru.fromEither(either)
      val backToResult = eru.attempt.unsafeRunSync()
      val backToEither = backToResult.fold(Left(_), Right(_))

      backToEither == either
    }
  }

  // ===== FROM_TRY METHOD TESTS =====

  test("Eru.fromTry converts Success values to Eru success") {
    val success = Success(42)
    val eru = Eru.fromTry(success)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.fromTry converts Failure values to Eru failure") {
    val exception = new RuntimeException("try error")
    val failure = Failure(exception)
    val eru = Eru.fromTry(failure)

    val result = eru.attempt.unsafeRunSync()
    result match {
      case Result.Failure(caught) => assertEquals(caught, exception)
      case Result.Success(_) => fail("Should have captured exception from Try.Failure")
    }
  }

  test("Eru.fromTry evaluates Try lazily") {
    var evaluated = false
    val eru = Eru.fromTry {
      evaluated = true
      Success(42)
    }

    assert(!evaluated, "Try should not be evaluated immediately")

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(evaluated, "Try should be evaluated when run")
  }

  property("Eru.fromTry preserves Try semantics") {
    forAll(arbitraryTries) { tryValue =>
      val eru = Eru.fromTry(tryValue)
      val result = eru.attempt.unsafeRunSync()

      tryValue match {
        case Success(value) => result == Result.Success(value)
        case Failure(exception) => result == Result.Failure(exception)
      }
    }
  }

  property("Eru.fromTry is equivalent to Eru.effect for pure computations") {
    forAll(smallInts) { value =>
      val fromTry = Eru.fromTry(Success(value)).attempt.unsafeRunSync()
      val effect = Eru.effect(value).attempt.unsafeRunSync()
      fromTry == effect
    }
  }

  // ===== FROM_OPTION METHOD TESTS =====

  test("Eru.fromOption converts Some values to Success") {
    val some = Some(42)
    val eru = Eru.fromOption(some, "not found")
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.fromOption converts None to Failure with provided error") {
    val none = None
    val error = "option was empty"
    val eru = Eru.fromOption(none, error)

    interceptMessage[EruException[String]]("option was empty") {
      eru.unsafeRunSync()
    }
  }

  test("Eru.fromOption evaluates option and error lazily") {
    var optionEvaluated = false
    var errorEvaluated = false

    val eru = Eru.fromOption(
      opt = {
        optionEvaluated = true
        Some(42)
      },
      onNone = {
        errorEvaluated = true
        "should not be evaluated"
      }
    )

    assert(!optionEvaluated, "Option should not be evaluated immediately")
    assert(!errorEvaluated, "Error should not be evaluated immediately")

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(optionEvaluated, "Option should be evaluated when run")
    assert(!errorEvaluated, "Error should not be evaluated for Some case")
  }

  test("Eru.fromOption evaluates error lazily only for None case") {
    var errorEvaluated = false

    val eru = Eru.fromOption(
      opt = None,
      onNone = {
        errorEvaluated = true
        "none error"
      }
    )

    interceptMessage[EruException[String]]("none error") {
      eru.unsafeRunSync()
    }

    assert(errorEvaluated, "Error should be evaluated for None case")
  }

  property("Eru.fromOption preserves Option semantics") {
    forAll(arbitraryOptions, errorStrings) { (option, error) =>
      val eru = Eru.fromOption(option, error)
      val result = eru.attempt.unsafeRunSync()

      option match {
        case Some(value) => result == Result.Success(value)
        case None => result == Result.Failure(error)
      }
    }
  }

  property("Eru.fromOption with Some is equivalent to Eru.succeed") {
    forAll(smallInts) { value =>
      val fromOption = Eru.fromOption(Some(value), "error").attempt.unsafeRunSync()
      val succeed = Eru.succeed(value).attempt.unsafeRunSync()
      fromOption == succeed
    }
  }

  // ===== UNIT METHOD TESTS =====

  test("Eru.unit contains Unit value") {
    val result = Eru.unit.unsafeRunSync()
    assertEquals(result, ())
  }

  test("Eru.unit is a successful effect") {
    val result = Eru.unit.attempt.unsafeRunSync()
    assertEquals(result, Result.Success(()))
  }

  test("Eru.unit can be composed with other effects") {
    val composed = for {
      _ <- Eru.unit
      value <- Eru.succeed(42)
      _ <- Eru.unit
    } yield value

    assertEquals(composed.unsafeRunSync(), 42)
  }

  test("Eru.unit is equivalent to Eru.succeed(())") {
    val unit = Eru.unit.attempt.unsafeRunSync()
    val succeed = Eru.succeed(()).attempt.unsafeRunSync()
    assertEquals(unit, succeed)
  }

  property("Eru.unit always succeeds with Unit") {
    forAll(Gen.const(())) { _ =>
      val result = Eru.unit.attempt.unsafeRunSync()
      result == Result.Success(())
    }
  }

  // ===== INTEGRATION TESTS =====

  test("All companion methods compose correctly in for-comprehension") {
    val either: Either[String, Int] = Right(10)
    val tryValue = Success(5)
    val option = Some(3)

    val composed = for {
      a <- Eru.fromEither(either)
      b <- Eru.fromTry(tryValue)
      c <- Eru.fromOption(option, "missing")
      d <- Eru.blocking(a + b + c)
      _ <- Eru.unit
    } yield d

    assertEquals(composed.unsafeRunSync(), 18)
  }

  test("All companion methods handle errors correctly in composition") {
    val either: Either[String, Int] = Left("either error")
    val tryValue = Success(5)
    val option = Some(3)

    val composed = for {
      a <- Eru.fromEither(either)
      b <- Eru.fromTry(tryValue)
      c <- Eru.fromOption(option, "missing")
      d <- Eru.blocking(a + b + c)
      _ <- Eru.unit
    } yield d

    interceptMessage[EruException[String]]("either error") {
      composed.unsafeRunSync()
    }
  }
}
