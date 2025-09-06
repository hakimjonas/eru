package net.ghoula.eru

/** Observer API for receiving structured execution events during program runs.
  *
  * Attach an observer via `unsafeRunSyncWith` to receive events such as `ProgramStart`,
  * `ProgramEnd`, and `Step`. Fiber lifecycle events are also emitted when fiber operations are
  * used.
  */
object EruObserver {

  /** A no-op observer that discards all events.
    *
    * This is useful when an observer is required by an API but the caller does not wish to record
    * or emit any events. It has near-zero overhead.
    *
    * @return
    *   an observer that ignores all events
    */
  def noop: EruObserver = new EruObserver { def onEvent(event: EruEvent): Unit = () }

  /** A console observer that prints events to standard output.
    *
    * This helper is intended for quick diagnostics and examples. For production use, prefer a
    * structured observer that forwards to logging/metrics/tracing backends.
    *
    * @return
    *   an observer that prints human-readable events to standard output
    */
  def console: EruObserver = new EruObserver {
    def onEvent(event: EruEvent): Unit = {
      System.out.println(event.toString)
    }
  }

  /** A stable identifier for a single program execution scope.
    *
    * ScopeId provides a unique identifier that tracks the execution boundary of Eru programs. Each
    * ScopeId typically corresponds to one program run and is used to correlate events during that
    * run.
    *
    * ScopeIds are generated using a monotonic counter to ensure uniqueness within the process
    * lifetime. They provide a lightweight mechanism for correlating events across the execution of
    * a program, useful for debugging and performance analysis.
    *
    * Thread-safety: ScopeId generation is thread-safe, using atomic operations to ensure unique
    * identifiers are produced even in highly concurrent environments.
    *
    * @example
    *   {{{
    * val scopeId = ScopeId.fresh()
    * logger.info(s"Starting execution with scope $scopeId")
    * // Use scopeId for correlation across events
    *   }}}
    */
  opaque type ScopeId = Long

  object ScopeId {
    private val next = new java.util.concurrent.atomic.AtomicLong(1L)

    /** Generates a fresh ScopeId for a new execution scope.
      *
      * This method creates a unique identifier for tracking program execution. Each call returns a
      * monotonically increasing value, ensuring uniqueness within the process lifetime.
      *
      * @return
      *   a new, unique ScopeId for program execution tracking
      */
    def fresh(): ScopeId = next.getAndIncrement()
  }

  /** Structured outcome representing the final result of program execution.
    *
    * Outcome provides a comprehensive classification of how an Eru program terminates, enabling
    * observers to implement appropriate handling strategies for different failure modes. This
    * classification aligns with Eru's principled approach to error handling by distinguishing
    * between recoverable domain errors and unexpected system failures.
    *
    * The three outcome types correspond to Eru's error model:
    *   - '''Success:''' Normal program completion with a produced value
    *   - '''TypedFailure:''' Expected domain error handled through Eru's typed error channel
    *   - '''Defect:''' Unexpected system failure (Throwable) indicating a programming error
    *
    * This structured approach enables observers to implement different strategies for different
    * failure modes, such as alerting for defects while treating typed failures as normal business
    * logic outcomes.
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
    * // Pattern matching on events for structured handling
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
      * effect interpretation.
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
    * // Production-ready observer with multiple backends
    * class CompositeObserver(
    *   logger: Logger,
    *   metrics: MetricsCollector,
    *   tracer: TracingBackend
    * ) extends EruObserver {
    *
    *   def onEvent(event: EruEvent): Unit = {
    *     try {
    *       // Log all events
    *       logEvent(event)
    *
    *       // Extract metrics
    *       collectMetrics(event)
    *
    *       // Forward tracing events
    *       event match {
    *         case TraceSpan(span) => tracer.recordSpan(span)
    *         case _ => ()
    *       }
    *     } catch {
    *       case NonFatal(e) =>
    *         // Never let observer exceptions escape
    *         System.err.println(s"Observer error: $e")
    *     }
    *   }
    *
    *   private def logEvent(event: EruEvent): Unit = // ...
    *   private def collectMetrics(event: EruEvent): Unit = // ...
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
}

export EruObserver.*
