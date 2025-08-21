package net.ghoula.eru

import munit.FunSuite

import scala.collection.mutable.ListBuffer

import net.ghoula.eru.prelude.*

class EruObserverSpec extends FunSuite {

  private class CollectingObserver extends EruObserver {
    val events: ListBuffer[EruEvent] = ListBuffer.empty
    def onEvent(event: EruEvent): Unit = events += event
  }

  test("unsafeRunSyncWith emits ProgramStart and ProgramEnd Success") {
    val obs = new CollectingObserver
    val out = Eru.succeed(123).unsafeRunSyncWith(obs)
    assertEquals(out, 123)
    assertEquals(obs.events.size, 2)
    val start = obs.events.head
    val end = obs.events(1)
    val scope = start match {
      case EruEvent.ProgramStart(s) => s
      case other => fail(s"expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Success) => assertEquals(s, scope)
      case other => fail(s"expected ProgramEnd Success, got $other")
    }
  }

  test("debug emits Step event with label before execution") {
    val obs = new CollectingObserver
    val p = Eru.succeed(1).debug("step-1")
    val value = p.unsafeRunSyncWith(obs)
    assertEquals(value, 1)
    assertEquals(obs.events.size, 3)
    val start = obs.events.head
    val step = obs.events(1)
    val end = obs.events(2)
    val scope = start match {
      case EruEvent.ProgramStart(s) => s
      case other => fail(s"expected ProgramStart, got $other")
    }
    step match {
      case EruEvent.Step(s, label) =>
        assertEquals(s, scope)
        assertEquals(label, "step-1")
      case other => fail(s"expected Step, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Success) => assertEquals(s, scope)
      case other => fail(s"expected ProgramEnd Success, got $other")
    }
  }

  test("typed failure emits ProgramEnd TypedFailure and throws EruException") {
    val obs = new CollectingObserver
    intercept[EruException[String]] {
      Eru.fail("oops").unsafeRunSyncWith(obs)
    }
    assertEquals(obs.events.size, 2)
    val start = obs.events.head
    val end = obs.events(1)
    val scope = start match {
      case EruEvent.ProgramStart(s) => s
      case other => fail(s"expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.TypedFailure(e)) =>
        assertEquals(s, scope)
        assertEquals(e, "oops")
      case other => fail(s"expected ProgramEnd TypedFailure, got $other")
    }
  }

  test("defect emits ProgramEnd Defect and rethrows Throwable") {
    val obs = new CollectingObserver
    val ex = new RuntimeException("boom")
    intercept[RuntimeException] {
      Eru.effect[Int](throw ex).unsafeRunSyncWith(obs)
    }
    assertEquals(obs.events.size, 2)
    val start = obs.events.head
    val end = obs.events(1)
    val scope = start match {
      case EruEvent.ProgramStart(s) => s
      case other => fail(s"expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Defect(t)) =>
        assertEquals(s, scope)
        assertEquals(t, ex)
      case other => fail(s"expected ProgramEnd Defect, got $other")
    }
  }

  test("ProgramEnd is emitted after finalizers are drained") {
    var finalized = 0
    class SnapObserver extends EruObserver {
      var programEndFinalizedSeen: Option[Int] = None
      def onEvent(event: EruEvent): Unit = event match {
        case EruEvent.ProgramEnd(_, _) => programEndFinalizedSeen = Some(finalized)
        case _ => ()
      }
    }
    val obs = new SnapObserver
    val prog = Eru.succeed(1).ensure(Eru.effect { finalized += 1; () })
    val out = prog.unsafeRunSyncWith(obs)
    assertEquals(out, 1)
    assertEquals(finalized, 1)
    assertEquals(obs.programEndFinalizedSeen, Some(1))
  }

}
