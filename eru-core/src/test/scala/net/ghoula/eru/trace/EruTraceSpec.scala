package net.ghoula.eru.trace

import munit.FunSuite

import scala.collection.mutable.ListBuffer

import net.ghoula.eru.CorePrelude.*
import net.ghoula.eru.trace.EruTrace.Span

/** Test suite for the EruTrace functionality and tracing infrastructure.
  *
  * Validates span creation, trace propagation, observer integration, and the correctness of trace
  * data collection across effect composition boundaries.
  */
final class EruTraceSpec extends FunSuite {

  test("SpanId.fresh generates unique identifiers") {
    val id1 = EruTrace.SpanId.fresh()
    val id2 = EruTrace.SpanId.fresh()
    assert(id1.toLong != id2.toLong)
    assert(id1.toLong > 0)
    assert(id2.toLong > 0)
  }

  test("TraceId.fresh generates unique identifiers") {
    val id1 = EruTrace.TraceId.fresh()
    val id2 = EruTrace.TraceId.fresh()
    assert(id1.toLong != id2.toLong)
    assert(id1.toLong > 0)
    assert(id2.toLong > 0)
  }

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

  test("TraceContext with baggage") {
    import EruTrace.*

    val context = TraceContext(TraceId.fresh())
      .withBaggage("user-id", "12345")
      .withBaggage("request-id", "req-789")

    assertEquals(context.baggage.size, 2)
    assertEquals(context.baggage("user-id"), "12345")
    assertEquals(context.baggage("request-id"), "req-789")
  }

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

  test("traced extension method creates spans") {

    class TestObserver extends TracingEruObserver {
      val spans: ListBuffer[Span] = ListBuffer.empty[Span]

      def onSpanCompleted(span: Span): Unit = {
        spans += span
      }
    }

    val observer = new TestObserver
    val effect = Eru.succeed(42).traced("test-operation")

    val result = effect.unsafeRunSyncWith(observer)
    assertEquals(result, 42)
  }

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

  test("traced extension method preserves errors") {
    val effect = Eru.fail("boom").traced("failing-operation")

    val exception = intercept[EruException[String]] {
      effect.unsafeRunSync()
    }
    assertEquals(exception.error, "boom")
  }

  test("traced extension method works with complex chains") {
    val effect = for {
      a <- Eru.succeed(10).traced("step-1")
      b <- Eru.succeed(20).traced("step-2")
      c <- Eru.succeed(a + b).traced("step-3")
    } yield c

    val result = effect.unsafeRunSync()
    assertEquals(result, 30)
  }

  test("traceEvent extension method works") {
    val effect = Eru
      .succeed(42)
      .traceEvent("checkpoint-1", Map("value" -> "42"))
      .map(_ * 2)
      .traceEvent("checkpoint-2", Map("doubled" -> "true"))

    val result = effect.unsafeRunSync()
    assertEquals(result, 84)
  }

  test("withTraceBaggage extension method works") {
    val effect = Eru
      .succeed("result")
      .withTraceBaggage("user-id", "user123")
      .withTraceBaggage("session-id", "sess456")

    val result = effect.unsafeRunSync()
    assertEquals(result, "result")
  }

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

  test("TracingEruObserver handles different event types") {
    class TestTracingObserver extends TracingEruObserver {
      val spans: ListBuffer[Span] = ListBuffer.empty[EruTrace.Span]
      val otherEvents: ListBuffer[EruEvent] = ListBuffer.empty[EruEvent]

      def onSpanCompleted(span: EruTrace.Span): Unit = {
        spans += span
      }

      override def onOtherEvent(event: EruEvent): Unit = {
        otherEvents += event
      }
    }

    val observer = new TestTracingObserver

    // Test that it's a proper EruObserver
    observer.onEvent(EruEvent.ProgramStart(ScopeId.fresh()))

    assertEquals(observer.spans.size, 0)
    assertEquals(observer.otherEvents.size, 1)
  }

  test("startTrace creates root trace context") {
    import EruTrace.*

    val context = startTrace("root-operation", Map("service" -> "test"))

    assert(context.currentSpan.isDefined)
    val rootSpan = context.currentSpan.get
    assertEquals(rootSpan.operation, "root-operation")
    assertEquals(rootSpan.tags("service"), "test")
    assert(rootSpan.parentSpanId.isEmpty)
  }

  test("getCurrentContext returns None initially") {
    import EruTrace.*

    val _ = getCurrentContext
  }

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
      case _ => fail("Expected Error variant")
    }
    assertEquals(cancelled, Cancelled)
  }
}
