package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class ObservabilitySpec extends FunSuite {
  test("observer sees ProgramStart, Step, and ProgramEnd(Success)") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    val value = Eru.succeed(123).debug("step-1").unsafeRunSyncWith(obs)

    assertEquals(value, 123)
    val xs = events.toList
    xs match {
      case EruEvent
            .ProgramStart(scopeId) :: EruEvent.Step(scopeId2, label) :: EruEvent.ProgramEnd(scopeId3, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        assertEquals(scopeId3, scopeId)
        assertEquals(label, "step-1")
        assertEquals(outcome, EruObserver.Outcome.Success)
      case other => fail(s"unexpected events: $other")
    }
  }

  test("observer sees ProgramStart and ProgramEnd(TypedFailure)") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    intercept[EruException[String]] {
      Eru.fail("boom").unsafeRunSyncWith(obs)
    }

    val xs = events.toList
    xs match {
      case EruEvent.ProgramStart(scopeId) :: EruEvent.ProgramEnd(scopeId2, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        outcome match {
          case EruObserver.Outcome.TypedFailure(err) => assertEquals(err, "boom")
          case other => fail(s"unexpected outcome: $other")
        }
      case other => fail(s"unexpected events: $other")
    }
  }

  test("observer sees ProgramStart and ProgramEnd(Defect) on Throwable") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }
    val boom = new RuntimeException("kapow")

    intercept[RuntimeException] {
      Eru.effect[Int](throw boom).unsafeRunSyncWith(obs)
    }

    val xs = events.toList
    xs match {
      case EruEvent.ProgramStart(scopeId) :: EruEvent.ProgramEnd(scopeId2, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        outcome match {
          case EruObserver.Outcome.Defect(t) =>
            assertEquals(t.getClass, boom.getClass)
            assertEquals(t.getMessage, boom.getMessage)
          case other => fail(s"unexpected outcome: $other")
        }
      case other => fail(s"unexpected events: $other")
    }
  }

  test("forkWithObserver emits FiberStarted and FiberCompleted with Exit.Success") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    val fiber = Eru.succeed(10).forkWithObserver(obs).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(10))

    val xs = events.toList
    xs match {
      case EruEvent.FiberStarted(fid1) :: EruEvent.FiberCompleted(fid2, ex) :: Nil =>
        assertEquals(fid2, fid1)
        assertEquals(ex, Exit.Success(10))
      case other => fail(s"unexpected fiber events: $other")
    }
  }
}
