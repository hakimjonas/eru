package net.ghoula.eru.trace

import net.ghoula.eru.*
import java.time.Duration

/** Test suite for the EruJfrEvents JFR integration system.
  *
  * Validates JFR event creation, emission, and JVM 25 feature detection. Tests work correctly on
  * both JVM < 25 (with graceful degradation) and JVM 25+ (with enhanced features).
  */
final class EruJfrEventsSpec extends munit.FunSuite {

  /** Validates that JVM version detection works correctly. */
  test("hasContextualSupport detects JVM version correctly") {
    val hasSupport = EruJfrEvents.hasContextualSupport
    val javaVersion = System.getProperty("java.version")
    val majorVersion = javaVersion.split("\\.")(0).toInt
    val expectedSupport = majorVersion >= 25

    assertEquals(hasSupport, expectedSupport)
  }

  /** Validates that EruEffectExecution events can be created and configured. */
  test("EruEffectExecution event creation") {
    val event = new EruJfrEvents.EruEffectExecution()

    event.traceId = "12345"
    event.spanId = "67890"
    event.operation = "test-operation"
    event.duration = Duration.ofMillis(100)
    event.status = "Success"
    event.fiberId = "fiber-123"

    assertEquals(event.traceId, "12345")
    assertEquals(event.spanId, "67890")
    assertEquals(event.operation, "test-operation")
    assertEquals(event.duration, Duration.ofMillis(100))
    assertEquals(event.status, "Success")
    assertEquals(event.fiberId, "fiber-123")
  }

  /** Validates that EruStructuredConcurrency events can be created and configured. */
  test("EruStructuredConcurrency event creation") {
    val event = new EruJfrEvents.EruStructuredConcurrency()

    event.traceId = "12345"
    event.parentSpanId = "67890"
    event.concurrencyOp = "fork"
    event.childCount = 2
    event.cancellationPolicy = "fail-fast"

    assertEquals(event.traceId, "12345")
    assertEquals(event.parentSpanId, "67890")
    assertEquals(event.concurrencyOp, "fork")
    assertEquals(event.childCount, 2)
    assertEquals(event.cancellationPolicy, "fail-fast")
  }

  /** Validates that EruResourceManagement events can be created and configured. */
  test("EruResourceManagement event creation") {
    val event = new EruJfrEvents.EruResourceManagement()

    event.traceId = "12345"
    event.spanId = "67890"
    event.resourceOp = "acquire"
    event.resourceType = "database-connection"
    event.resourceId = "conn-123"
    event.cleanupStatus = "success"

    assertEquals(event.traceId, "12345")
    assertEquals(event.spanId, "67890")
    assertEquals(event.resourceOp, "acquire")
    assertEquals(event.resourceType, "database-connection")
    assertEquals(event.resourceId, "conn-123")
    assertEquals(event.cleanupStatus, "success")
  }

  /** Validates that EruErrorHandling events can be created and configured. */
  test("EruErrorHandling event creation") {
    val event = new EruJfrEvents.EruErrorHandling()

    event.traceId = "12345"
    event.spanId = "67890"
    event.errorType = "TimeoutException"
    event.recoveryStrategy = "retry"
    event.attemptCount = 3
    event.recoverySuccess = true

    assertEquals(event.traceId, "12345")
    assertEquals(event.spanId, "67890")
    assertEquals(event.errorType, "TimeoutException")
    assertEquals(event.recoveryStrategy, "retry")
    assertEquals(event.attemptCount, 3)
    assertEquals(event.recoverySuccess, true)
  }

  /** Validates that EventEmitter.emitEffectExecution works without errors. */
  test("EventEmitter.emitEffectExecution executes without errors") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()

