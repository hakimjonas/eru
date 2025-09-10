package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the EruObserver system and event emission.
  *
  * Validates the observer pattern implementation including event capture, filtering, and proper
  * integration with effect execution. Tests ensure that the observer system provides complete
  * visibility into runtime behavior without affecting computational correctness or performance,
  * supporting the Exceptional Observability pillar of the Eru framework.
  */
class EruObserverSpec extends FunSuite {

  private class CollectingObserver extends EruObserver {
    private var _events: List[EruEvent] = Nil
    def events: List[EruEvent] = _events.reverse
    def onEvent(event: EruEvent): Unit = _events = event :: _events
  }

  /** Validates that unsafeRunSyncWith emits ProgramStart and ProgramEnd Success events.
    *
    * Tests that successful effect execution produces the correct observer events with matching
    * scope identifiers and success outcome.
    */
  test("unsafeRunSyncWith emits ProgramStart and ProgramEnd Success") {
    val obs = new CollectingObserver
    val out = Eru.succeed(123).unsafeRunSyncWith(obs)
    assertEquals(out, 123)
    assertEquals(obs.events.size, 2)
    val start = obs.events.head
    val end = obs.events(1)
    val scope = start match {
      case EruEvent.ProgramStart(s) => s
      case other => fail(s"Expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Success) => assertEquals(s, scope)
      case other => fail(s"Expected ProgramEnd Success, got $other")
    }
  }

  /** Validates that debug emits Step event with label before execution.
    *
    * Tests that debug operations produce Step events with correct labels and scope information in
    * the proper execution order.
    */
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
      case other => fail(s"Expected ProgramStart, got $other")
    }
    step match {
      case EruEvent.Step(s, label) =>
        assertEquals(s, scope)
        assertEquals(label, "step-1")
      case other => fail(s"Expected Step, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Success) => assertEquals(s, scope)
      case other => fail(s"Expected ProgramEnd Success, got $other")
    }
  }

  /** Validates that typed failure emits ProgramEnd TypedFailure and throws EruException.
    *
    * Tests that typed failures produce the correct observer events with failure outcomes while
    * still throwing the appropriate exception.
    */
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
      case other => fail(s"Expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.TypedFailure(e)) =>
        assertEquals(s, scope)
        assertEquals(e, "oops")
      case other => fail(s"Expected ProgramEnd TypedFailure, got $other")
    }
  }

  /** Validates that defect emits ProgramEnd Defect and rethrows Throwable.
    *
    * Tests that unhandled exceptions produce the correct observer events with defect outcomes while
    * still rethrowing the original exception.
    */
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
      case other => fail(s"Expected ProgramStart, got $other")
    }
    end match {
      case EruEvent.ProgramEnd(s, Outcome.Defect(t)) =>
        assertEquals(s, scope)
        assertEquals(t, ex)
      case other => fail(s"Expected ProgramEnd Defect, got $other")
    }
  }

  /** Validates that ProgramEnd is emitted after finalizers are drained.
    *
    * Tests that observer events maintain correct ordering with finalizer execution, ensuring
    * ProgramEnd occurs only after all cleanup is complete.
    */
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
