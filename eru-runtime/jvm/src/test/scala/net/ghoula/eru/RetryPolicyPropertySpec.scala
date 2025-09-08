package net.ghoula.eru

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Property-based test suite for RetryPolicy functionality and invariants.
  *
  * Uses generative testing to validate that retry policies maintain mathematical correctness across
  * a wide range of inputs and configurations. Tests verify policy composition, duration
  * calculations, and termination conditions to ensure that retry mechanisms provide predictable and
  * reliable behavior under all operational scenarios.
  */
final class RetryPolicyPropertySpec extends ScalaCheckSuite {

  private val nonNegativeSmall: Gen[Int] = Gen.choose(0, 10)
  private val positiveSmall: Gen[Int] = Gen.choose(1, 10)

  property("Policy.Recurs(n) bounds attempts and succeeds if successIndex <= n + 1") {
    forAll(nonNegativeSmall, positiveSmall) { (maxRetries, successIndexRaw) =>
      val successIndex = successIndexRaw // 1 means succeed on first attempt
      var attempts = 0

      val eff: Eru[String | Throwable, Int] = Eru.effect {
        attempts += 1
        attempts
      }.flatMap { n =>
        if n < successIndex then Eru.fail("retry") else Eru.succeed(42)
      }

      val retried = eff.retry(EruRuntime.Policy.Recurs(maxRetries))
      val exit = retried.runExit()

      val expectedAttempts = Math.min(successIndex, maxRetries + 1)
      val attemptsOk = attempts == expectedAttempts

      val outcomeOk =
        if successIndex <= maxRetries + 1 then exit == Exit.Success(42)
        else
          exit match {
            case Exit.Failure(e) => e == "retry"
            case _ => false
          }

      attemptsOk && outcomeOk
    }
  }

  property("Policy.Exponential(base, max) bounds attempts deterministically (base ZERO to avoid delay)") {
    forAll(nonNegativeSmall, positiveSmall) { (maxRetries, successIndexRaw) =>
      val base = Duration.ZERO
      val successIndex = successIndexRaw
      var attempts = 0

      val eff: Eru[String | Throwable, Int] = Eru.effect {
        attempts += 1
        attempts
      }.flatMap { n =>
        if n < successIndex then Eru.fail("retry") else Eru.succeed(7)
      }

      val retried = eff.retry(EruRuntime.Policy.Exponential(base, maxRetries))
      val exit = retried.runExit()

      val expectedAttempts = Math.min(successIndex, maxRetries + 1)
      val attemptsOk = attempts == expectedAttempts

      val outcomeOk =
        if successIndex <= maxRetries + 1 then exit == Exit.Success(7)
        else
          exit match {
            case Exit.Failure(e) => e == "retry"
            case _ => false
          }

      attemptsOk && outcomeOk
    }
  }

  property("Defects (Throwable) are not retried") {
    forAll(nonNegativeSmall) { (maxRetries) =>
      var attempts = 0
      val boom = new RuntimeException("boom")
      val eff: Eru[Any, Int] =
        Eru.effect {
          attempts += 1
          throw boom
        }

      val exit = eff.retryN(maxRetries).runExit()

      (attempts == 1) && (exit match {
        case Exit.Die(t) => t.getClass == boom.getClass && t.getMessage == boom.getMessage
        case _ => false
      })
    }
  }
}
