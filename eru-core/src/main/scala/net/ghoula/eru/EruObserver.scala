package net.ghoula.eru

/** Observer API for receiving structured execution events during program runs.
  *
  * Attach an observer via `unsafeRunSyncWith` to receive events such as `ProgramStart`,
  * `ProgramEnd`, and `Step`. Fiber lifecycle events are also emitted when fiber operations are
  * used.
  */
object EruObserver {

  /** An observer that discards all events.
    */
  def noop: EruObserver = new EruObserver { def onEvent(event: EruEvent): Unit = () }

  /** An observer that prints events to standard output.
    */
  def console: EruObserver = new EruObserver {
    def onEvent(event: EruEvent): Unit = {
      System.out.println(event.toString)
    }
  }

  /** Unique identifier for a program execution scope.
    *
    * Generated using a monotonic counter to ensure uniqueness within the process lifetime.
    *
    * @example
    *   {{{
    * val scopeId = ScopeId.fresh()
    * logger.info(s"Starting execution with scope $scopeId")
    *   }}}
    */
  opaque type ScopeId = Long

  /** Companion object for ScopeId providing unique identifier generation.
    *
    * Uses a process-unique starting point to avoid conflicts between multiple Eru instances. The ID
    * generation combines process ID and nano time with atomic increment for uniqueness.
    */
  object ScopeId {
    private val processUniqueStart = {
      val processId = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.hashCode & 0xffffL
      val nanoTime = (System.nanoTime() >> 24) & 0xffffffffffffL
      (processId.toLong << 48) | (nanoTime & 0xffffL) | 0x3000L
    }
    private val next = new java.util.concurrent.atomic.AtomicLong(processUniqueStart)

    /** Creates a new unique scope identifier.
      *
      * @return
      *   a new ScopeId
      */
    def fresh(): ScopeId = next.getAndIncrement()
  }

  /** The outcome of program execution.
    *
    * Three possible outcomes:
    *   - Success: Normal completion with a value
    *   - TypedFailure: Expected domain error
    *   - Defect: Unexpected system failure
    *
    * @example
    *   {{{
    * outcome match {
    *   case Outcome.Success =>
    *     metrics.incrementCounter("program.success")
    *   case Outcome.TypedFailure(error) =>
    *     logger.info(s"Program failed with domain error: $error")
    *     metrics.incrementCounter("program.typed_failure")
    *   case Outcome.Defect(throwable) =>
    *     logger.error("Unexpected program defect", throwable)
    *     metrics.incrementCounter("program.defect")
    *     alerting.sendAlert("Critical defect in program execution", throwable)
    * }
    *   }}}
    */
  enum Outcome {

    /** Represents successful program completion.
      *
      * This outcome indicates that the program executed successfully and produced a value. The
      * actual value is not captured in the outcome as observers typically focus on execution
      * patterns rather than program results.
      */
    case Success

    /** Represents program failure with a typed, domain-specific error.
      *
      * This outcome indicates that the program failed through Eru's typed error channel, which
      * represents expected domain errors that are part of normal business logic. These errors are
      * typically recoverable and should be handled as normal program flow.
      *
      * @param error
      *   the typed error value that caused the program to fail
      */
    case TypedFailure(error: Any)

    /** Represents program failure due to an unexpected system error (defect).
      *
      * This outcome indicates that the program failed due to an unexpected Throwable, which
      * represents a programming error or system failure. Defects typically indicate bugs in the
      * program logic and require immediate attention.
      *
      * @param throwable
      *   the unexpected Throwable that caused the program to fail
      */
    case Defect(throwable: Throwable)
  }

