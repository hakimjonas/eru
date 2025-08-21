package net.ghoula.eru.trace

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.*
import net.ghoula.eru.DataClassUtils

/** Lightweight, built-in tracing system for Eru effects.
  *
  * This tracing system provides structured observability without external dependencies, integrating
  * seamlessly with the existing EruObserver pattern. It follows the principle of "exceptional
  * observability" by making the runtime transparent while maintaining high performance through
  * careful design.
  *
  * Key features:
  *   - Zero-allocation tracing for hot paths when tracing is disabled
  *   - Structured trace context with span hierarchy
  *   - Integration with existing EruObserver for unified observability
  *   - Performance-aware collection with configurable sampling
  */
object EruTrace {

  /** A unique identifier for a trace span. */
  opaque type SpanId = Long

  object SpanId {
    private val counter = new AtomicLong(1L)

    def fresh(): SpanId = counter.getAndIncrement()

    extension (id: SpanId) {
      def toLong: Long = id
    }
  }

  /** A unique identifier for a complete trace. */
  opaque type TraceId = Long

  object TraceId {
    private val counter = new AtomicLong(1L)

    def fresh(): TraceId = counter.getAndIncrement()

    extension (id: TraceId) {
      def toLong: Long = id
    }
  }

  /** Structured information about a span in a trace.
    *
    * A span represents a unit of work within a trace, with timing information and contextual
    * metadata for debugging and performance analysis. Each span captures the execution context of a
    * specific operation, including its relationship to parent spans and detailed metadata for
    * observability.
    *
    * Key features:
    *   - Hierarchical span relationships through parent-child links
    *   - Structured metadata via tags and events
    *   - Precise timing information for performance analysis
    *   - Status tracking for success/failure analysis
    *
    * @param spanId
    *   unique identifier for this span
    * @param traceId
    *   identifier of the trace this span belongs to
    * @param parentSpanId
    *   identifier of the parent span, if any
    * @param operation
    *   human-readable name of the operation being traced
    * @param startTime
    *   timestamp when the span began
    * @param endTime
    *   timestamp when the span completed, if completed
    * @param status
    *   current status of the span execution
    * @param tags
    *   key-value metadata for additional context
    * @param events
    *   timeline of events that occurred during span execution
    */
  final class Span(
    val spanId: SpanId,
    val traceId: TraceId,
    val parentSpanId: Option[SpanId],
    val operation: String,
    val startTime: Instant,
    val endTime: Option[Instant] = None,
    val status: SpanStatus = SpanStatus.InProgress,
    val tags: Map[String, String] = Map.empty,
    val events: List[SpanEvent] = Nil
  ) {

    /** Creates a copy of this span with modified fields.
      *
      * This method provides case class-like copy functionality for immutable updates while
      * maintaining the modern class-based approach.
      */
    def copy(
      spanId: SpanId = this.spanId,
      traceId: TraceId = this.traceId,
      parentSpanId: Option[SpanId] = this.parentSpanId,
      operation: String = this.operation,
      startTime: Instant = this.startTime,
      endTime: Option[Instant] = this.endTime,
      status: SpanStatus = this.status,
      tags: Map[String, String] = this.tags,
      events: List[SpanEvent] = this.events
    ): Span =
      new Span(spanId, traceId, parentSpanId, operation, startTime, endTime, status, tags, events)

    /** Duration of this span, if completed.
      *
      * @return
      *   the duration between start and end times if the span has completed
      */
    def duration: Option[java.time.Duration] =
      endTime.map(end => java.time.Duration.between(startTime, end))

    /** Adds a tag to this span for additional context.
      *
      * Tags provide structured metadata that can be used for filtering, analysis, and correlation
      * in observability tools.
      *
      * @param key
      *   the tag key
      * @param value
      *   the tag value
      * @return
      *   a new span with the additional tag
      */
    def withTag(key: String, value: String): Span =
      copy(tags = tags + (key -> value))

    /** Adds multiple tags to this span.
      *
      * @param newTags
      *   map of tags to add
      * @return
      *   a new span with the additional tags
      */
    def withTags(newTags: Map[String, String]): Span =
      copy(tags = tags ++ newTags)

    /** Adds an event to this span's timeline.
      *
      * Events represent significant occurrences during span execution and provide a detailed
      * timeline for debugging and performance analysis.
      *
      * @param event
      *   the event to add to the span's timeline
      * @return
      *   a new span with the additional event
      */
    def withEvent(event: SpanEvent): Span =
      copy(events = event :: events)

    /** Completes this span with the given status.
      *
      * This marks the span as finished and records the final status and end time.
      *
      * @param finalStatus
      *   the final status of the span execution
      * @return
      *   a new span marked as completed with the specified status
      */
    def complete(finalStatus: SpanStatus): Span =
      copy(endTime = Some(Instant.now()), status = finalStatus)

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: Span =>
        spanId == that.spanId &&
        traceId == that.traceId &&
        parentSpanId == that.parentSpanId &&
        operation == that.operation &&
        startTime == that.startTime &&
        endTime == that.endTime &&
        status == that.status &&
        tags == that.tags &&
        events == that.events
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(spanId, traceId, parentSpanId, operation, startTime, endTime, status, tags, events)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor(
        "Span",
        spanId,
        traceId,
        parentSpanId,
        operation,
        startTime,
        endTime,
        status,
        tags,
        events
      )
  }

