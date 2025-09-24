package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for EruObserver system components not covered by basic tests.
  *
  * Tests the factory methods, specialized observer traits, ScopeId generation, and event structures
  * to ensure complete coverage of the observer API. Complements EruObserverSpec which focuses on
  * integration testing with the runtime.
  */
class EruObserverComprehensiveSpec extends munit.FunSuite {

  test("EruObserver.noop discards all events") {
    val observer = EruObserver.noop

    // Should not throw or have any side effects
    observer.onEvent(EruEvent.ProgramStart(ScopeId.fresh()))
    observer.onEvent(EruEvent.ProgramEnd(ScopeId.fresh(), Outcome.Success))
    observer.onEvent(EruEvent.Step(ScopeId.fresh(), "test"))

    // Test passes if no exceptions thrown
    assert(true)
  }

  test("EruObserver.console prints events to stdout") {
    // Capture stdout for testing
    val originalOut = System.out
    val capturedOutput = new java.io.ByteArrayOutputStream()
    val printStream = new java.io.PrintStream(capturedOutput)

    try {
      System.setOut(printStream)
      val observer = EruObserver.console
      val scopeId = ScopeId.fresh()

      observer.onEvent(EruEvent.ProgramStart(scopeId))

      val output = capturedOutput.toString
      assert(output.contains("ProgramStart"), "Should print ProgramStart event")
      assert(output.contains(scopeId.toString), "Should print scope ID")
    } finally {
      System.setOut(originalOut)
    }
  }

  test("ScopeId.fresh generates unique identifiers") {
    val id1 = ScopeId.fresh()
    val id2 = ScopeId.fresh()
    val id3 = ScopeId.fresh()

    // All IDs should be different
    assertNotEquals(id1, id2)
    assertNotEquals(id2, id3)
    assertNotEquals(id1, id3)

    // IDs are monotonic by design - test by generating them sequentially
    // Since ScopeId is opaque, we cannot directly access the underlying Long value
    // The monotonic property is guaranteed by the atomic counter implementation
  }

  test("Outcome.Success represents successful completion") {
    val outcome = Outcome.Success

    outcome match {
      case Outcome.Success => assert(true, "Pattern match should work")
      case _ => fail("Expected Success outcome")
    }
  }

  test("Outcome.TypedFailure wraps typed errors") {
    val error = "test error"
    val outcome = Outcome.TypedFailure(error)

    outcome match {
      case Outcome.TypedFailure(e) => assertEquals(e, error)
      case _ => fail("Expected TypedFailure outcome")
    }
  }

  test("Outcome.Defect wraps throwables") {
    val throwable = new RuntimeException("test exception")
    val outcome = Outcome.Defect(throwable)

    outcome match {
      case Outcome.Defect(t) => assertEquals(t, throwable)
      case _ => fail("Expected Defect outcome")
    }
  }

  test("EruEvent.ProgramStart contains scope ID") {
    val scopeId = ScopeId.fresh()
    val event = EruEvent.ProgramStart(scopeId)

    event match {
      case EruEvent.ProgramStart(id) => assertEquals(id, scopeId)
      case _ => fail("Expected ProgramStart event")
    }
  }

  test("EruEvent.ProgramEnd contains scope and outcome") {
    val scopeId = ScopeId.fresh()
    val outcome = Outcome.Success
    val event = EruEvent.ProgramEnd(scopeId, outcome)

    event match {
      case EruEvent.ProgramEnd(id, out) =>
        assertEquals(id, scopeId)
        assertEquals(out, outcome)
      case _ => fail("Expected ProgramEnd event")
    }
  }

  test("EruEvent.Step contains scope and label") {
    val scopeId = ScopeId.fresh()
    val label = "test step"
    val event = EruEvent.Step(scopeId, label)

    event match {
      case EruEvent.Step(id, lbl) =>
        assertEquals(id, scopeId)
        assertEquals(lbl, label)
      case _ => fail("Expected Step event")
    }
  }

  test("EruEvent.FiberStarted contains fiber ID") {
    val fiberId = FiberId.fresh()
    val event = EruEvent.FiberStarted(fiberId)

    event match {
      case EruEvent.FiberStarted(id) => assertEquals(id, fiberId)
      case _ => fail("Expected FiberStarted event")
    }
  }