  /** Structured events emitted by Eru's observer-aware interpreter.
    *
    * EruEvent provides a comprehensive event system that captures all significant moments in
    * program execution, from high-level program lifecycle to detailed fiber management and tracing
    * information. The event system is designed to support both the current synchronous runtime and
    * the future asynchronous fiber-based runtime.
    *
    * Events are categorized into three main types:
    *   - '''Program Events:''' Track the lifecycle of entire program executions
    *   - '''Fiber Events:''' Monitor individual fiber creation, completion, and interruption
    *   - '''Tracing Events:''' Provide detailed tracing information for performance analysis
    *
    * Each event carries contextual information that enables correlation and analysis across
    * different execution scopes. The structured nature of events enables rich observability without
    * sacrificing performance or type safety.
    *
    * @example
    *   {{{
    * def handleEvent(event: EruEvent): Unit = event match {
    *   case ProgramStart(scopeId) =>
    *     startTimer(scopeId)
    *     logger.info(s"Program execution started: $scopeId")
    *
    *   case ProgramEnd(scopeId, outcome) =>
    *     val duration = stopTimer(scopeId)
    *     metrics.recordDuration("program.execution.time", duration)
    *     outcome match {
    *       case Outcome.Success =>
    *         logger.info(s"Program $scopeId completed successfully")
    *       case Outcome.TypedFailure(error) =>
    *         logger.warn(s"Program $scopeId failed: $error")
    *       case Outcome.Defect(throwable) =>
    *         logger.error(s"Program $scopeId defect", throwable)
    *     }
    *
    *   case Step(scopeId, label) =>
    *     logger.debug(s"Step '$label' in scope $scopeId")
    *
    *   case FiberStarted(fiberId) =>
    *     fiberRegistry.register(fiberId)
    *
    *   case FiberCompleted(fiberId, exit) =>
    *     fiberRegistry.complete(fiberId, exit)
    *
    *   case FiberInterrupted(fiberId, cause) =>
    *     fiberRegistry.interrupt(fiberId, cause)
    *
    *   case FiberForked(parentId, childId) =>
    *     fiberRegistry.linkChild(parentId, childId)
    *     logger.debug(s"Fiber $childId forked by parent $parentId")
    *
    *   case StructuredCleanupStarted(fiberId, childCount) =>
    *     logger.info(s"Fiber $fiberId starting structured cleanup of $childCount children")
    *     metrics.recordGauge("structured_concurrency.active_cleanups", 1)
    *
    *   case StructuredCleanupCompleted(fiberId, interruptedCount, completedCount) =>
    *     logger.info(s"Fiber $fiberId completed cleanup: interrupted=$interruptedCount, completed=$completedCount")
    *     metrics.recordGauge("structured_concurrency.active_cleanups", -1)
    *
    *   case ChildInterruptionRequested(parentId, childId, cause, wasRunning) =>
    *     if (wasRunning) {
    *       logger.debug(s"Parent $parentId interrupting running child $childId: $cause")
    *       metrics.incrementCounter("structured_concurrency.child_interruptions")
    *     } else {
    *       logger.debug(s"Parent $parentId skipped interruption of completed child $childId")
    *       metrics.incrementCounter("structured_concurrency.child_already_completed")
    *     }
    *
    *   case TraceSpan(span) =>
    *     traceCollector.collect(span)
    * }
    *   }}}
    */
  enum EruEvent {

    /** Signals the start of a new program execution.
      *
      * This event is emitted when a new Eru program begins execution, typically at the entry point
      * of `unsafeRunSyncWith`. It marks the beginning of an execution scope and provides the
      * ScopeId for correlating subsequent events.
      *
      * @param scopeId
      *   the unique identifier for this program execution scope
      */
    case ProgramStart(scopeId: ScopeId)

    /** Signals the completion of a program execution with its final outcome.
      *
      * This event is emitted when an Eru program completes execution, regardless of whether it
      * succeeded or failed. The outcome provides structured information about how the program
      * terminated, enabling appropriate handling strategies.
      *
      * @param scopeId
      *   the unique identifier for this program execution scope
      * @param outcome
      *   the final outcome of the program execution
      */
    case ProgramEnd(scopeId: ScopeId, outcome: Outcome)

