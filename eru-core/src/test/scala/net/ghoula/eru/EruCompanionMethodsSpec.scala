package net.ghoula.eru

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
class EruCompanionMethodsSpec extends munit.ScalaCheckSuite {

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

  /** Validates that Eru.blocking executes computation lazily.
    *
    * Tests that blocking computations are not executed immediately upon construction, maintaining
    * lazy evaluation semantics.
    */
  test("Eru.blocking executes computation lazily") {
    var executed = false
    val eru = Eru.blocking {
      executed = true
      42
    }

    assert(!executed)

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(executed)
  }

  /** Validates that Eru.blocking captures exceptions as typed errors.
    *
    * Tests that exceptions thrown within blocking computations are properly captured and converted
    * to typed Eru failures.
    */
  test("Eru.blocking captures exceptions as typed errors") {
    val exception = new RuntimeException("blocking error")
    val eru = Eru.blocking {
      throw exception
    }

    val result = eru.attempt.unsafeRunSync()
    result match {
      case Result.Failure(caught) => assertEquals(caught, exception)
      case Result.Success(_) => fail("Expected exception to be captured")
    }
  }

  /** Validates that Eru.blocking handles side effects correctly.
    *
    * Tests that blocking effects properly execute side effects each time they are run, maintaining
    * referential transparency.
    */
  test("Eru.blocking handles side effects correctly") {
    var counter = 0
    val eru = Eru.blocking {
      counter += 1
      counter
    }

    assertEquals(eru.unsafeRunSync(), 1)
    assertEquals(eru.unsafeRunSync(), 2)
    assertEquals(eru.unsafeRunSync(), 3)
  }

  /** Property test validating that Eru.blocking preserves successful computations.
    *
    * Verifies that successful blocking computations produce the expected results across a wide
    * range of input values.
    */
  property("Eru.blocking preserves successful computations") {
    forAll(smallInts) { value =>
      val eru = Eru.blocking(value)
      eru.unsafeRunSync() == value
    }
  }

  /** Property test validating that Eru.blocking is equivalent to Eru.effect for pure computations.
    *
    * Verifies that blocking and effect constructors produce identical results for computations
    * without side effects.
    */
  property("Eru.blocking is equivalent to Eru.effect for pure computations") {
    forAll(smallInts) { value =>
      val blocking = Eru.blocking(value).attempt.unsafeRunSync()
      val effect = Eru.effect(value).attempt.unsafeRunSync()
      blocking == effect
    }
  }

