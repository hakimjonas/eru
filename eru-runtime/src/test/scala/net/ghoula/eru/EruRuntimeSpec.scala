package net.ghoula.eru

import munit.FunSuite

import scala.collection.mutable.ListBuffer

class EruRuntimeSpec extends FunSuite {

  test("fork/await returns Exit.Success on success") {
    val fiber = EruRuntime.fork(Eru.succeed(42)).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(v) => assertEquals(v, 42)
      case other => fail(s"expected Success, got $other")
    }
  }

  test("fork/await returns Exit.Failure on typed failure") {
    val fiber = EruRuntime.fork(Eru.fail("boom")).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case other => fail(s"expected Failure, got $other")
    }
  }

  test("fork/await returns Exit.Die on defect (Throwable)") {
    val ex = new RuntimeException("kaboom")
    val fiber = EruRuntime.fork(Eru.effect[Int](throw ex)).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Die(t) => assertEquals(t, ex)
      case other => fail(s"expected Die, got $other")
    }
  }

  test("interrupt records cause and await returns Exit.Interrupt") {
    val fiber = EruRuntime.fork(Eru.succeed(1)).unsafeRunSync()
    fiber.interrupt(InterruptCause.Cancelled).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Interrupt(fid, cause) =>
        assertEquals(fid, fiber.id)
        assertEquals(cause, InterruptCause.Cancelled)
      case other => fail(s"expected Interrupt, got $other")
    }
  }

  test("forkWithObserver emits FiberStarted and FiberCompleted") {
    class Obs extends EruObserver {
      val buf: ListBuffer[EruEvent] = scala.collection.mutable.ListBuffer.empty[EruEvent]
      def onEvent(event: EruEvent): Unit = buf += event
    }
    val obs = new Obs
    val fiber = EruRuntime.forkWithObserver(Eru.succeed(5), obs).unsafeRunSync()
    // Drive the scheduler by awaiting completion so events are emitted
    fiber.await.unsafeRunSync()
    val evs = obs.buf.toList
    assert(evs.nonEmpty)
    val (fidStarted, fidCompleted) = evs match {
      case EruEvent.FiberStarted(fid) :: EruEvent.FiberCompleted(fid2, exit) :: _ =>
        exit match {
          case Exit.Success(v: Int) => assertEquals(v, 5)
          case other => fail(s"expected Success in FiberCompleted, got $other")
        }
        (fid, fid2)
      case other => fail(s"expected FiberStarted then FiberCompleted, got $other"); (FiberId.fresh(), FiberId.fresh())
    }
    assertEquals(fidStarted, fiber.id)
    assertEquals(fidCompleted, fiber.id)
  }

  // New tests for zipPar and race placeholders
  test("zipPar success-success returns tuple") {
    val eff = EruRuntime.zipPar(Eru.succeed(1), Eru.succeed("ok"))
    val res = eff.unsafeRunSync()
    assertEquals(res, (1, "ok"))
  }

  test("race returns Left when left succeeds first") {
    val eff = EruRuntime.race(Eru.succeed(10), Eru.succeed("r"))
    val res = eff.unsafeRunSync()
    res match {
      case Left(a) => assertEquals(a, 10)
      case Right(_) => fail("expected Left winner")
    }
  }

  test("race returns Right when left fails and right succeeds") {
    val eff = EruRuntime.race(Eru.fail("boom"), Eru.succeed(99))
    val res = eff.unsafeRunSync()
    res match {
      case Right(b) => assertEquals(b, 99)
      case Left(_) => fail("expected Right winner")
    }
  }

  test("yieldNow is a no-op and returns Unit") {
    val u = EruRuntime.yieldNow.unsafeRunSync()
    assertEquals((), u)
  }
}