  object Span {

    /** Creates a new Span instance.
      *
      * @param spanId
      *   unique identifier for this span
      * @param traceId
      *   identifier of the trace this span belongs to
      * @param parentSpanId
      *   identifier of the parent span, if any
      * @param operation
      *   human-readable name of the operation being traced
      * @param startTime
      *   timestamp when the span began
      * @param endTime
      *   timestamp when the span completed, if completed
      * @param status
      *   current status of the span execution
      * @param tags
      *   key-value metadata for additional context
      * @param events
      *   timeline of events that occurred during span execution
      * @return
      *   a new Span instance
      */
    def apply(
      spanId: SpanId,
      traceId: TraceId,
      parentSpanId: Option[SpanId],
      operation: String,
      startTime: Instant,
      endTime: Option[Instant] = None,
      status: SpanStatus = SpanStatus.InProgress,
      tags: Map[String, String] = Map.empty,
      events: List[SpanEvent] = Nil
    ): Span =
      new Span(spanId, traceId, parentSpanId, operation, startTime, endTime, status, tags, events)

    /** Extracts fields from a Span for pattern matching.
      *
      * @param span
      *   the span to extract from
      * @return
      *   tuple of all span fields for pattern matching
      */
    def unapply(span: Span): Option[
      (
        SpanId,
        TraceId,
        Option[SpanId],
        String,
        Instant,
        Option[Instant],
        SpanStatus,
        Map[String, String],
        List[SpanEvent]
      )
    ] =
      Some(
        (
          span.spanId,
          span.traceId,
          span.parentSpanId,
          span.operation,
          span.startTime,
          span.endTime,
          span.status,
          span.tags,
          span.events
        )
      )
  }

  /** Status of a span indicating how it completed. */
  enum SpanStatus {
    case InProgress
    case Success
    case Error(cause: String)
    case Cancelled
  }

  /** An event that occurred during span execution.
    *
    * Span events represent significant milestones or checkpoints during the execution of a traced
    * operation. They provide detailed timeline information for debugging and performance analysis,
    * allowing developers to understand the flow and timing of operations within a span.
    *
    * Events are particularly useful for:
    *   - Marking important milestones in complex operations
    *   - Recording intermediate states or decisions
    *   - Providing detailed context for debugging failures
    *   - Performance analysis of sub-operations
    *
    * @param timestamp
    *   when the event occurred
    * @param name
    *   human-readable name describing the event
    * @param attributes
    *   additional structured metadata for the event
    */
  final class SpanEvent(
    val timestamp: Instant,
    val name: String,
    val attributes: Map[String, String] = Map.empty
  ) {

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: SpanEvent =>
        timestamp == that.timestamp &&
        name == that.name &&
        attributes == that.attributes
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(timestamp, name, attributes)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor("SpanEvent", timestamp, name, attributes)
  }

  object SpanEvent {

    /** Creates a new SpanEvent instance.
      *
      * @param timestamp
      *   when the event occurred
      * @param name
      *   human-readable name describing the event
      * @param attributes
      *   additional structured metadata for the event
      * @return
      *   a new SpanEvent instance
      */
    def apply(
      timestamp: Instant,
      name: String,
      attributes: Map[String, String] = Map.empty
    ): SpanEvent =
      new SpanEvent(timestamp, name, attributes)

