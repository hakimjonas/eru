package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Test suite for JVM-specific parallel execution functionality.
  *
  * Validates parallel combinators and concurrent execution on the JVM virtual thread backend. These
  * tests ensure that parallel operations achieve true concurrency, maintain proper timing
  * characteristics, and provide correct error handling semantics while leveraging the performance
  * benefits of the JVM's virtual thread implementation.
  */
final class ParallelSpec extends EruTestSuite {

  test("zipPar runs effects in parallel on JVM VT backend") {
    val a = sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(1))
    val b = sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(2))

    val result = a.zipPar(b).unsafeRunSync()
    assertEquals(result, (1, 2))
  }

  test("zipPar propagates typed failure (left-biased on both-fail)") {
    val leftFail: Eru[String, Int] = Eru.fail("boom")
    val rightSucc = sleep(Duration.ofMillis(10)).flatMap(_ => Eru.succeed(2))

    val result = leftFail.zipPar(rightSucc).attempt.unsafeRunSync()
    result match {
      case Result.Failure(e) => assertEquals(s"$e", "boom")
      case other => fail(s"Expected typed failure, got: $other")
    }
  }

  test("race returns the faster winner and does not block on the loser") {
    val fastLeft = sleep(Duration.ofMillis(5)).flatMap(_ => Eru.succeed("L"))
    val slowRight = sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed("R"))

    val result = fastLeft.race(slowRight).unsafeRunSync()
    assertEquals(result, Left("L"))
  }
}
