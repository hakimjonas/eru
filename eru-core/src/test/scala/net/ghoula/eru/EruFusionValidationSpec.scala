package net.ghoula.eru

import munit.FunSuite

class EruFusionValidationSpec extends FunSuite {

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

  test("mixed pure/impure chains produce expected AST structure") {
    val prog = Eru
      .succeed(0)
      .flatMap(i => Eru.succeed(i + 1))
      .flatMap(i => Eru.effect(i + 1))

    import Eru.Internals
    Internals.view(prog) match {
      case Internals.View.VSucceed(_) => fail("Should not fully fuse with Effect")
      case _ => ()
    }
  }

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

  test("pure fusion handles exceptions correctly (single call)") {
    var callCount = 0
    val prog = Eru.succeed(0).flatMap { _ =>
      callCount += 1
      throw new RuntimeException("boom")
    }
    intercept[RuntimeException] { prog.unsafeRunSync() }
    assertEquals(callCount, 1)
  }

  test("deeply nested pure chains maintain correctness") {
    val depth = 10000
    val prog = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    assertEquals(prog.unsafeRunSync(), depth)
  }

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