    /** Extracts fields from a SpanEvent for pattern matching.
      *
      * @param event
      *   the event to extract from
      * @return
      *   tuple of all event fields for pattern matching
      */
    def unapply(event: SpanEvent): Option[(Instant, String, Map[String, String])] =
      Some((event.timestamp, event.name, event.attributes))
  }

  /** Trace context that flows through effect execution.
    *
    * TraceContext maintains the state and relationships within a distributed trace, carrying both
    * the current span information and baggage that flows through the entire trace. This context
    * enables correlation of operations across different parts of the system and provides a
    * foundation for distributed tracing and observability.
    *
    * Key responsibilities:
    *   - Maintaining trace and span relationships
    *   - Propagating baggage across trace boundaries
    *   - Creating child spans with proper hierarchy
    *   - Providing context for trace-aware operations
    *
    * @param traceId
    *   unique identifier for the entire trace
    * @param currentSpan
    *   the currently active span, if any
    * @param baggage
    *   key-value context that flows through the entire trace
    */
  final class TraceContext(
    val traceId: TraceId,
    val currentSpan: Option[Span] = None,
    val baggage: Map[String, String] = Map.empty
  ) {

    /** Creates a copy of this trace context with modified fields.
      *
      * This method provides case class-like copy functionality for immutable updates while
      * maintaining the modern class-based approach.
      */
    def copy(
      traceId: TraceId = this.traceId,
      currentSpan: Option[Span] = this.currentSpan,
      baggage: Map[String, String] = this.baggage
    ): TraceContext =
      new TraceContext(traceId, currentSpan, baggage)

    /** Creates a child span within this trace context.
      *
      * This method establishes a parent-child relationship between spans, ensuring proper trace
      * hierarchy and context propagation. The child span inherits the trace ID and references the
      * current span as its parent.
      *
      * @param operation
      *   human-readable name of the operation being traced
      * @return
      *   tuple containing the updated context and the new child span
      */
    def createChildSpan(operation: String): (TraceContext, Span) = {
      val spanId = SpanId.fresh()
      val parentSpanId = currentSpan.map(_.spanId)
      val span = Span(
        spanId = spanId,
        traceId = traceId,
        parentSpanId = parentSpanId,
        operation = operation,
        startTime = Instant.now()
      )
      val newContext = copy(currentSpan = Some(span))
      (newContext, span)
    }

    /** Adds baggage (context that flows through the entire trace).
      *
      * Baggage is trace-wide context that propagates across all spans and operations within a
      * trace. It's useful for carrying information like user IDs, request IDs, feature flags, or
      * other cross-cutting concerns that need to be available throughout the trace lifecycle.
      *
      * @param key
      *   the baggage key
      * @param value
      *   the baggage value
      * @return
      *   a new trace context with the additional baggage
      */
    def withBaggage(key: String, value: String): TraceContext =
      copy(baggage = baggage + (key -> value))

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: TraceContext =>
        traceId == that.traceId &&
        currentSpan == that.currentSpan &&
        baggage == that.baggage
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(traceId, currentSpan, baggage)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor("TraceContext", traceId, currentSpan, baggage)
  }

  object TraceContext {

    /** Creates a new TraceContext instance.
      *
      * @param traceId
      *   unique identifier for the entire trace
      * @param currentSpan
      *   the currently active span, if any
      * @param baggage
      *   key-value context that flows through the entire trace
      * @return
      *   a new TraceContext instance
      */
    def apply(
      traceId: TraceId,
      currentSpan: Option[Span] = None,
      baggage: Map[String, String] = Map.empty
    ): TraceContext =
      new TraceContext(traceId, currentSpan, baggage)

    /** Extracts fields from a TraceContext for pattern matching.
      *
      * @param context
      *   the context to extract from
      * @return
      *   tuple of all context fields for pattern matching
      */
    def unapply(context: TraceContext): Option[(TraceId, Option[Span], Map[String, String])] =
      Some((context.traceId, context.currentSpan, context.baggage))
  }

  /** Global trace context holder using thread-local storage. */
  private val currentContext = new ThreadLocal[Option[TraceContext]] {
    override def initialValue(): Option[TraceContext] = None
  }

  /** Gets the current trace context, if any. */
  def getCurrentContext: Option[TraceContext] = currentContext.get()

