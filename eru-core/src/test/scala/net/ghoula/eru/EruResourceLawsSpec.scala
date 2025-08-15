package net.ghoula.eru

import munit.FunSuite

import scala.util.Random

class EruResourceLawsSpec extends FunSuite {

  private val samples = 200
  private val maxDepth = 8

  // Generate a small random Eru[String, Int] program using only succeed/fail/map/flatMap
  // to keep the error channel stable (String) for equivalence via attempt.
  private def genProgram(rng: Random, depth: Int): Eru[String, Int] = {
    if (depth <= 0) {
      if (rng.nextBoolean()) Eru.succeed(rng.nextInt(20)) else Eru.fail("e0")
    } else {
      if (rng.nextBoolean()) {
        // map branch
        genProgram(rng, depth - 1).map(_ + 1)
      } else {
        // base
        if (rng.nextBoolean()) Eru.succeed(rng.nextInt(20)) else Eru.fail("e2")
      }
    }
  }

  test("ensure identity: fa.ensure(unit).attempt == fa.attempt (randomized)") {
    val rng = new Random(12345L)
    var i = 0
    while (i < samples) {
      val prog = genProgram(rng, rng.nextInt(maxDepth + 1))
      val lhs = prog.ensure(Eru.unit).attempt.unsafeRunSync()
      val rhs = prog.attempt.unsafeRunSync()
      assertEquals(lhs, rhs)
      i += 1
    }
  }

  test("bracket release runs exactly once regardless of use outcome (randomized)") {
    val rng = new Random(9876L)
    var i = 0
    while (i < samples) {
      var released = 0
      val acquire: Eru[Nothing, Int] = Eru.succeed(rng.nextInt(10) + 1)
      val release: Int => Eru[Nothing, Unit] = _ => Eru.effect { released += 1; () }.attempt.flatMap(_ => Eru.unit)
      val runSuccess = rng.nextBoolean()
      val prog: Eru[String, Int] = acquire.bracket(release) { a =>
        if (runSuccess) Eru.succeed(a * 3) else Eru.fail("boom")
      }
      if (runSuccess) {
        val out = prog.unsafeRunSync()
        assert(out >= 3)
      } else {
        val ex = intercept[EruException[String]] { prog.unsafeRunSync() }
        assertEquals(ex.error, "boom")
      }
      assertEquals(released, 1)
      i += 1
    }
  }

  test("nested ensures follow FILO ordering for random depths") {
    val rng = new Random(13579L)
    var i = 0
    while (i < samples) {
      val depth = 1 + rng.nextInt(maxDepth + 1)
      val order = scala.collection.mutable.ListBuffer.empty[Int]
      val base: Eru[Nothing, Unit] = Eru.unit
      val prog = (1 to depth).foldLeft(base) { (acc, k) =>
        val fin = Eru.effect { order += k; () }
        acc.ensure(fin)
      }
      prog.unsafeRunSync()
      assertEquals(order.toList, (1 to depth).reverse.toList)
      i += 1
    }
  }
}
