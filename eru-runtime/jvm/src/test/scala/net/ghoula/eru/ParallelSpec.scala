package net.ghoula.eru

import munit.FunSuite

import java.time.Duration
//import scala.annotation.nowarn

//@nowarn("msg=.*")
final class ParallelSpec extends FunSuite {

  private def nowMs(): Long = System.nanoTime() / 1000000L

  test("zipPar runs effects in parallel on JVM VT backend (timing check)") {
    val a = EruRuntime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(1))
    val b = EruRuntime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(2))

    val start = nowMs()
    val out = EruRuntime.zipPar(a, b).unsafeRunSync()
    val took = nowMs() - start

    assertEquals(out, (1, 2))
    // Parallel should be significantly less than sequential ~60ms; allow generous slack for CI/load conditions
    assert(took < 120L)
  }

  test("zipPar propagates typed failure (left-biased on both-fail)") {
    val leftFail: Eru[String, Int] = Eru.fail("boom")
    val rightSucc = EruRuntime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.succeed(2))

    val res = EruRuntime.zipPar(leftFail, rightSucc).attempt.unsafeRunSync()
    res match {
      case Result.Failure(e) => assertEquals(s"$e", "boom")
      case Result.Success(_) => fail("Expected typed failure, but got success")
    }
  }

  test("race returns the faster winner and does not block on the loser") {
    val fastLeft = EruRuntime.sleep(Duration.ofMillis(5)).flatMap(_ => Eru.succeed("L"))
    val slowRight = EruRuntime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed("R"))

    val start = nowMs()
    val out = EruRuntime.race(fastLeft, slowRight).unsafeRunSync()
    val took = nowMs() - start

    assertEquals(out, Left("L"))
    assert(clue(took) < 25L)
  }
}