  /** Validates that Eru.fromEither converts Right values to Success.
    *
    * Tests that Either Right values are properly converted to successful Eru effects with correct
    * value preservation.
    */
  test("Eru.fromEither converts Right values to Success") {
    val right = Right(42)
    val eru = Eru.fromEither(right)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  /** Validates that Eru.fromEither converts Left values to Failure.
    *
    * Tests that Either Left values are properly converted to failed Eru effects with correct error
    * preservation.
    */
  test("Eru.fromEither converts Left values to Failure") {
    val left = Left("error")
    val eru = Eru.fromEither(left)

    interceptMessage[EruException[String]]("error") {
      eru.unsafeRunSync()
    }
  }

  /** Validates that Eru.fromEither preserves error and success types.
    *
    * Tests that type information is correctly maintained when converting Either values to Eru
    * effects.
    */
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

  /** Property test validating that Eru.fromEither preserves Either semantics.
    *
    * Verifies that Either conversion maintains correct success/failure semantics across a wide
    * range of Either values.
    */
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

  /** Property test validating that Eru.fromEither round-trip with attempt preserves values.
    *
    * Verifies that converting Either to Eru and back via attempt produces the original Either
    * value.
    */
  property("Eru.fromEither round-trip with attempt preserves values") {
    forAll(arbitraryEithers) { either =>
      val eru = Eru.fromEither(either)
      val backToResult = eru.attempt.unsafeRunSync()
      val backToEither = backToResult.fold(Left(_), Right(_))

      backToEither == either
    }
  }

  /** Validates that Eru.fromTry converts Success values to Eru success.
    *
    * Tests that Try Success values are properly converted to successful Eru effects with correct
    * value preservation.
    */
  test("Eru.fromTry converts Success values to Eru success") {
    val success = Success(42)
    val eru = Eru.fromTry(success)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  /** Validates that Eru.fromTry converts Failure values to Eru failure.
    *
    * Tests that Try Failure values are properly converted to failed Eru effects with correct
    * exception preservation.
    */
  test("Eru.fromTry converts Failure values to Eru failure") {
    val exception = new RuntimeException("try error")
    val failure = Failure(exception)
    val eru = Eru.fromTry(failure)

    val result = eru.attempt.unsafeRunSync()
    result match {
      case Result.Failure(caught) => assertEquals(caught, exception)
      case Result.Success(_) => fail("Expected exception from Try.Failure")
    }
  }

  /** Validates that Eru.fromTry evaluates Try lazily.
    *
    * Tests that Try values are not evaluated immediately upon fromTry construction, maintaining
    * lazy evaluation semantics.
    */
  test("Eru.fromTry evaluates Try lazily") {
    var evaluated = false
    val eru = Eru.fromTry {
      evaluated = true
      Success(42)
    }

    assert(!evaluated)

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(evaluated)
  }

  /** Property test validating that Eru.fromTry preserves Try semantics.
    *
    * Verifies that Try conversion maintains correct success/failure semantics across a wide range
    * of Try values.
    */
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

  /** Property test validating that Eru.fromTry is equivalent to Eru.effect for pure computations.
    *
    * Verifies that fromTry and effect constructors produce identical results for successful
    * computations without side effects.
    */
  property("Eru.fromTry is equivalent to Eru.effect for pure computations") {
    forAll(smallInts) { value =>
      val fromTry = Eru.fromTry(Success(value)).attempt.unsafeRunSync()
      val effect = Eru.effect(value).attempt.unsafeRunSync()
      fromTry == effect
    }
  }

  /** Validates that Eru.fromOption converts Some values to Success.
    *
    * Tests that Option Some values are properly converted to successful Eru effects with correct
    * value preservation.
    */
  test("Eru.fromOption converts Some values to Success") {
    val some = Some(42)
    val eru = Eru.fromOption(some, "not found")
    assertEquals(eru.unsafeRunSync(), 42)
  }

  /** Validates that Eru.fromOption converts None to Failure with provided error.
    *
    * Tests that Option None values are properly converted to failed Eru effects using the provided
    * error value.
    */
  test("Eru.fromOption converts None to Failure with provided error") {
    val none = None
    val error = "option was empty"
    val eru = Eru.fromOption(none, error)

    interceptMessage[EruException[String]]("option was empty") {
      eru.unsafeRunSync()
    }
  }

  /** Validates that Eru.fromOption evaluates option and error lazily.
    *
    * Tests that both option and error parameters are evaluated lazily, with error evaluation only
    * occurring when needed for None cases.
    */
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

    assert(!optionEvaluated)
    assert(!errorEvaluated)

    val result = eru.unsafeRunSync()
    assertEquals(result, 42)
    assert(optionEvaluated)
    assert(!errorEvaluated)
  }

  /** Validates that Eru.fromOption evaluates error lazily only for None case.
    *
    * Tests that error parameter evaluation is deferred until actually needed when the Option is
    * None.
    */
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

    assert(errorEvaluated)
  }

  /** Property test validating that Eru.fromOption preserves Option semantics.
    *
    * Verifies that Option conversion maintains correct Some/None semantics across a wide range of
    * Option values.
    */
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

