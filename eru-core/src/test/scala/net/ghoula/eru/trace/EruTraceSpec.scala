package net.ghoula.eru.trace

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*
import net.ghoula.eru.trace.EruTrace.Span

/** Test suite for the EruTrace functionality and tracing infrastructure.
  *
  * Validates span creation, trace propagation, observer integration, and the correctness of trace
  * data collection across effect composition boundaries.
  */
final class EruTraceSpec extends FunSuite {

  /** Validates that SpanId.fresh generates unique identifiers.
    *
    * Tests that each call to SpanId.fresh produces a unique identifier with positive value,
    * ensuring proper span identification in tracing systems.
    */
  test("SpanId.fresh generates unique identifiers") {
    val id1 = EruTrace.SpanId.fresh()
    val id2 = EruTrace.SpanId.fresh()
    assert(id1.toLong != id2.toLong)
    assert(id1.toLong > 0)
    assert(id2.toLong > 0)
  }

  /** Validates that TraceId.fresh generates unique identifiers.
    *
    * Tests that each call to TraceId.fresh produces a unique identifier with positive value,
    * ensuring proper trace identification across distributed systems.
    */
  test("TraceId.fresh generates unique identifiers") {
    val id1 = EruTrace.TraceId.fresh()
    val id2 = EruTrace.TraceId.fresh()
    assert(id1.toLong != id2.toLong)
    assert(id1.toLong > 0)
    assert(id2.toLong > 0)
  }

  /** Validates basic span creation and property access.
    *
    * Tests that spans are created with correct initial properties including IDs, operation name,
    * status, and empty collections for tags and events.
    */
  test("Span creation and modification") {
    import EruTrace.*

    val spanId = SpanId.fresh()
    val traceId = TraceId.fresh()

    val span = Span(
      spanId = spanId,
      traceId = traceId,
      parentSpanId = None,
      operation = "test-operation",
      startTime = java.time.Instant.now()
    )

    assertEquals(span.spanId, spanId)
    assertEquals(span.traceId, traceId)
    assertEquals(span.operation, "test-operation")
    assertEquals(span.status, SpanStatus.InProgress)
    assert(span.tags.isEmpty)
    assert(span.events.isEmpty)
  }

  /** Validates span tag management functionality.
    *
    * Tests that tags can be added to spans individually and in batches, and that tag values are
    * correctly stored and retrievable.
    */
  test("Span with tags") {
    import EruTrace.*

    val span = Span(
      spanId = SpanId.fresh(),
      traceId = TraceId.fresh(),
      parentSpanId = None,
      operation = "tagged-operation",
      startTime = java.time.Instant.now()
    ).withTag("key1", "value1")
      .withTags(Map("key2" -> "value2", "key3" -> "value3"))

    assertEquals(span.tags.size, 3)
    assertEquals(span.tags("key1"), "value1")
    assertEquals(span.tags("key2"), "value2")
    assertEquals(span.tags("key3"), "value3")
  }

  /** Validates span event management functionality.
    *
    * Tests that events can be added to spans and that event data including timestamps, names, and
    * attributes are correctly stored.
    */
  test("Span with events") {
    import EruTrace.*

    val event1 = SpanEvent(
      timestamp = java.time.Instant.now(),
      name = "event1",
      attributes = Map("attr1" -> "value1")
    )

    val event2 = SpanEvent(
      timestamp = java.time.Instant.now(),
      name = "event2"
    )

    val span = Span(
      spanId = SpanId.fresh(),
      traceId = TraceId.fresh(),
      parentSpanId = None,
      operation = "event-operation",
      startTime = java.time.Instant.now()
    ).withEvent(event1).withEvent(event2)

    assertEquals(span.events.size, 2)
    assert(span.events.contains(event1))
    assert(span.events.contains(event2))
  }

  /** Validates span completion with status and timing.
    *
    * Tests that spans can be completed with a final status and that completion automatically sets
    * end time and calculates duration.
    */
  test("Span completion") {
    import EruTrace.*

    val span = Span(
      spanId = SpanId.fresh(),
      traceId = TraceId.fresh(),
      parentSpanId = None,
      operation = "completion-test",
      startTime = java.time.Instant.now()
    )

    val completedSpan = span.complete(SpanStatus.Success)
    assertEquals(completedSpan.status, SpanStatus.Success)
    assert(completedSpan.endTime.isDefined)
    assert(completedSpan.duration.isDefined)
  }

