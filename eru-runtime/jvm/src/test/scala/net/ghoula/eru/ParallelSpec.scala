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
final class ParallelSpec extends TestWithRuntime {

  test("zipPar runs effects in parallel on JVM VT backend") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val a = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(1))
      val b = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(2))

      // Fork the zipPar operation to allow TestClock advancement
      val fiber = runtime.fork(runtime.zipPar(a, b)).unsafeRunSync()

      // Advance TestClock to complete the sleep operations
      runtime.testClock.advance(Duration.ofMillis(30))

      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(out) => assertEquals(out, (1, 2))
        case other => fail(s"Expected successful zipPar, got: $other")
      }
    }
  }

  test("zipPar propagates typed failure (left-biased on both-fail)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val leftFail: Eru[String, Int] = Eru.fail("boom")
      val rightSucc = runtime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.succeed(2))

      // Fork the zipPar operation
      val fiber = runtime.fork(runtime.zipPar(leftFail, rightSucc)).unsafeRunSync()

      // Advance time to allow any sleep to complete (though failure should happen immediately)
      runtime.testClock.advance(Duration.ofMillis(10))

      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Failure(e) => assertEquals(s"$e", "boom")
        case other => fail(s"Expected typed failure, got: $other")
      }
    }
  }

  test("race returns the faster winner and does not block on the loser") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fastLeft = runtime.sleep(Duration.ofMillis(5)).flatMap(_ => Eru.succeed("L"))
      val slowRight = runtime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed("R"))

      // Fork the race operation
      val fiber = runtime.fork(runtime.race(fastLeft, slowRight)).unsafeRunSync()

      // Advance time to allow the faster operation to complete
      runtime.testClock.advance(Duration.ofMillis(5))

      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(out) => assertEquals(out, Left("L"))
        case other => fail(s"Expected successful race with Left result, got: $other")
      }
    }
  }
}
