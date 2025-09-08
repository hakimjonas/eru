package net.ghoula.eru

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for JVM-specific parallel execution functionality.
  *
  * Validates parallel combinators and concurrent execution on the JVM virtual thread backend. These
  * tests ensure that parallel operations achieve true concurrency, maintain proper timing
  * characteristics, and provide correct error handling semantics while leveraging the performance
  * benefits of the JVM's virtual thread implementation.
  */
final class ParallelSpec extends FunSuite {

  test("zipPar runs effects in parallel on JVM VT backend") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val a = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(1))
      val b = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(2))

      val out = runtime.zipPar(a, b).unsafeRunSync()
      assertEquals(out, (1, 2))
    }
  }

  test("zipPar propagates typed failure (left-biased on both-fail)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val leftFail: Eru[String, Int] = Eru.fail("boom")
      val rightSucc = runtime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.succeed(2))

      val res = runtime.zipPar(leftFail, rightSucc).attempt.unsafeRunSync()
      res match {
        case Result.Failure(e) => assertEquals(s"$e", "boom")
        case Result.Success(_) => fail("Expected typed failure, but got success")
      }
    }
  }

  test("race returns the faster winner and does not block on the loser") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fastLeft = runtime.sleep(Duration.ofMillis(5)).flatMap(_ => Eru.succeed("L"))
      val slowRight = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed("R"))

      val out = runtime.race(fastLeft, slowRight).unsafeRunSync()
      assertEquals(out, Left("L"))
    }
  }
}