  /** Property test validating that Eru.fromOption with Some is equivalent to Eru.succeed.
    *
    * Verifies that fromOption with Some values produces identical results to direct succeed
    * construction.
    */
  property("Eru.fromOption with Some is equivalent to Eru.succeed") {
    forAll(smallInts) { value =>
      val fromOption = Eru.fromOption(Some(value), "error").attempt.unsafeRunSync()
      val succeed = Eru.succeed(value).attempt.unsafeRunSync()
      fromOption == succeed
    }
  }

  /** Validates that Eru.unit contains Unit value.
    *
    * Tests that the unit effect produces the Unit value when executed.
    */
  test("Eru.unit contains Unit value") {
    val result: Unit = Eru.unit.unsafeRunSync()
    assertEquals(result, ())
  }

  /** Validates that Eru.unit is a successful effect.
    *
    * Tests that the unit effect represents a successful computation with Unit result.
    */
  test("Eru.unit is a successful effect") {
    val result = Eru.unit.attempt.unsafeRunSync()
    assertEquals(result, Result.Success(()))
  }

  /** Validates that Eru.unit can be composed with other effects.
    *
    * Tests that the unit effect properly participates in monadic composition without affecting
    * other computations.
    */
  test("Eru.unit can be composed with other effects") {
    val composed = for {
      _ <- Eru.unit
      value <- Eru.succeed(42)
      _ <- Eru.unit
    } yield value

    assertEquals(composed.unsafeRunSync(), 42)
  }

  /** Validates that Eru.unit is equivalent to Eru.succeed(()).
    *
    * Tests that the unit effect produces the same result as explicitly succeeding with the Unit
    * value.
    */
  test("Eru.unit is equivalent to Eru.succeed(())") {
    val unit = Eru.unit.attempt.unsafeRunSync()
    val succeed = Eru.succeed(()).attempt.unsafeRunSync()
    assertEquals(unit, succeed)
  }

  /** Property test validating that Eru.unit always succeeds with Unit.
    *
    * Verifies that the unit effect consistently produces successful results with the Unit value
    * across all test executions.
    */
  property("Eru.unit always succeeds with Unit") {
    forAll(Gen.const(())) { _ =>
      val result = Eru.unit.attempt.unsafeRunSync()
      result == Result.Success(())
    }
  }

  /** Validates that all companion methods compose correctly in for-comprehension.
    *
    * Tests that fromEither, fromTry, fromOption, blocking, and unit can all be composed together in
    * monadic chains successfully.
    */
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

  /** Validates that all companion methods handle errors correctly in composition.
    *
    * Tests that error propagation works correctly when companion methods are composed together and
    * one produces a failure.
    */
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

  // =======================================================================
  // Tests for new iterative construction methods
  // =======================================================================

  /** Tests for Eru.iterateN method. */