    EruJfrEvents.EventEmitter.emitEffectExecution(
      traceId = traceId,
      spanId = spanId,
      operation = "test-operation",
      duration = Duration.ofMillis(50),
      status = EruTrace.SpanStatus.Success,
      fiberId = fiberId
    )
  }

  /** Validates that EventEmitter.emitStructuredConcurrency works without errors. */
  test("EventEmitter.emitStructuredConcurrency executes without errors") {
    val traceId = EruTrace.TraceId.fresh()
    val parentSpanId = EruTrace.SpanId.fresh()

    EruJfrEvents.EventEmitter.emitStructuredConcurrency(
      traceId = traceId,
      parentSpanId = Some(parentSpanId),
      concurrencyOp = "zipPar",
      childCount = 2,
      cancellationPolicy = "structured"
    )
  }

  /** Validates that EventEmitter.emitResourceManagement works without errors. */
  test("EventEmitter.emitResourceManagement executes without errors") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()

    EruJfrEvents.EventEmitter.emitResourceManagement(
      traceId = traceId,
      spanId = spanId,
      resourceOp = "release",
      resourceType = "file-handle",
      resourceId = "file-123",
      cleanupStatus = "completed"
    )
  }

  /** Validates that EventEmitter.emitErrorHandling works without errors. */
  test("EventEmitter.emitErrorHandling executes without errors") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()

    EruJfrEvents.EventEmitter.emitErrorHandling(
      traceId = traceId,
      spanId = spanId,
      errorType = "NetworkException",
      recoveryStrategy = "circuit-breaker",
      attemptCount = 1,
      recoverySuccess = false
    )
  }

  /** Validates that span status conversion works correctly. */
  test("span status conversion in emitEffectExecution") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()

    val statuses = List(
      EruTrace.SpanStatus.InProgress,
      EruTrace.SpanStatus.Success,
      EruTrace.SpanStatus.Error("test error"),
      EruTrace.SpanStatus.Cancelled
    )

    statuses.foreach { status =>
      EruJfrEvents.EventEmitter.emitEffectExecution(
        traceId = traceId,
        spanId = spanId,
        operation = "status-test",
        duration = Duration.ofMillis(10),
        status = status,
        fiberId = fiberId
      )
    }
  }

  /** Validates that emitStructuredConcurrency handles None parent span correctly. */
  test("emitStructuredConcurrency with None parent span") {
    val traceId = EruTrace.TraceId.fresh()

    EruJfrEvents.EventEmitter.emitStructuredConcurrency(
      traceId = traceId,
      parentSpanId = None,
      concurrencyOp = "root-fork",
      childCount = 1,
      cancellationPolicy = "none"
    )
  }

  /** Validates that multiple events can be emitted in sequence. */
  test("multiple event emission sequence") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()

    EruJfrEvents.EventEmitter.emitStructuredConcurrency(
      traceId = traceId,
      parentSpanId = Some(spanId),
      concurrencyOp = "start",
      childCount = 1,
      cancellationPolicy = "structured"
    )

    EruJfrEvents.EventEmitter.emitResourceManagement(
      traceId = traceId,
      spanId = spanId,
      resourceOp = "acquire",
      resourceType = "connection",
      resourceId = "conn-456",
      cleanupStatus = "pending"
    )

    EruJfrEvents.EventEmitter.emitEffectExecution(
      traceId = traceId,
      spanId = spanId,
      operation = "business-logic",
      duration = Duration.ofMillis(200),
      status = EruTrace.SpanStatus.Success,
      fiberId = fiberId
    )

    EruJfrEvents.EventEmitter.emitResourceManagement(
      traceId = traceId,
      spanId = spanId,
      resourceOp = "release",
      resourceType = "connection",
      resourceId = "conn-456",
      cleanupStatus = "completed"
    )
  }

  /** Validates that events work correctly with very long operation names. */
  test("events handle long operation names") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()
    val longOperation = "very-long-operation-name-" + ("x" * 100)

    EruJfrEvents.EventEmitter.emitEffectExecution(
      traceId = traceId,
      spanId = spanId,
      operation = longOperation,
      duration = Duration.ofMillis(1),
      status = EruTrace.SpanStatus.Success,
      fiberId = fiberId
    )
  }

  /** Validates that events work correctly with zero and maximum durations. */
  test("events handle extreme duration values") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()

    EruJfrEvents.EventEmitter.emitEffectExecution(
      traceId = traceId,
      spanId = spanId,
      operation = "zero-duration",
      duration = Duration.ZERO,
      status = EruTrace.SpanStatus.Success,
      fiberId = fiberId
    )

    EruJfrEvents.EventEmitter.emitEffectExecution(
      traceId = traceId,
      spanId = spanId,
      operation = "long-duration",
      duration = Duration.ofHours(1),
      status = EruTrace.SpanStatus.Success,
      fiberId = fiberId
    )
  }

  /** Validates that event emission is performant and doesn't cause overhead when JFR is disabled. */
  test("event emission performance") {
    val traceId = EruTrace.TraceId.fresh()
    val spanId = EruTrace.SpanId.fresh()
    val fiberId = FiberId.fresh()

    val iterations = 1000
    val startTime = System.nanoTime()

    (1 to iterations).foreach { _ =>
      EruJfrEvents.EventEmitter.emitEffectExecution(
        traceId = traceId,
        spanId = spanId,
        operation = "performance-test",
        duration = Duration.ofNanos(1),
        status = EruTrace.SpanStatus.Success,
        fiberId = fiberId
      )
    }

    val duration = System.nanoTime() - startTime
    val averageNanos = duration / iterations

    assert(averageNanos < 1000000)
  }
}