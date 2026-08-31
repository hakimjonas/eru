package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Specialized test suite for resource management under defect conditions.
  *
  * Validates resource cleanup behavior when encountering unrecoverable errors (defects) such as
  * Throwable exceptions during resource usage. These tests ensure that even when the system
  * encounters unexpected failures, resource finalizers are properly executed and no resource leaks
  * occur, maintaining system reliability under extreme error conditions.
  */
final class EruResourceDefectSpec extends munit.FunSuite {

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

  test("bracket handles defects in acquisition phase") {
    var released = 0
    val acquireException = new RuntimeException("acquire failed")

    val acquire: Eru[Throwable, String] = Eru.effect[String](throw acquireException)
    val release: String => Eru[Nothing, Unit] = _ => Eru.effect { released += 1; () }.attempt.flatMap(_ => Eru.unit)

    val prog = acquire.bracket(release) { res => Eru.succeed(res.length) }
    val exit = prog.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t, acquireException)
      case other => fail(s"expected Die, got $other")
    }

    assertEquals(released, 0)
  }

  test("bracket handles defects in release phase while preserving original result") {
    var used = false
    val releaseException = new RuntimeException("release failed")

    val acquire: Eru[Nothing, String] = Eru.succeed("resource")
    val release: String => Eru[Throwable, Unit] = _ => Eru.effect[Unit](throw releaseException)

    val prog = acquire.bracket(release) { res =>
      used = true
      Eru.succeed(res.length)
    }

    val exit = prog.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Success(length) => assertEquals(length, 8)
      case other => fail(s"expected Success, got $other")
    }

    assert(used, "Resource should have been used")
  }

  test("ensure handles defects in finalizer without affecting main result") {
    var finalizerRan = false
    val finalizerException = new RuntimeException("finalizer failed")

    val prog = Eru.succeed(42).ensure {
      Eru.effect {
        finalizerRan = true
        throw finalizerException
      }.attempt.flatMap(_ => Eru.unit)
    }

    val result = prog.unsafeRunSync()
    assertEquals(result, 42)
    assert(finalizerRan, "Finalizer should have run")
  }

  test("nested bracket with defects maintains proper cleanup order") {
    var cleanupOrder = List.empty[String]

    val outerAcquire = Eru.succeed("outer")
    val outerRelease =
      (res: String) => Eru.effect { cleanupOrder = res :: cleanupOrder; () }.attempt.flatMap(_ => Eru.unit)

    val innerAcquire = Eru.succeed("inner")
    val innerRelease =
      (res: String) => Eru.effect { cleanupOrder = res :: cleanupOrder; () }.attempt.flatMap(_ => Eru.unit)

    val boom = new RuntimeException("nested boom")

    val prog = outerAcquire.bracket(outerRelease) { _ =>
      innerAcquire.bracket(innerRelease) { _ =>
        Eru.effect[Int](throw boom)
      }
    }

    val exit = prog.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t, boom)
      case other => fail(s"expected Die, got $other")
    }

    assertEquals(cleanupOrder, List("inner", "outer"))
  }

  test("defects in complex resource chains maintain resource safety") {
    var resource1Released = false
    var resource2Released = false
    var resource3Released = false

    val chain = for {
      r1 <- Eru
        .succeed("res1")
        .autoCleanup(_ => Eru.effect { resource1Released = true; () }.attempt.flatMap(_ => Eru.unit))
      r2 <- Eru
        .succeed("res2")
        .autoCleanup(_ => Eru.effect { resource2Released = true; () }.attempt.flatMap(_ => Eru.unit))
      r3 <- Eru
        .succeed("res3")
        .autoCleanup(_ => Eru.effect { resource3Released = true; () }.attempt.flatMap(_ => Eru.unit))
      _ <- Eru.effect[Unit](throw new RuntimeException("chain defect"))
    } yield (r1, r2, r3)

    val exit = chain.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Die(_) => ()
      case other => fail(s"expected Die, got $other")
    }

    assert(resource1Released, "Resource 1 should be released")
    assert(resource2Released, "Resource 2 should be released")
    assert(resource3Released, "Resource 3 should be released")
  }

  test("defects during error recovery still trigger resource cleanup") {
    var cleaned = false
    val originalError = "original failure"
    val recoveryDefect = new RuntimeException("recovery defect")

    val prog = Eru
      .fail(originalError)
      .ensure(Eru.effect { cleaned = true; () }.attempt.flatMap(_ => Eru.unit))
      .recoverWith { case _ => Eru.effect[Int](throw recoveryDefect) }

    val exit = prog.attempt.map(Result.toExit).unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t, recoveryDefect)
      case other => fail(s"expected Die, got $other")
    }

    assert(cleaned, "Cleanup should have occurred despite recovery defect")
  }
}
