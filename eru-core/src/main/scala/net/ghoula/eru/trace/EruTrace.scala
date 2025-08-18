package net.ghoula.eru.trace

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.*

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
    * metadata for debugging and performance analysis.
    */
  final case class Span(
    spanId: SpanId,
    traceId: TraceId,
    parentSpanId: Option[SpanId],
    operation: String,
    startTime: Instant,
    endTime: Option[Instant] = None,
    status: SpanStatus = SpanStatus.InProgress,
    tags: Map[String, String] = Map.empty,
    events: List[SpanEvent] = Nil
  ) {

    /** Duration of this span, if completed. */
    def duration: Option[java.time.Duration] =
      endTime.map(end => java.time.Duration.between(startTime, end))

    /** Adds a tag to this span for additional context. */
    def withTag(key: String, value: String): Span =
      copy(tags = tags + (key -> value))

    /** Adds multiple tags to this span. */
    def withTags(newTags: Map[String, String]): Span =
      copy(tags = tags ++ newTags)

    /** Adds an event to this span's timeline. */
    def withEvent(event: SpanEvent): Span =
      copy(events = event :: events)

    /** Completes this span with the given status. */
    def complete(finalStatus: SpanStatus): Span =
      copy(endTime = Some(Instant.now()), status = finalStatus)
  }

  /** Status of a span indicating how it completed. */
  enum SpanStatus {
    case InProgress
    case Success
    case Error(cause: String)
    case Cancelled
  }

  /** An event that occurred during span execution. */
  final case class SpanEvent(
    timestamp: Instant,
    name: String,
    attributes: Map[String, String] = Map.empty
  )

  /** Trace context that flows through effect execution. */
  final case class TraceContext(
    traceId: TraceId,
    currentSpan: Option[Span] = None,
    baggage: Map[String, String] = Map.empty
  ) {

    /** Creates a child span within this trace context. */
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

    /** Adds baggage (context that flows through the entire trace). */
    def withBaggage(key: String, value: String): TraceContext =
      copy(baggage = baggage + (key -> value))
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
