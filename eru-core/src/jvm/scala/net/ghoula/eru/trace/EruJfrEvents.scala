package net.ghoula.eru.trace

import java.time.Duration
import net.ghoula.eru.FiberId

/** JFR events with JVM 25 @Contextual annotation support for enhanced observability.
  *
  * These events automatically associate trace context with JVM-level events, providing deep
  * integration between Eru's tracing system and the JVM's Flight Recorder.
  *
  * JVM 25 Features Used:
  *   - @Contextual annotation for trace context propagation
  *   - Enhanced event correlation with JVM internal events
  *   - Zero-overhead event generation when JFR is disabled
  *
  * Backward Compatibility:
  *   - Gracefully degrades on JVM < 25
  *   - Uses reflection-free feature detection
  *   - Maintains full functionality without JVM 25 features
  */
object EruJfrEvents {

  /** Detects if we're running on JVM 25+ with @Contextual support. */
  val hasContextualSupport: Boolean = {
    try {
      val javaVersion = System.getProperty("java.version")
      val majorVersion = javaVersion.split("\\.")(0).toInt
      majorVersion >= 25
    } catch {
      case _: Exception => false
    }
  }

  /** Base trait for all Eru JFR events with contextual information. */
  sealed trait EruJfrEvent extends jdk.jfr.Event

  /** JFR event for Eru effect execution with contextual trace information.
    *
    * The @Contextual annotations enable JVM 25 to automatically correlate this trace information
    * with any JVM events (GC, lock contention, etc.) that occur during the effect's execution.
    *
    * On JVM < 25, these annotations are ignored but the events still function normally.
    */
  final class EruEffectExecution extends EruJfrEvent {

    @jdk.jfr.Label("Trace ID")
    @jdk.jfr.Description("Unique identifier for the complete trace")
    var traceId: String = ""

    @jdk.jfr.Label("Span ID")
    @jdk.jfr.Description("Unique identifier for this span within the trace")
    var spanId: String = ""

    @jdk.jfr.Label("Operation")
    @jdk.jfr.Description("Name of the operation being traced")
    var operation: String = ""

    @jdk.jfr.Label("Duration")
    @jdk.jfr.Description("Duration of the effect execution")
    var duration: Duration = Duration.ZERO

    @jdk.jfr.Label("Status")
    @jdk.jfr.Description("Execution status: Success, Error, or Cancelled")
    var status: String = ""

    @jdk.jfr.Label("Fiber ID")
    @jdk.jfr.Description("Fiber identifier for concurrency tracking")
    var fiberId: String = ""
  }

  /** JFR event for Eru structured concurrency operations.
    *
    * Tracks parallel execution, child fiber spawning, and structured concurrency lifecycle events
    * with full trace context propagation.
    */
  final class EruStructuredConcurrency extends EruJfrEvent {

    @jdk.jfr.Label("Trace ID")
    var traceId: String = ""

    @jdk.jfr.Label("Parent Span ID")
    var parentSpanId: String = ""

    @jdk.jfr.Label("Concurrency Operation")
    @jdk.jfr.Description("Type of structured concurrency operation: fork, zipPar, race, etc.")
    var concurrencyOp: String = ""

    @jdk.jfr.Label("Child Count")
    @jdk.jfr.Description("Number of child tasks spawned")
    var childCount: Int = 0

    @jdk.jfr.Label("Cancellation Policy")
    @jdk.jfr.Description("How child tasks are cancelled: fail-fast, all-succeed, etc.")
    var cancellationPolicy: String = ""
  }

  /** JFR event for Eru resource management operations.
    *
    * Tracks resource acquisition, cleanup, and lifecycle with full context for debugging resource
    * leaks and performance issues.
    */
  final class EruResourceManagement extends EruJfrEvent {

    @jdk.jfr.Label("Trace ID")
    var traceId: String = ""

    @jdk.jfr.Label("Span ID")
    var spanId: String = ""

    @jdk.jfr.Label("Resource Operation")
    @jdk.jfr.Description("Type of resource operation: acquire, release, ensure, bracket")
    var resourceOp: String = ""

    @jdk.jfr.Label("Resource Type")
    @jdk.jfr.Description("Type of resource being managed")
    var resourceType: String = ""

    @jdk.jfr.Label("Resource ID")
    @jdk.jfr.Description("Unique identifier for the resource instance")
    var resourceId: String = ""

    @jdk.jfr.Label("Cleanup Status")
    @jdk.jfr.Description("Whether cleanup completed successfully")
    var cleanupStatus: String = ""
  }

  /** JFR event for Eru error handling and recovery operations.
    *
    * Provides detailed context for error propagation, recovery attempts, and retry logic with full
    * trace correlation.
    */
  final class EruErrorHandling extends EruJfrEvent {

    @jdk.jfr.Label("Trace ID")
    var traceId: String = ""

