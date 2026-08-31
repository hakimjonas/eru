package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the EruObserver system and event emission.
  *
  * Validates the observer pattern implementation including event capture, filtering, and proper
  * integration with effect execution. Tests ensure that the observer system provides complete
  * visibility into runtime behavior without affecting computational correctness or performance,
  * supporting the Runtime Observability pillar of the Eru framework.
  */
class EruObserverSpec extends munit.FunSuite {

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

  /** Validates that StructuredConcurrencyObserver routes each event category to its hook. */
  test("StructuredConcurrencyObserver dispatches events to their hooks") {
    class RecordingStructuredObserver extends StructuredConcurrencyObserver {
      var forked: List[(FiberId, FiberId)] = Nil
      var cleanupStarted: List[(FiberId, Int)] = Nil
      var cleanupCompleted: List[(FiberId, Int, Int)] = Nil
      var childInterruptions: List[(FiberId, FiberId, InterruptCause, Boolean)] = Nil
      var fiberLifecycle: List[EruEvent] = Nil
      var programLifecycle: List[EruEvent] = Nil
      var spans: List[net.ghoula.eru.trace.EruTrace.Span] = Nil

      override def onFiberForked(parentId: FiberId, childId: FiberId): Unit =
        forked = forked :+ ((parentId, childId))

      override def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit =
        cleanupStarted = cleanupStarted :+ ((fiberId, childCount))

      override def onStructuredCleanupCompleted(
        fiberId: FiberId,
        interruptedCount: Int,
        completedCount: Int
      ): Unit =
        cleanupCompleted = cleanupCompleted :+ ((fiberId, interruptedCount, completedCount))

      override def onChildInterruptionRequested(
        parentId: FiberId,
        childId: FiberId,
        cause: InterruptCause,
        childWasRunning: Boolean
      ): Unit =
        childInterruptions = childInterruptions :+ ((parentId, childId, cause, childWasRunning))

      override def onFiberLifecycle(event: EruEvent): Unit =
        fiberLifecycle = fiberLifecycle :+ event

      override def onProgramLifecycle(event: EruEvent): Unit =
        programLifecycle = programLifecycle :+ event

      override def onTracing(span: net.ghoula.eru.trace.EruTrace.Span): Unit =
        spans = spans :+ span
    }

    val obs = new RecordingStructuredObserver
    val parent = FiberId.fresh()
    val child = FiberId.fresh()
    val scope = EruObserver.ScopeId.fresh()
    val cause = InterruptCause.Cancelled(Some("test"))
    val exit: Exit[Any, Any] = Exit.Success(42)

    obs.onEvent(EruEvent.FiberForked(parent, child))
    obs.onEvent(EruEvent.StructuredCleanupStarted(parent, 3))
    obs.onEvent(EruEvent.StructuredCleanupCompleted(parent, 2, 1))
    obs.onEvent(EruEvent.ChildInterruptionRequested(parent, child, cause, childWasRunning = true))
    obs.onEvent(EruEvent.FiberStarted(child))
    obs.onEvent(EruEvent.FiberCompleted(child, exit))
    obs.onEvent(EruEvent.ProgramStart(scope))
    obs.onEvent(EruEvent.Step(scope, "label"))

    assertEquals(obs.forked, List((parent, child)))
    assertEquals(obs.cleanupStarted, List((parent, 3)))
    assertEquals(obs.cleanupCompleted, List((parent, 2, 1)))
    assertEquals(obs.childInterruptions, List((parent, child, cause, true)))
    assertEquals(obs.fiberLifecycle.size, 2)
    assertEquals(obs.programLifecycle.size, 2)
    assertEquals(obs.spans, Nil)
  }

  /** Validates that TracingEruObserver routes spans and non-span events separately. */
  test("TracingEruObserver routes spans to onSpanCompleted and the rest to onOtherEvent") {
    class RecordingTracingObserver extends TracingEruObserver {
      var spans: List[net.ghoula.eru.trace.EruTrace.Span] = Nil
      var other: List[EruEvent] = Nil

      override def onSpanCompleted(span: net.ghoula.eru.trace.EruTrace.Span): Unit =
        spans = spans :+ span

      override def onOtherEvent(event: EruEvent): Unit =
        other = other :+ event
    }

    val obs = new RecordingTracingObserver
    val span = net.ghoula.eru.trace.EruTrace.Span(
      spanId = net.ghoula.eru.trace.EruTrace.SpanId.fresh(),
      traceId = net.ghoula.eru.trace.EruTrace.TraceId.fresh(),
      parentSpanId = None,
      operation = "test-op",
      startTime = java.time.Instant.now()
    )
    val scope = EruObserver.ScopeId.fresh()

    obs.onEvent(EruEvent.TraceSpan(span))
    obs.onEvent(EruEvent.Step(scope, "not a span"))

    assertEquals(obs.spans, List(span))
    assertEquals(obs.other.size, 1)
    obs.other.head match {
      case EruEvent.Step(_, label) => assertEquals(label, "not a span")
      case otherEvent => fail(s"Expected Step, got $otherEvent")
    }
  }

}