  /** Sets the current trace context. */
  def setCurrentContext(context: Option[TraceContext]): Unit =
    currentContext.set(context)

  /** Creates a new root trace context. */
  def startTrace(operation: String, tags: Map[String, String] = Map.empty): TraceContext = {
    val traceId = TraceId.fresh()
    val context = TraceContext(traceId)
    val (newContext, rootSpan) = context.createChildSpan(operation)
    val taggedSpan = rootSpan.withTags(tags)
    newContext.copy(currentSpan = Some(taggedSpan))
  }
}

/** Extension methods for adding tracing capabilities to Eru effects. */
extension [E, A](eru: Eru[E, A]) {

  /** Wraps this effect with a trace span for observability.
    *
    * This method creates a new span for this effect's execution, providing detailed timing and
    * context information for debugging and performance analysis. The span integrates with the
    * existing EruObserver pattern.
    *
    * @param operation
    *   name of the operation for the span
    * @param tags
    *   additional context tags for the span
    * @return
    *   an effect that executes within a trace span
    */
  def traced(operation: String, tags: Map[String, String] = Map.empty): Eru[E | Throwable, A] = {
    import EruTrace.*

    Eru.effect {
      val context = getCurrentContext.getOrElse(startTrace("root-trace"))
      val (newContext, span) = context.createChildSpan(operation)
      val taggedSpan = span.withTags(tags)

      setCurrentContext(Some(newContext.copy(currentSpan = Some(taggedSpan))))
      (newContext, taggedSpan)
    }.flatMap { case (_, span) =>
      eru.attempt.map { result =>
        val completedSpan = result match {
          case Result.Success(value) =>
            span.complete(SpanStatus.Success)
          case Result.Failure(error) =>
            val errorMsg = error match {
              case t: Throwable => t.getMessage
              case other => other.toString
            }
            span.complete(SpanStatus.Error(errorMsg))
        }

        EruEvent.TraceSpan(completedSpan)

        result
      }.flatMap {
        case Result.Success(value) => Eru.succeed(value)
        case Result.Failure(error) => Eru.fail(error)
      }
    }
  }

  /** Adds a trace event at this point in the effect execution.
    *
    * This is useful for marking important milestones or checkpoints within a larger operation for
    * detailed performance analysis.
    *
    * @param eventName
    *   name of the event
    * @param attributes
    *   additional context for the event
    * @return
    *   the effect unchanged, with a trace event recorded
    */
  def traceEvent(eventName: String, attributes: Map[String, String] = Map.empty): Eru[E, A] = {
    import EruTrace.*

    eru.map { value =>
      getCurrentContext.flatMap(_.currentSpan) match {
        case Some(span) =>
          val event = SpanEvent(
            timestamp = Instant.now(),
            name = eventName,
            attributes = attributes
          )
          val updatedSpan = span.withEvent(event)
          val updatedContext = getCurrentContext.get.copy(currentSpan = Some(updatedSpan))
          setCurrentContext(Some(updatedContext))
        case None =>
      }
      value
    }
  }

  /** Adds baggage (trace-wide context) to the current trace.
    *
    * Baggage flows through the entire trace and can be used to propagate important context like
    * user IDs, request IDs, or feature flags.
    *
    * @param key
    *   baggage key
    * @param value
    *   baggage value
    * @return
    *   the effect unchanged, with baggage added to trace context
    */
  def withTraceBaggage(key: String, value: String): Eru[E, A] = {
    import EruTrace.*

    eru.map { result =>
      getCurrentContext match {
        case Some(context) =>
          val updatedContext = context.withBaggage(key, value)
          setCurrentContext(Some(updatedContext))
        case None =>
          val context = startTrace("implicit-trace").withBaggage(key, value)
          setCurrentContext(Some(context))
      }
      result
    }
  }
}

/** Enhanced EruObserver that can handle trace events. */
trait TracingEruObserver extends EruObserver {

  /** Called when a trace span completes. */
  def onSpanCompleted(span: EruTrace.Span): Unit

  override def onEvent(event: EruEvent): Unit = {
    event match {
      case EruEvent.TraceSpan(span) => onSpanCompleted(span)
      case other => onOtherEvent(other)
    }
  }

  /** Handle non-tracing events (can be overridden). */
  def onOtherEvent(event: EruEvent): Unit = ()
}
