package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Validation suite for Eru's fusion optimization system.
  *
  * Verifies that the internal AST fusion optimizations produce semantically equivalent results
  * while maintaining correct program structure. Tests ensure that fusion rules preserve
  * computational semantics and do not introduce performance regressions or correctness issues,
  * validating the construction-time optimizations critical for high-performance effect execution.
  */
class EruFusionValidationSpec extends munit.FunSuite {

  /** Validates that pure fusion produces correct AST structure.
    *
    * Tests that chains of pure operations are properly fused into optimized AST representations
    * while maintaining computational correctness.
    */
  test("pure fusion produces correct AST structure") {
    val prog = (0 until 100).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    import Eru.Internals
    Internals.view(prog) match {
      case Internals.View.VSucceed(v) => assertEquals(v, 100)
      case other => fail(s"Expected Succeed(100), got $other")
    }
  }

  /** Validates that mixed pure/impure chains produce expected AST structure.
    *
    * Tests that fusion optimization correctly handles chains mixing pure and effectful operations,
    * preventing inappropriate fusion.
    */
  test("mixed pure/impure chains produce expected AST structure") {
    val prog = Eru
      .succeed(0)
      .flatMap(i => Eru.succeed(i + 1))
      .flatMap(i => Eru.effect(i + 1))

    import Eru.Internals
    Internals.view(prog) match {
      case Internals.View.VSucceed(_) => fail("Should not fuse with Effect")
      case _ => ()
    }
  }

  /** Validates that pure fusion gives same results as non-optimized execution.
    *
    * Tests that fusion optimizations preserve computational semantics by comparing optimized and
    * non-optimized execution results.
    */
  test("pure fusion gives same results as non-optimized") {
    val depth = 1000
    val nonOptimized = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.effect(i + 1))
    }
    val optimized = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    assertEquals(nonOptimized.unsafeRunSync(), optimized.unsafeRunSync())
  }

  /** Validates that pure fusion handles exceptions correctly with single call semantics.
    *
    * Tests that fusion optimization maintains proper exception handling behavior, ensuring
    * exceptions are thrown exactly once.
    */
  test("pure fusion handles exceptions correctly (single call)") {
    var callCount = 0
    val prog = Eru.succeed(0).flatMap { _ =>
      callCount += 1
      throw new RuntimeException("boom")
    }
    intercept[RuntimeException] { prog.unsafeRunSync() }
    assertEquals(callCount, 1)
  }

  /** Validates that deeply nested pure chains maintain correctness.
    *
    * Tests that fusion optimization handles deep chains efficiently while preserving computational
    * accuracy across thousands of operations.
    */
  test("deeply nested pure chains maintain correctness") {
    val depth = 10000
    val prog = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    assertEquals(prog.unsafeRunSync(), depth)
  }

  /** Validates pure fusion exception safety for throwing continuations.
    *
    * Tests that fusion optimization properly handles exceptions thrown from continuation functions
    * while preserving exception semantics.
    */
  test("pure fusion exception safety for throwing continuation") {
    def throwingFunction(x: Int): Eru[Nothing, Int] = {
      if (x < 0) throw new IllegalArgumentException("negative")
      else Eru.succeed(x * 2)
    }
    val prog1 = Eru.succeed(-1).flatMap(throwingFunction)
    val prog2 = Eru.succeed(5).flatMap(throwingFunction)

    intercept[IllegalArgumentException] { prog1.unsafeRunSync() }
    assertEquals(prog2.unsafeRunSync(), 10)
  }
}
