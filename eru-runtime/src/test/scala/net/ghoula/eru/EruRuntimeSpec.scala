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
}