    /** Signals an intermediate step or checkpoint during program execution.
      *
      * This event provides visibility into the internal execution flow of Eru programs, enabling
      * detailed tracing and debugging. Steps are typically emitted at significant points during
      * effect interpretation. This event is intended for low-volume, human-readable debugging
      * traces and should not be used for high-frequency, performance-critical metrics.
      *
      * @param scopeId
      *   the unique identifier for this program execution scope
      * @param label
      *   human-readable description of the execution step
      */
    case Step(scopeId: ScopeId, label: String)

    /** Signals the creation and start of a new fiber.
      *
      * This event is emitted when a new fiber is created. It enables tracking of concurrent
      * execution and fiber lifecycle management.
      *
      * @param fiberId
      *   the unique identifier for the started fiber
      */
    case FiberStarted(fiberId: FiberId)

    /** Signals the completion of a fiber with its exit outcome.
      *
      * This event is emitted when a fiber completes execution, providing information about how the
      * fiber terminated. The exit outcome includes success values, typed errors, defects, and
      * interruption information.
      *
      * @param fiberId
      *   the unique identifier for the completed fiber
      * @param exit
      *   the structured exit outcome of the fiber execution
      */
    case FiberCompleted(fiberId: FiberId, exit: Exit[Any, Any])

    /** Signals the interruption of a fiber with the interruption cause.
      *
      * This event is emitted when a fiber is cooperatively interrupted, providing information about
      * why the interruption occurred. This enables proper handling of cancellation scenarios and
      * resource cleanup.
      *
      * @param fiberId
      *   the unique identifier for the interrupted fiber
      * @param cause
      *   the structured cause of the fiber interruption
      */
    case FiberInterrupted(fiberId: FiberId, cause: InterruptCause)

    /** Signals the establishment of a parent-child fiber relationship.
      *
      * This event is emitted when a fiber is forked within another fiber's context, establishing a
      * structured concurrency hierarchy. This enables tracking of parent-child relationships and
      * understanding the scope boundaries for structured cleanup.
      *
      * @param parentId
      *   the unique identifier of the parent fiber
      * @param childId
      *   the unique identifier of the child fiber that was forked
      */
    case FiberForked(parentId: FiberId, childId: FiberId)

    /** Signals the beginning of structured cleanup for a fiber's children.
      *
      * This event is emitted when a fiber begins the structured cleanup process for its child
      * fibers. This typically occurs when the parent fiber completes (successfully or with failure)
      * and needs to ensure all children are properly interrupted and cleaned up according to
      * structured concurrency semantics.
      *
      * @param fiberId
      *   the unique identifier of the parent fiber initiating cleanup
      * @param childCount
      *   the number of child fibers that need to be cleaned up
      */
    case StructuredCleanupStarted(fiberId: FiberId, childCount: Int)

    /** Signals the completion of structured cleanup for a fiber's children.
      *
      * This event is emitted when a fiber has successfully completed the structured cleanup process
      * for all its child fibers. This ensures observers can track when structured concurrency
      * guarantees have been properly enforced and all children have been cleaned up.
      *
      * @param fiberId
      *   the unique identifier of the parent fiber that completed cleanup
      * @param interruptedCount
      *   the number of child fibers that were actively interrupted
      * @param completedCount
      *   the number of child fibers that had already completed
      */
    case StructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int)

    /** Signals the interruption request sent to a child fiber during structured cleanup.
      *
      * This event is emitted when a parent fiber sends an interruption request to a child fiber as
      * part of structured cleanup. This provides visibility into the structured concurrency process
      * and helps debug timing issues or cleanup problems.
      *
      * @param parentId
      *   the unique identifier of the parent fiber requesting interruption
      * @param childId
      *   the unique identifier of the child fiber being interrupted
      * @param cause
      *   the structured cause of the interruption request
      * @param childWasRunning
      *   whether the child fiber was still running when interruption was requested
      */
    case ChildInterruptionRequested(
      parentId: FiberId,
      childId: FiberId,
      cause: InterruptCause,
      childWasRunning: Boolean
    )

