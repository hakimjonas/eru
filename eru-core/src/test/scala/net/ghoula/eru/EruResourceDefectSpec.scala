package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

final class EruResourceDefectSpec extends FunSuite {

  test("bracket release runs exactly once when use throws Throwable (defect path)") {
    var released = 0

    val acquire: Eru[Nothing, String] = Eru.succeed("res")
    val release: String => Eru[Nothing, Unit] = _ => Eru.effect { released += 1; () }.attempt.flatMap(_ => Eru.unit)

    val boom = new RuntimeException("boom")

    val prog: Eru[Throwable, Int] = acquire.bracket(release) { _ =>
      Eru.effect[Int](throw boom)
    }

    val exit = prog.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t, boom)
      case other => fail(s"expected Die, got $other")
    }

    assertEquals(released, 1)
  }
}