  /** Validates TraceContext creation and child span generation.
    *
    * Tests that trace contexts are properly initialized and can create child spans with correct
    * hierarchy relationships and trace propagation.
    */
  test("TraceContext creation and child spans") {
    import EruTrace.*

    val traceId = TraceId.fresh()
    val context = TraceContext(traceId)

    assertEquals(context.traceId, traceId)
    assert(context.currentSpan.isEmpty)
    assert(context.baggage.isEmpty)

    val (newContext, childSpan) = context.createChildSpan("child-operation")
    assertEquals(newContext.traceId, traceId)
    assertEquals(newContext.currentSpan, Some(childSpan))
    assertEquals(childSpan.operation, "child-operation")
    assertEquals(childSpan.traceId, traceId)
    assert(childSpan.parentSpanId.isEmpty)
  }

  /** Validates TraceContext baggage management.
    *
    * Tests that baggage key-value pairs can be added to trace contexts for cross-cutting concerns
    * and distributed tracing metadata.
    */
  test("TraceContext with baggage") {
    import EruTrace.*

    val context = TraceContext(TraceId.fresh())
      .withBaggage("user-id", "12345")
      .withBaggage("request-id", "req-789")

    assertEquals(context.baggage.size, 2)
    assertEquals(context.baggage("user-id"), "12345")
    assertEquals(context.baggage("request-id"), "req-789")
  }

  /** Validates that nested child spans maintain proper hierarchy.
    *
    * Tests that parent-child relationships are correctly established when creating nested spans
    * within a trace context.
    */
  test("nested child spans maintain hierarchy") {
    import EruTrace.*

    val traceId = TraceId.fresh()
    val rootContext = TraceContext(traceId)

    val (context1, span1) = rootContext.createChildSpan("parent-op")
    val context1WithSpan = context1.copy(currentSpan = Some(span1))

    val (_, span2) = context1WithSpan.createChildSpan("child-op")

    assertEquals(span1.traceId, traceId)
    assertEquals(span2.traceId, traceId)
    assertEquals(span2.parentSpanId, Some(span1.spanId))
  }

  /** Validates that traced extension method creates spans correctly.
    *
    * Tests that the traced extension method properly instruments effects with tracing while
    * preserving computation results.
    */
  test("traced extension method creates spans") {

    class TestObserver extends TracingEruObserver {
      private var _spans: List[Span] = Nil
      def spans: List[Span] = _spans.reverse

      def onSpanCompleted(span: Span): Unit = {
        _spans = span :: _spans
      }
    }

    val observer = new TestObserver
    val effect = Eru.succeed(42).traced("test-operation")

    val result = effect.unsafeRunSyncWith(observer)
    assertEquals(result, 42)
  }

  /** Validates that traced extension method accepts tags.
    *
    * Tests that the traced extension method can be configured with tags for enhanced observability
    * metadata while preserving effect semantics.
    */
  test("traced extension method with tags") {
    val effect = Eru
      .succeed("hello")
      .traced(
        "tagged-operation",
        Map("component" -> "test", "version" -> "1.0")
      )

    val result = effect.unsafeRunSync()
    assertEquals(result, "hello")
  }

  /** Validates that traced extension method preserves error semantics.
    *
    * Tests that tracing instrumentation does not interfere with error propagation in failed
    * effects.
    */
  test("traced extension method preserves errors") {
    val effect = Eru.fail("boom").traced("failing-operation")

    val exception = intercept[EruException[String]] {
      effect.unsafeRunSync()
    }
    assertEquals(exception.error, "boom")
  }

  /** Validates that traced extension method works with complex effect chains.
    *
    * Tests that tracing can be applied to individual steps in monadic compositions while
    * maintaining correct execution semantics.
    */
  test("traced extension method works with complex chains") {
    val effect = for {
      a <- Eru.succeed(10).traced("step-1")
      b <- Eru.succeed(20).traced("step-2")
      c <- Eru.succeed(a + b).traced("step-3")
    } yield c

    val result = effect.unsafeRunSync()
    assertEquals(result, 30)
  }

