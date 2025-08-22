package net.ghoula.eru

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import net.ghoula.eru.CorePrelude.*
import net.ghoula.eru.Result as EruResult

/** Property-based testing specification for Eru effect system using munit-scalacheck.
  *
  * This specification leverages generative testing to verify algebraic properties and invariants
  * across a wide range of inputs, providing mathematical rigor beyond example-based testing.
  * Properties are verified using ScalaCheck generators with random inputs to ensure correctness
  * holds universally.
  */
class EruPropertyBasedSpec extends ScalaCheckSuite {

  /** Generator for small positive integers to control test complexity. */
  private val smallPositiveInts: Gen[Int] = Gen.choose(1, 100)

  /** Generator for error strings representing typed failures. */
  private val errorStrings: Gen[String] = Gen.oneOf("error1", "error2", "network failure", "timeout")

  /** Generator for successful Eru effects with integer values. */
  private val successfulEru: Gen[Eru[String, Int]] =
    smallPositiveInts.map(Eru.succeed)

  /** Generator for failed Eru effects with string errors. */
  private val failedEru: Gen[Eru[String, Int]] =
    errorStrings.map(Eru.fail)

  /** Generator for arbitrary Eru effects (success or failure). */
  private val arbitraryEru: Gen[Eru[String, Int]] =
    Gen.oneOf(successfulEru, failedEru)

  /** Generator for pure transformation functions. */
  private val pureIntFunctions: Gen[Int => Int] =
    Gen.oneOf(
      Gen.const((x: Int) => x + 1),
      Gen.const((x: Int) => x * 2),
      Gen.const((x: Int) => x - 10),
      Gen.const((x: Int) => Math.abs(x))
    )

  /** Generator for Eru-returning continuation functions. */
  private val eruContinuations: Gen[Int => Eru[String, Int]] =
    Gen.oneOf(
      (x: Int) => Eru.succeed(x + 1),
      (x: Int) => Eru.succeed(x * 2),
      (x: Int) => if (x > 50) Eru.fail("too large") else Eru.succeed(x),
      (x: Int) => Eru.effect(x + 10).mapError(_.getMessage)
    )

  property("Functor law: fmap(id) = id") {
    forAll(arbitraryEru) { eru =>
      val result = eru.attempt.unsafeRunSync()
      val mappedResult = eru.map(identity).attempt.unsafeRunSync()
      result == mappedResult
    }
  }

  property("Functor law: fmap(f . g) = fmap(f) . fmap(g)") {
    forAll(arbitraryEru, pureIntFunctions, pureIntFunctions) { (eru, f, g) =>
      val leftSide = eru.map(f.andThen(g)).attempt.unsafeRunSync()
      val rightSide = eru.map(f).map(g).attempt.unsafeRunSync()
      leftSide == rightSide
    }
  }

  property("Monad law: left identity - pure(a) >>= f = f(a)") {
    forAll(smallPositiveInts, eruContinuations) { (value, f) =>
      val leftSide = Eru.succeed(value).flatMap(f).attempt.unsafeRunSync()
      val rightSide = f(value).attempt.unsafeRunSync()
      leftSide == rightSide
    }
  }

  property("Monad law: right identity - m >>= pure = m") {
    forAll(arbitraryEru) { eru =>
      val leftSide = eru.flatMap(Eru.succeed).attempt.unsafeRunSync()
      val rightSide = eru.attempt.unsafeRunSync()
      leftSide == rightSide
    }
  }

  property("Monad law: associativity - (m >>= f) >>= g = m >>= (\\x -> f x >>= g)") {
    forAll(arbitraryEru, eruContinuations, eruContinuations) { (eru, f, g) =>
      val leftSide = eru.flatMap(f).flatMap(g).attempt.unsafeRunSync()
      val rightSide = eru.flatMap(x => f(x).flatMap(g)).attempt.unsafeRunSync()
      leftSide == rightSide
    }
  }

  property("map preserves errors - failed effects remain failed after mapping") {
    forAll(failedEru, pureIntFunctions) { (failedEru, f) =>
      val originalResult = failedEru.attempt.unsafeRunSync()
      val mappedResult = failedEru.map(f).attempt.unsafeRunSync()

      (originalResult, mappedResult) match {
        case (EruResult.Failure(originalError), EruResult.Failure(mappedError)) =>
          originalError == mappedError
        case _ => false
      }
    }
  }

  property("flatMap error propagation - first error in chain is preserved") {
    forAll(failedEru, eruContinuations) { (failedEru, continuation) =>
      val originalResult = failedEru.attempt.unsafeRunSync()
      val chainedResult = failedEru.flatMap(continuation).attempt.unsafeRunSync()

      (originalResult, chainedResult) match {
        case (EruResult.Failure(originalError), EruResult.Failure(chainedError)) =>
          originalError == chainedError
        case _ => false
      }
    }
  }

  property("attempt never fails at type level - always produces Result") {
    forAll(arbitraryEru) { eru =>
      // attempt should never throw - it always succeeds with a Result
      val result = eru.attempt.unsafeRunSync()
      result match {
        case EruResult.Success(_) => true
        case EruResult.Failure(_) => true
      }
    }
  }

  property("orElse provides fallback only on failure") {
    forAll(arbitraryEru, arbitraryEru) { (first, fallback) =>
      val firstResult = first.attempt.unsafeRunSync()
      val combinedResult = first.orElse(fallback).attempt.unsafeRunSync()

      firstResult match {
        case success @ EruResult.Success(_) => combinedResult == success
        case EruResult.Failure(_) => combinedResult == fallback.attempt.unsafeRunSync()
      }
    }
  }

  property("zip combines successful results into pairs") {
    forAll(successfulEru, successfulEru) { (eru1, eru2) =>
      val result1 = eru1.attempt.unsafeRunSync()
      val result2 = eru2.attempt.unsafeRunSync()
      val zippedResult = eru1.zip(eru2).attempt.unsafeRunSync()

      (result1, result2, zippedResult) match {
        case (EruResult.Success(a), EruResult.Success(b), EruResult.Success((zipA, zipB))) =>
          a == zipA && b == zipB
        case _ => false
      }
    }
  }

  property("zip short-circuits on first failure") {
    forAll(failedEru, arbitraryEru) { (failedEru, other) =>
      val failedResult = failedEru.attempt.unsafeRunSync()
      val zippedResult = failedEru.zip(other).attempt.unsafeRunSync()

      (failedResult, zippedResult) match {
        case (EruResult.Failure(originalError), EruResult.Failure(zippedError)) =>
          originalError == zippedError
        case _ => false
      }
    }
  }

  property("recover converts matched errors to success") {
    forAll(errorStrings) { errorString =>
      val failedEru = Eru.fail(errorString)
      val recoveredResult = failedEru.recover {
        case err if err == errorString => 999
      }.attempt.unsafeRunSync()

      recoveredResult == EruResult.Success(999)
    }
  }

  property("recover leaves unmatched errors as failures") {
    forAll(errorStrings, errorStrings) { (actualError, matchedError) =>
      if (actualError != matchedError) {
        val failedEru = Eru.fail(actualError)
        val recoveredResult = failedEru.recover {
          case err if err == matchedError => 999
        }.attempt.unsafeRunSync()

        recoveredResult == EruResult.Failure(actualError)
      } else {
        // Skip this case when errors match
        true
      }
    }
  }
}