    /** Signals the completion of a tracing span for performance analysis.
      *
      * This event is emitted when a tracing span completes, providing detailed timing and
      * contextual information for performance analysis and debugging. Spans can be nested and
      * correlated to build comprehensive execution traces.
      *
      * @param span
      *   the completed tracing span with timing and metadata
      */
    case TraceSpan(span: net.ghoula.eru.trace.EruTrace.Span)
  }

  /** Observer interface for receiving structured execution events.
    *
    * EruObserver defines the contract for observing Eru program execution through structured
    * events. Implementations receive all significant execution events and can perform
    * side-effecting operations such as logging, metrics collection, tracing, and alerting.
    *
    * The interface is designed for high performance and low overhead, with the expectation that
    * implementations will be efficient and non-blocking to avoid impacting program execution
    * performance.
    *
    * ==Implementation Guidelines==
    *
    * '''Performance:''' Implementations should be lightweight and avoid blocking operations. Heavy
    * processing should be offloaded to background threads or queues.
    *
    * '''Exception Safety:''' Implementations should handle exceptions internally and never let
    * exceptions escape, as this could disrupt program execution.
    *
    * '''Side Effects:''' The interface is explicitly designed for side effects such as logging,
    * metrics, and external system integration.
    *
    * @example
    *   {{{
    * class CompositeObserver(
    *   logger: Logger,
    *   metrics: MetricsCollector,
    *   tracer: TracingBackend
    * ) extends EruObserver {
    *
    *   def onEvent(event: EruEvent): Unit = {
    *     try {
    *       logEvent(event)
    *       collectMetrics(event)
    *       event match {
    *         case TraceSpan(span) => tracer.recordSpan(span)
    *         case _ => ()
    *       }
    *     } catch {
    *       case NonFatal(e) =>
    *         System.err.println(s"Observer error: $e")
    *     }
    *   }
    *
    *   private def logEvent(event: EruEvent): Unit = ???
    *   private def collectMetrics(event: EruEvent): Unit = ???
    * }
    *   }}}
    */
  trait EruObserver {

    /** Handles a structured execution event.
      *
      * This method is called for every significant event during Eru program execution.
      * Implementations should handle events efficiently and never throw exceptions.
      *
      * The method is called synchronously during program execution, so implementations should be
      * fast and non-blocking to avoid impacting performance.
      *
      * @param event
      *   the structured execution event to handle
      */
    def onEvent(event: EruEvent): Unit
  }

  /** Enhanced EruObserver that provides specialized handling for structured concurrency events.
    *
    * This trait provides a higher-level interface for observing structured concurrency patterns,
    * making it easier to build observers that focus on fiber relationships and cleanup semantics.
    * It follows Eru's Radical Ergonomics pillar by providing an intuitive API for common
    * observability patterns.
    *
    * The default implementations are no-ops, allowing users to override only the events they care
    * about. This makes it easy to create focused observers for specific use cases like debugging
    * structured concurrency issues or monitoring fiber lifecycle patterns.
    *
    * @example
    *   {{{
    * class StructuredConcurrencyDebugObserver extends StructuredConcurrencyObserver {
    *   override def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit =
    *     println(s"Starting cleanup of $childCount children for fiber $fiberId")
    *
    *   override def onChildInterruptionRequested(
    *     parentId: FiberId, childId: FiberId, cause: InterruptCause, childWasRunning: Boolean
    *   ): Unit =
    *     if (childWasRunning) {
    *       println(s"Interrupting running child $childId from parent $parentId: $cause")
    *     } else {
    *       println(s"Skipping already-completed child $childId from parent $parentId")
    *     }
    * }
    *   }}}
    */
  trait StructuredConcurrencyObserver extends EruObserver {

    /** Called when a parent-child fiber relationship is established.
      *
      * @param parentId
      *   the unique identifier of the parent fiber
      * @param childId
      *   the unique identifier of the child fiber that was forked
      */
    def onFiberForked(parentId: FiberId, childId: FiberId): Unit = ()

    /** Called when structured cleanup begins for a fiber's children.
      *
      * @param fiberId
      *   the unique identifier of the parent fiber initiating cleanup
      * @param childCount
      *   the number of child fibers that need to be cleaned up
      */
    def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit = ()

    /** Called when structured cleanup completes for a fiber's children.
      *
      * @param fiberId
      *   the unique identifier of the parent fiber that completed cleanup
      * @param interruptedCount
      *   the number of child fibers that were actively interrupted
      * @param completedCount
      *   the number of child fibers that had already completed
      */
    def onStructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int): Unit = ()

    /** Called when a child interruption is requested during structured cleanup.
      *
      * @param parentId
      *   the unique identifier of the parent fiber requesting interruption
      * @param childId
      *   the unique identifier of the child fiber being interrupted
      * @param cause
      *   the structured cause of the interruption request
      * @param childWasRunning
      *   whether the child fiber was still running when interruption was requested
      */
    def onChildInterruptionRequested(
      parentId: FiberId,
      childId: FiberId,
      cause: InterruptCause,
      childWasRunning: Boolean
    ): Unit = ()

    /** Called for fiber lifecycle events (started, completed, interrupted).
      *
      * @param event
      *   the fiber lifecycle event
      */
    def onFiberLifecycle(event: EruEvent): Unit = ()

    /** Called for program lifecycle events (started, ended, steps).
      *
      * @param event
      *   the program lifecycle event
      */
    def onProgramLifecycle(event: EruEvent): Unit = ()

    /** Called for tracing events.
      *
      * @param span
      *   the completed tracing span
      */
    def onTracing(span: net.ghoula.eru.trace.EruTrace.Span): Unit = ()

    override def onEvent(event: EruEvent): Unit = {
      event match {
        case EruEvent.FiberForked(parentId, childId) =>
          onFiberForked(parentId, childId)
        case EruEvent.StructuredCleanupStarted(fiberId, childCount) =>
          onStructuredCleanupStarted(fiberId, childCount)
        case EruEvent.StructuredCleanupCompleted(fiberId, interruptedCount, completedCount) =>
          onStructuredCleanupCompleted(fiberId, interruptedCount, completedCount)
        case EruEvent.ChildInterruptionRequested(parentId, childId, cause, wasRunning) =>
          onChildInterruptionRequested(parentId, childId, cause, wasRunning)
        case event @ (EruEvent.FiberStarted(_) | EruEvent.FiberCompleted(_, _) | EruEvent.FiberInterrupted(_, _)) =>
          onFiberLifecycle(event)
        case event @ (EruEvent.ProgramStart(_) | EruEvent.ProgramEnd(_, _) | EruEvent.Step(_, _)) =>
          onProgramLifecycle(event)
        case EruEvent.TraceSpan(span) =>
          onTracing(span)
      }
    }
  }

  /** Enhanced EruObserver that can handle trace events.
    *
    * This trait provides a specialized interface for observing tracing events, making it easier to
    * build observers that focus on performance analysis and distributed tracing integration.
    */
  trait TracingEruObserver extends EruObserver {

    /** Called when a trace span completes.
      *
      * @param span
      *   the completed tracing span with timing and metadata
      */
    def onSpanCompleted(span: net.ghoula.eru.trace.EruTrace.Span): Unit

    override def onEvent(event: EruEvent): Unit = {
      event match {
        case EruEvent.TraceSpan(span) => onSpanCompleted(span)
        case other => onOtherEvent(other)
      }
    }

    /** Handle non-tracing events (can be overridden).
      *
      * @param event
      *   the non-tracing event
      */
    def onOtherEvent(event: EruEvent): Unit = ()
  }
}

export EruObserver.*