  test("EruEvent.FiberCompleted contains fiber ID and exit") {
    val fiberId = FiberId.fresh()
    val exit = Exit.Success(42)
    val event = EruEvent.FiberCompleted(fiberId, exit)

    event match {
      case EruEvent.FiberCompleted(id, ex) =>
        assertEquals(id, fiberId)
        assertEquals(ex, exit)
      case _ => fail("Expected FiberCompleted event")
    }
  }

  test("EruEvent.FiberInterrupted contains fiber ID and cause") {
    val fiberId = FiberId.fresh()
    val cause = InterruptCause.Cancelled(Some("test"))
    val event = EruEvent.FiberInterrupted(fiberId, cause)

    event match {
      case EruEvent.FiberInterrupted(id, c) =>
        assertEquals(id, fiberId)
        assertEquals(c, cause)
      case _ => fail("Expected FiberInterrupted event")
    }
  }

  test("EruEvent.FiberForked contains parent and child IDs") {
    val parentId = FiberId.fresh()
    val childId = FiberId.fresh()
    val event = EruEvent.FiberForked(parentId, childId)

    event match {
      case EruEvent.FiberForked(parent, child) =>
        assertEquals(parent, parentId)
        assertEquals(child, childId)
      case _ => fail("Expected FiberForked event")
    }
  }

  test("EruEvent.StructuredCleanupStarted contains fiber ID and count") {
    val fiberId = FiberId.fresh()
    val childCount = 3
    val event = EruEvent.StructuredCleanupStarted(fiberId, childCount)

    event match {
      case EruEvent.StructuredCleanupStarted(id, count) =>
        assertEquals(id, fiberId)
        assertEquals(count, childCount)
      case _ => fail("Expected StructuredCleanupStarted event")
    }
  }

  test("EruEvent.StructuredCleanupCompleted contains counts") {
    val fiberId = FiberId.fresh()
    val interruptedCount = 2
    val completedCount = 1
    val event = EruEvent.StructuredCleanupCompleted(fiberId, interruptedCount, completedCount)

    event match {
      case EruEvent.StructuredCleanupCompleted(id, interrupted, completed) =>
        assertEquals(id, fiberId)
        assertEquals(interrupted, interruptedCount)
        assertEquals(completed, completedCount)
      case _ => fail("Expected StructuredCleanupCompleted event")
    }
  }

  test("EruEvent.ChildInterruptionRequested contains all fields") {
    val parentId = FiberId.fresh()
    val childId = FiberId.fresh()
    val cause = InterruptCause.Timeout(java.time.Duration.ofSeconds(30))
    val wasRunning = true
    val event = EruEvent.ChildInterruptionRequested(parentId, childId, cause, wasRunning)

    event match {
      case EruEvent.ChildInterruptionRequested(parent, child, c, running) =>
        assertEquals(parent, parentId)
        assertEquals(child, childId)
        assertEquals(c, cause)
        assertEquals(running, wasRunning)
      case _ => fail("Expected ChildInterruptionRequested event")
    }
  }

  test("StructuredConcurrencyObserver delegates events correctly") {
    var fiberForkedCalled = false
    var cleanupStartedCalled = false
    var cleanupCompletedCalled = false
    var childInterruptionCalled = false
    var fiberLifecycleCalled = false
    var programLifecycleCalled = false

    val observer = new StructuredConcurrencyObserver {
      override def onFiberForked(parentId: FiberId, childId: FiberId): Unit =
        fiberForkedCalled = true

      override def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit =
        cleanupStartedCalled = true

      override def onStructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int): Unit =
        cleanupCompletedCalled = true

      override def onChildInterruptionRequested(
        parentId: FiberId,
        childId: FiberId,
        cause: InterruptCause,
        childWasRunning: Boolean
      ): Unit =
        childInterruptionCalled = true

      override def onFiberLifecycle(event: EruEvent): Unit =
        fiberLifecycleCalled = true

      override def onProgramLifecycle(event: EruEvent): Unit =
        programLifecycleCalled = true

      override def onTracing(span: net.ghoula.eru.trace.EruTrace.Span): Unit = ()
    }

    // Test each event type delegation
    observer.onEvent(EruEvent.FiberForked(FiberId.fresh(), FiberId.fresh()))
    assert(fiberForkedCalled, "Should delegate FiberForked")

    observer.onEvent(EruEvent.StructuredCleanupStarted(FiberId.fresh(), 1))
    assert(cleanupStartedCalled, "Should delegate StructuredCleanupStarted")

