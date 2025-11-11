package net.ghoula.eru

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import net.ghoula.eru.CorePrelude.*

/** Property-based testing specification for Result data type using munit-scalacheck.
  *
  * This specification leverages generative testing to verify algebraic properties and invariants of
  * the Result type across a wide range of inputs, ensuring mathematical correctness of all Result
  * operations and companion object methods.
  */
class ResultPropertySpec extends munit.ScalaCheckSuite {

  /** Generator for small positive integers to control test complexity. */
  private val smallPositiveInts: Gen[Int] = Gen.choose(1, 1000)

  /** Generator for error strings representing typed failures. */
  private val errorStrings: Gen[String] =
    Gen.oneOf("error1", "error2", "network failure", "timeout", "validation error")

  /** Generator for successful Result values with integer content. */
  private val successfulResults: Gen[Result[String, Int]] =
    smallPositiveInts.map(Result.Success(_))

  /** Generator for failed Result values with string errors. */
  private val failedResults: Gen[Result[String, Int]] =
    errorStrings.map(Result.Failure(_))

  /** Generator for arbitrary Result values (success or failure). */
  private val arbitraryResults: Gen[Result[String, Int]] =
    Gen.oneOf(successfulResults, failedResults)

  /** Generator for pure transformation functions. */
  private val pureIntFunctions: Gen[Int => Int] =
    Gen.oneOf(
      Gen.const((x: Int) => x + 1),
      Gen.const((x: Int) => x * 2),
      Gen.const((x: Int) => x - 10),
      Gen.const((x: Int) => Math.abs(x)),
      Gen.const((x: Int) => x * x)
    )

  /** Generator for Result-returning continuation functions. */
  private val resultContinuations: Gen[Int => Result[String, Int]] =
    Gen.oneOf(
      (x: Int) => Result.Success(x + 1),
      (x: Int) => Result.Success(x * 2),
      (x: Int) => if (x > 500) Result.Failure("too large") else Result.Success(x),
      (x: Int) => if (x < 0) Result.Failure("negative") else Result.Success(x + 10)
    )

  property("Functor law: fmap(id) = id for Result") {
    forAll(arbitraryResults) { result =>
      val mapped = result.map(identity)
      result == mapped
    }
  }

  property("Functor law: fmap(f . g) = fmap(f) . fmap(g) for Result") {
    forAll(arbitraryResults, pureIntFunctions, pureIntFunctions) { (result, f, g) =>
      val leftSide = result.map(f.andThen(g))
      val rightSide = result.map(f).map(g)
      leftSide == rightSide
    }
  }

  property("Monad law: left identity for Result - pure(a) >>= f = f(a)") {
    forAll(smallPositiveInts, resultContinuations) { (value, f) =>
      val leftSide = Result.Success(value).flatMap(f)
      val rightSide = f(value)
      leftSide == rightSide
    }
  }

  property("Monad law: right identity for Result - m >>= pure = m") {
    forAll(arbitraryResults) { result =>
      val leftSide = result.flatMap(Result.Success(_))
      leftSide == result
    }
  }

  property("Monad law: associativity for Result - (m >>= f) >>= g = m >>= (\\x -> f x >>= g)") {
    forAll(arbitraryResults, resultContinuations, resultContinuations) { (result, f, g) =>
      val leftSide = result.flatMap(f).flatMap(g)
      val rightSide = result.flatMap(x => f(x).flatMap(g))
      leftSide == rightSide
    }
  }

  property("map preserves Success values with transformation") {
    forAll(successfulResults, pureIntFunctions) { (successResult, f) =>
      successResult match {
        case Result.Success(value) =>
          val mapped = successResult.map(f)
          mapped == Result.Success(f(value))
        case _ => false
      }
    }
  }

  property("map preserves Failure values unchanged") {
    forAll(failedResults, pureIntFunctions) { (failedResult, f) =>
      val mapped = failedResult.map(f)
      mapped == failedResult
    }
  }

  property("flatMap error propagation - first error in chain is preserved") {
    forAll(failedResults, resultContinuations) { (failedResult, continuation) =>
      val chained = failedResult.flatMap(continuation)
      chained == failedResult
    }
  }

  property("fold catamorphism property - handles both cases correctly") {
    forAll(arbitraryResults) { result =>
      val folded = result.fold(
        error => s"Error: $error",
        value => s"Success: $value"
      )

      result match {
        case Result.Success(value) => folded == s"Success: $value"
        case Result.Failure(error) => folded == s"Error: $error"
      }
    }
  }

  property("isSuccess and isFailure are complementary") {
    forAll(arbitraryResults) { result =>
      result.isSuccess != result.isFailure
    }
  }

  property("isSuccess returns true only for Success values") {
    forAll(arbitraryResults) { result =>
      result match {
        case Result.Success(_) => result.isSuccess
        case Result.Failure(_) => !result.isSuccess
      }
    }
  }

  property("isFailure returns true only for Failure values") {
    forAll(arbitraryResults) { result =>
      result match {
        case Result.Success(_) => !result.isFailure
        case Result.Failure(_) => result.isFailure
      }
    }
  }

  property("Result.succeed creates Success values") {
    forAll(smallPositiveInts) { value =>
      val result = Result.succeed(value)
      result == Result.Success(value) && result.isSuccess
    }
  }

  property("Result.fail creates Failure values") {
    forAll(errorStrings) { error =>
      val result = Result.fail(error)
      result == Result.Failure(error) && result.isFailure
    }
  }

  property("Result.fold catamorphism produces correct output type") {
    forAll(arbitraryResults) { result =>
      val stringOutput = Result.fold(result)(
        error => s"Failed: $error",
        value => s"Succeeded: $value"
      )

      val booleanOutput = Result.fold(result)(
        _ => false,
        _ => true
      )

      result match {
        case Result.Success(value) =>
          stringOutput == s"Succeeded: $value" && booleanOutput == true
        case Result.Failure(error) =>
          stringOutput == s"Failed: $error" && booleanOutput == false
      }
    }
  }

  property("Result.toEru preserves Success and Failure semantics") {
    forAll(arbitraryResults) { result =>
      val eru = Result.toEru(result)
      val backToResult = eru.attempt.unsafeRunSync()

      result match {
        case success @ Result.Success(_) => backToResult == success
        case failure @ Result.Failure(_) => backToResult == failure
      }
    }
  }

  property("Result.toExit preserves Success values") {
    forAll(successfulResults) { successResult =>
      successResult match {
        case Result.Success(value) =>
          val exit = Result.toExit(successResult)
          exit == Exit.Success(value)
        case _ => false
      }
    }
  }

  property("Result.toExit converts Throwable failures to Exit.Die") {
    forAll(
      Gen.oneOf(
        new RuntimeException("runtime"),
        new IllegalArgumentException("illegal"),
        new NullPointerException("null")
      )
    ) { throwable =>
      val result = Result.Failure(throwable)
      val exit = Result.toExit(result)
      exit == Exit.Die(throwable)
    }
  }

  property("Result.toExit converts typed failures to Exit.Failure") {
    forAll(errorStrings) { error =>
      val result = Result.Failure(error)
      val exit = Result.toExit(result)
      exit == Exit.Failure(error)
    }
  }

  property("Result composition preserves algebraic laws") {
    forAll(arbitraryResults, resultContinuations) { (result, f) =>
      val mapThenFlatMap = result.map(identity).flatMap(f)
      val directFlatMap = result.flatMap(f)

      mapThenFlatMap == directFlatMap
    }
  }
}