  test("Eru.iterateN with n=0 returns start value immediately") {
    val result = Eru.iterateN(42, 0)(_ => Eru.succeed(99)).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("Eru.iterateN with n=1 executes step once") {
    val result = Eru.iterateN(10, 1)(x => Eru.succeed(x * 2)).unsafeRunSync()
    assertEquals(result, 20)
  }

  test("Eru.iterateN with negative n fails with descriptive error") {
    val result = Eru.iterateN(0, -1)(_ => Eru.succeed(1)).attempt.unsafeRunSync()
    result match {
      case Result.Failure(error: String) =>
        assert(error.contains("iterateN requires n >= 0"))
        assert(error.contains("-1"))
      case _ => fail(s"Expected String error, got $result")
    }
  }

  test("Eru.iterateN executes exactly n iterations") {
    // Pure test: verify the final result shows exactly n iterations occurred
    val result = Eru.iterateN(0, 5)(current => Eru.succeed(current + 1)).unsafeRunSync()
    assertEquals(result, 5)

    // Also test with a computation that tracks progress via the value itself
    val resultWithSum = Eru
      .iterateN((0, 0), 5) { case (current, sum) =>
        Eru.succeed((current + 1, sum + current + 1))
      }
      .unsafeRunSync()
    assertEquals(resultWithSum._1, 5) // 5 iterations
    assertEquals(resultWithSum._2, 15) // Sum: 1+2+3+4+5 = 15
  }

  test("Eru.iterateN handles large iteration counts without stack overflow") {
    val result = Eru.iterateN(0, 10000)(current => Eru.succeed(current + 1)).unsafeRunSync()
    assertEquals(result, 10000)
  }

  test("Eru.iterateN propagates step function errors") {
    val result = Eru
      .iterateN(0, 3) { current =>
        if (current == 2) Eru.fail("step error") else Eru.succeed(current + 1)
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(error: String) => assertEquals(error, "step error")
      case _ => fail(s"Expected error, got $result")
    }
  }

  property("Eru.iterateN with n iterations produces correct final value") {
    forAll(smallInts, Gen.choose(0, 100)) { (start, n) =>
      val result = Eru.iterateN(start, n)(x => Eru.succeed(x + 1)).unsafeRunSync()
      assertEquals(result, start + n)
    }
  }

  /** Tests for Eru.unfold method. */

  test("Eru.unfold generates empty list when starting with None") {
    val result = Eru.unfold(())(_ => Eru.succeed(None)).unsafeRunSync()
    assertEquals(result, List.empty[Nothing])
  }

  test("Eru.unfold generates single element list") {
    val result = Eru
      .unfold(1) { x =>
        if (x == 1) Eru.succeed(Some((x, 2)))
        else Eru.succeed(None)
      }
      .unsafeRunSync()
    assertEquals(result, List(1))
  }

  test("Eru.unfold generates Fibonacci sequence") {
    val result = Eru
      .unfold((0, 1)) { case (a, b) =>
        if (a > 20) Eru.succeed(None)
        else Eru.succeed(Some((a, (b, a + b))))
      }
      .unsafeRunSync()

    assertEquals(result, List(0, 1, 1, 2, 3, 5, 8, 13))
  }

  test("Eru.unfold propagates errors from generator function") {
    val result = Eru
      .unfold(0) { x =>
        if (x == 0) Eru.fail("generator error")
        else Eru.succeed(Some((x, x + 1)))
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(error: String) => assertEquals(error, "generator error")
      case _ => fail(s"Expected error, got $result")
    }
  }

  test("Eru.unfold handles large sequences without stack overflow") {
    val result = Eru
      .unfold(0) { x =>
        if (x >= 1000) Eru.succeed(None)
        else Eru.succeed(Some((x, x + 1)))
      }
      .unsafeRunSync()

    assertEquals(result.length, 1000)
    assertEquals(result.take(5), List(0, 1, 2, 3, 4))
    assertEquals(result.takeRight(5), List(995, 996, 997, 998, 999))
  }

  property("Eru.unfold respects termination condition") {
    forAll(Gen.choose(0, 50)) { limit =>
      val result = Eru
        .unfold(0) { x =>
          if (x >= limit) Eru.succeed(None)
          else Eru.succeed(Some((x, x + 1)))
        }
        .unsafeRunSync()

      assertEquals(result.length, limit)
      if (limit > 0) {
        assertEquals(result.head, 0)
        assertEquals(result.last, limit - 1)
      }
    }
  }

  /** Tests for Eru.sequence method. */

  test("Eru.sequence with empty list returns empty list") {
    val result = Eru.sequence(List.empty[Eru[String, Int]]).unsafeRunSync()
    assertEquals(result, List.empty[Int])
  }

  test("Eru.sequence with single success returns single element list") {
    val result = Eru.sequence(List(Eru.succeed(42))).unsafeRunSync()
    assertEquals(result, List(42))
  }

  test("Eru.sequence with multiple successes returns all results") {
    val effects = List(Eru.succeed(1), Eru.succeed(2), Eru.succeed(3))
    val result = Eru.sequence(effects).unsafeRunSync()
    assertEquals(result, List(1, 2, 3))
  }

  test("Eru.sequence fails fast on first error") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("second fails"),
      Eru.succeed(3)
    )
    val result = Eru.sequence(effects).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error: String) => assertEquals(error, "second fails")
      case _ => fail(s"Expected error, got $result")
    }
  }

  test("Eru.sequence handles large lists without stack overflow") {
    val effects = (1 to 1000).map(Eru.succeed).toList
    val result = Eru.sequence(effects).unsafeRunSync()
    assertEquals(result.length, 1000)
    assertEquals(result.take(5), List(1, 2, 3, 4, 5))
    assertEquals(result.takeRight(5), List(996, 997, 998, 999, 1000))
  }

  property("Eru.sequence preserves order") {
    forAll(Gen.listOf(smallInts)) { numbers =>
      val effects = numbers.map(Eru.succeed)
      val result = Eru.sequence(effects).unsafeRunSync()
      assertEquals(result, numbers)
    }
  }

  /** Tests for Eru.traverse method. */

  test("Eru.traverse with empty list returns empty list") {
    val result = Eru.traverse(List.empty[Int])(x => Eru.succeed(x * 2)).unsafeRunSync()
    assertEquals(result, List.empty[Int])
  }

  test("Eru.traverse transforms and sequences correctly") {
    val result = Eru.traverse(List(1, 2, 3))(x => Eru.succeed(x * 2)).unsafeRunSync()
    assertEquals(result, List(2, 4, 6))
  }

  test("Eru.traverse fails fast on first transformation error") {
    val result = Eru
      .traverse(List(1, 2, 3)) { x =>
        if (x == 2) Eru.fail("transformation error") else Eru.succeed(x * 2)
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(error: String) => assertEquals(error, "transformation error")
      case _ => fail(s"Expected error, got $result")
    }
  }

  test("Eru.traverse handles large lists without stack overflow") {
    val input = (1 to 1000).toList
    val result = Eru.traverse(input)(x => Eru.succeed(x * 2)).unsafeRunSync()
    assertEquals(result.length, 1000)
    assertEquals(result.take(5), List(2, 4, 6, 8, 10))
    assertEquals(result.takeRight(5), List(1992, 1994, 1996, 1998, 2000))
  }

  test("Eru.traverse is equivalent to sequence(map(f))") {
    val input = List(1, 2, 3, 4, 5)
    val f = (x: Int) => Eru.succeed(x * x)

    val traverseResult = Eru.traverse(input)(f).unsafeRunSync()
    val sequenceMapResult = Eru.sequence(input.map(f)).unsafeRunSync()

    assertEquals(traverseResult, sequenceMapResult)
  }

  property("Eru.traverse preserves input-output correspondence") {
    forAll(Gen.listOf(smallInts)) { numbers =>
      val result = Eru.traverse(numbers)(x => Eru.succeed(x.toString)).unsafeRunSync()
      assertEquals(result, numbers.map(_.toString))
    }
  }

  /** Stack safety regression test for all new iterative methods. */
  test("Stack safety regression test for all iterative methods") {
    val largeN = 10000

    // Test iterateN
    val iterateNResult = Eru.iterateN(0, largeN)(x => Eru.succeed(x + 1)).unsafeRunSync()
    assertEquals(iterateNResult, largeN)

    // Test unfold
    val unfoldResult = Eru
      .unfold(0) { x =>
        if (x >= largeN) Eru.succeed(None) else Eru.succeed(Some((x, x + 1)))
      }
      .unsafeRunSync()
    assertEquals(unfoldResult.length, largeN)

    // Test sequence
    val effects = (1 to largeN).map(Eru.succeed).toList
    val sequenceResult = Eru.sequence(effects).unsafeRunSync()
    assertEquals(sequenceResult.length, largeN)

    // Test traverse
    val traverseResult = Eru.traverse((1 to largeN).toList)(x => Eru.succeed(x)).unsafeRunSync()
    assertEquals(traverseResult.length, largeN)
  }
}
