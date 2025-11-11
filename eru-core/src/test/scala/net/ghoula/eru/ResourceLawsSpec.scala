package net.ghoula.eru

import scala.util.Random

import net.ghoula.eru.CorePrelude.*

/** Verification of resource management laws and invariants in the Eru effect system.
  *
  * This specification ensures that resource management operations follow mathematical laws and
  * maintain consistent behavior across different execution scenarios. Tests validate that bracket,
  * ensure, and other resource operations maintain proper ordering, cleanup guarantees, and
  * compositional properties essential for predictable resource lifecycle management.
  */
class ResourceLawsSpec extends munit.FunSuite {

  private val samples = 200
  private val maxDepth = 8

  /** Generates a small random `Eru[String, Int]` program.
    *
    * This generator only uses `succeed`, `fail`, and `map` to keep the error channel stable as
    * `String` for equivalence checking via `attempt`.
    *
    * @param rng
    *   The random number generator.
    * @param depth
    *   The maximum recursion depth of generation.
    * @return
    *   A randomly generated `Eru` program.
    */
  private def genProgram(rng: Random, depth: Int): Eru[String, Int] = {
    if (depth <= 0) {
      if (rng.nextBoolean()) Eru.succeed(rng.nextInt(20)) else Eru.fail("e0")
    } else {
      if (rng.nextBoolean()) {
        genProgram(rng, depth - 1).map(_ + 1)
      } else {
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
      var order = List.empty[Int]
      val base: Eru[Nothing, Unit] = Eru.unit
      val prog = (1 to depth).foldLeft(base) { (acc, k) =>
        val fin = Eru.effect { order = k :: order; () }
        acc.ensure(fin)
      }
      prog.unsafeRunSync()
      assertEquals(order.reverse, (1 to depth).reverse.toList)
      i += 1
    }
  }
}