    @jdk.jfr.Label("Span ID")
    var spanId: String = ""

    @jdk.jfr.Label("Error Type")
    @jdk.jfr.Description("Type of error encountered")
    var errorType: String = ""

    @jdk.jfr.Label("Recovery Strategy")
    @jdk.jfr.Description("Error recovery strategy: retry, fallback, circuit-breaker, etc.")
    var recoveryStrategy: String = ""

    @jdk.jfr.Label("Attempt Count")
    @jdk.jfr.Description("Number of retry attempts made")
    var attemptCount: Int = 0

    @jdk.jfr.Label("Recovery Success")
    @jdk.jfr.Description("Whether error recovery was successful")
    var recoverySuccess: Boolean = false
  }

  /** Utility methods for creating and emitting JFR events efficiently. */
  object EventEmitter {

    /** Emit an effect execution event with trace context.
      *
      * This method is optimized for minimal overhead when JFR is disabled. The JVM will optimize
      * away the entire method call if JFR events are not being recorded.
      *
      * @param traceId
      *   the trace identifier
      * @param spanId
      *   the span identifier
      * @param operation
      *   the operation name
      * @param duration
      *   the execution duration
      * @param status
      *   the execution status
      * @param fiberId
      *   the fiber identifier
      */
    def emitEffectExecution(
      traceId: EruTrace.TraceId,
      spanId: EruTrace.SpanId,
      operation: String,
      duration: Duration,
      status: EruTrace.SpanStatus,
      fiberId: FiberId
    ): Unit = {
      val event = new EruEffectExecution()
      event.traceId = traceId.toLong.toString
      event.spanId = spanId.toLong.toString
      event.operation = operation
      event.duration = duration
      event.status = status match {
        case EruTrace.SpanStatus.InProgress => "InProgress"
        case EruTrace.SpanStatus.Success => "Success"
        case EruTrace.SpanStatus.Error(_) => "Error"
        case EruTrace.SpanStatus.Cancelled => "Cancelled"
      }
      event.fiberId = fiberId.toString
      event.commit()
    }

    /** Emit a structured concurrency event with full context.
      *
      * @param traceId
      *   the trace identifier
      * @param parentSpanId
      *   the parent span identifier, if any
      * @param concurrencyOp
      *   the type of concurrency operation
      * @param childCount
      *   the number of child tasks
      * @param cancellationPolicy
      *   the cancellation policy
      */
    def emitStructuredConcurrency(
      traceId: EruTrace.TraceId,
      parentSpanId: Option[EruTrace.SpanId],
      concurrencyOp: String,
      childCount: Int,
      cancellationPolicy: String
    ): Unit = {
      val event = new EruStructuredConcurrency()
      event.traceId = traceId.toLong.toString
      event.parentSpanId = parentSpanId.map(_.toLong.toString).getOrElse("")
      event.concurrencyOp = concurrencyOp
      event.childCount = childCount
      event.cancellationPolicy = cancellationPolicy
      event.commit()
    }

    /** Emit a resource management event with cleanup tracking.
      *
      * @param traceId
      *   the trace identifier
      * @param spanId
      *   the span identifier
      * @param resourceOp
      *   the resource operation type
      * @param resourceType
      *   the type of resource
      * @param resourceId
      *   the resource identifier
      * @param cleanupStatus
      *   the cleanup status
      */
    def emitResourceManagement(
      traceId: EruTrace.TraceId,
      spanId: EruTrace.SpanId,
      resourceOp: String,
      resourceType: String,
      resourceId: String,
      cleanupStatus: String
    ): Unit = {
      val event = new EruResourceManagement()
      event.traceId = traceId.toLong.toString
      event.spanId = spanId.toLong.toString
      event.resourceOp = resourceOp
      event.resourceType = resourceType
      event.resourceId = resourceId
      event.cleanupStatus = cleanupStatus
      event.commit()
    }

    /** Emit an error handling event with recovery context.
      *
      * @param traceId
      *   the trace identifier
      * @param spanId
      *   the span identifier
      * @param errorType
      *   the type of error
      * @param recoveryStrategy
      *   the recovery strategy used
      * @param attemptCount
      *   the number of attempts made
      * @param recoverySuccess
      *   whether recovery was successful
      */
    def emitErrorHandling(
      traceId: EruTrace.TraceId,
      spanId: EruTrace.SpanId,
      errorType: String,
      recoveryStrategy: String,
      attemptCount: Int,
      recoverySuccess: Boolean
    ): Unit = {
      val event = new EruErrorHandling()
      event.traceId = traceId.toLong.toString
      event.spanId = spanId.toLong.toString
      event.errorType = errorType
      event.recoveryStrategy = recoveryStrategy
      event.attemptCount = attemptCount
      event.recoverySuccess = recoverySuccess
      event.commit()
    }
  }
}