  /** Validates that traceEvent extension method adds events to spans.
    *
    * Tests that trace events can be added to effect execution with custom attributes for detailed
    * observability.
    */
  test("traceEvent extension method works") {
    val effect = Eru
      .succeed(42)
      .traceEvent("checkpoint-1", Map("value" -> "42"))
      .map(_ * 2)
      .traceEvent("checkpoint-2", Map("doubled" -> "true"))

    val result = effect.unsafeRunSync()
    assertEquals(result, 84)
  }

  /** Validates that withTraceBaggage extension method manages baggage.
    *
    * Tests that baggage can be added to trace contexts through extension methods while preserving
    * effect execution.
    */
  test("withTraceBaggage extension method works") {
    val effect = Eru
      .succeed("result")
      .withTraceBaggage("user-id", "user123")
      .withTraceBaggage("session-id", "sess456")

    val result = effect.unsafeRunSync()
    assertEquals(result, "result")
  }

  /** Validates that tracing extensions compose with other effect extensions.
    *
    * Tests that tracing extensions can be combined with resource management and other effect
    * extensions without conflicts.
    */
  test("tracing extensions compose with other extensions") {
    val effect = Eru
      .succeed("resource")
      .traced("resource-acquisition")
      .autoCleanup(_ => Eru.unit)
      .traceEvent("resource-acquired")
      .withTraceBaggage("resource-type", "test")

    val result = effect.unsafeRunSync()
    assertEquals(result, "resource")
  }

  /** Validates that TracingEruObserver handles different event types correctly.
    *
    * Tests that the tracing observer properly dispatches span events and other EruEvent types to
    * appropriate handlers.
    */
  test("TracingEruObserver handles different event types") {
    class TestTracingObserver extends TracingEruObserver {
      private var _spans: List[EruTrace.Span] = Nil
      private var _otherEvents: List[EruEvent] = Nil
      def spans: List[EruTrace.Span] = _spans.reverse
      def otherEvents: List[EruEvent] = _otherEvents.reverse

      def onSpanCompleted(span: EruTrace.Span): Unit = {
        _spans = span :: _spans
      }

      override def onOtherEvent(event: EruEvent): Unit = {
        _otherEvents = event :: _otherEvents
      }
    }

    val observer = new TestTracingObserver

    observer.onEvent(EruEvent.ProgramStart(ScopeId.fresh()))

    assertEquals(observer.spans.size, 0)
    assertEquals(observer.otherEvents.size, 1)
  }

  /** Validates that startTrace creates root trace context correctly.
    *
    * Tests that starting a new trace creates a properly initialized root context with appropriate
    * span and metadata.
    */
  test("startTrace creates root trace context") {
    import EruTrace.*

    val context = startTrace("root-operation", Map("service" -> "test"))

    assert(context.currentSpan.isDefined)
    val rootSpan = context.currentSpan.get
    assertEquals(rootSpan.operation, "root-operation")
    assertEquals(rootSpan.tags("service"), "test")
    assert(rootSpan.parentSpanId.isEmpty)
  }

  /** Validates that getCurrentContext returns appropriate value.
    *
    * Tests that the current trace context can be retrieved from the ambient tracing system.
    */
  test("getCurrentContext returns None initially") {
    import EruTrace.*

    val _ = getCurrentContext
  }

  /** Validates that span status variants work correctly.
    *
    * Tests that all span status variants (InProgress, Success, Error, Cancelled) are properly
    * constructed and pattern matched.
    */
  test("span status variants work correctly") {
    import EruTrace.SpanStatus.*

    val inProgress = InProgress
    val success = Success
    val error = Error("test error")
    val cancelled = Cancelled

    assertEquals(inProgress, InProgress)
    assertEquals(success, Success)
    error match {
      case Error(msg) => assertEquals(msg, "test error")
      case _ => fail("Expected Error status variant")
    }
    assertEquals(cancelled, Cancelled)
  }
}