    observer.onEvent(EruEvent.StructuredCleanupCompleted(FiberId.fresh(), 1, 0))
    assert(cleanupCompletedCalled, "Should delegate StructuredCleanupCompleted")

    observer.onEvent(
      EruEvent.ChildInterruptionRequested(FiberId.fresh(), FiberId.fresh(), InterruptCause.Cancelled(), true)
    )
    assert(childInterruptionCalled, "Should delegate ChildInterruptionRequested")

    observer.onEvent(EruEvent.FiberStarted(FiberId.fresh()))
    assert(fiberLifecycleCalled, "Should delegate FiberStarted to lifecycle")

    observer.onEvent(EruEvent.ProgramStart(ScopeId.fresh()))
    assert(programLifecycleCalled, "Should delegate ProgramStart to lifecycle")
  }

  test("TracingEruObserver delegates trace events correctly") {
    var spanCompletedCalled = false
    var otherEventCalled = false

    val observer = new TracingEruObserver {
      override def onSpanCompleted(span: net.ghoula.eru.trace.EruTrace.Span): Unit =
        spanCompletedCalled = true

      override def onOtherEvent(event: EruEvent): Unit =
        otherEventCalled = true
    }

    // Test trace event delegation
    val span = new net.ghoula.eru.trace.EruTrace.Span(
      spanId = net.ghoula.eru.trace.EruTrace.SpanId.fresh(),
      traceId = net.ghoula.eru.trace.EruTrace.TraceId.fresh(),
      parentSpanId = None,
      operation = "test",
      startTime = java.time.Instant.ofEpochMilli(0),
      endTime = Some(java.time.Instant.ofEpochMilli(100))
    )
    observer.onEvent(EruEvent.TraceSpan(span))
    assert(spanCompletedCalled, "Should delegate TraceSpan to onSpanCompleted")

    // Test other event delegation
    observer.onEvent(EruEvent.ProgramStart(ScopeId.fresh()))
    assert(otherEventCalled, "Should delegate non-trace events to onOtherEvent")
  }

  test("Default StructuredConcurrencyObserver implementations are no-ops") {
    val observer = new StructuredConcurrencyObserver {}

    // Should not throw exceptions - all methods have empty default implementations
    observer.onFiberForked(FiberId.fresh(), FiberId.fresh())
    observer.onStructuredCleanupStarted(FiberId.fresh(), 1)
    observer.onStructuredCleanupCompleted(FiberId.fresh(), 1, 0)
    observer.onChildInterruptionRequested(FiberId.fresh(), FiberId.fresh(), InterruptCause.Cancelled(), true)
    observer.onFiberLifecycle(EruEvent.FiberStarted(FiberId.fresh()))
    observer.onProgramLifecycle(EruEvent.ProgramStart(ScopeId.fresh()))
    observer.onTracing(
      new net.ghoula.eru.trace.EruTrace.Span(
        spanId = net.ghoula.eru.trace.EruTrace.SpanId.fresh(),
        traceId = net.ghoula.eru.trace.EruTrace.TraceId.fresh(),
        parentSpanId = None,
        operation = "test",
        startTime = java.time.Instant.ofEpochMilli(0),
        endTime = Some(java.time.Instant.ofEpochMilli(100))
      )
    )

    // Test passes if no exceptions thrown
    assert(true)
  }

  test("Default TracingEruObserver.onOtherEvent is no-op") {
    val observer = new TracingEruObserver {
      override def onSpanCompleted(span: net.ghoula.eru.trace.EruTrace.Span): Unit = ()
    }

    // Should not throw exception - default implementation is no-op
    observer.onOtherEvent(EruEvent.ProgramStart(ScopeId.fresh()))

    // Test passes if no exceptions thrown
    assert(true)
  }

  test("ScopeId maintains process uniqueness properties") {
    // Generate multiple IDs in sequence
    val ids = (1 to 100).map(_ => ScopeId.fresh())

    // All IDs should be unique
    val uniqueIds = ids.toSet
    assertEquals(uniqueIds.size, 100, "All generated IDs should be unique")

    // IDs are monotonic by design - the implementation guarantees this
    // Since ScopeId is opaque, we test uniqueness rather than direct ordering
    // The monotonic property is ensured by the atomic counter in the implementation
  }
}
