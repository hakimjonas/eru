package net.ghoula.eru

/** Structured outcome of running an effect.
  *
  * Cases:
  *   - Success(value): completed successfully
  *   - Failure(error): completed with a typed error
  *   - Die(throwable): failed due to an unexpected throwable
  *   - Interrupt(fiberId, cause): interrupted cooperatively
  *
  * @tparam E
  *   the typed error
  * @tparam A
  *   the success value
  */
enum Exit[+E, +A] {

  /** Successful completion with a value.
    *
    * @param value
    *   the value produced by the effect
    */
  case Success(value: A)

  /** Failure with a typed error.
    *
    * @param error
    *   the error that caused the failure
    */
  case Failure(error: E)

  /** Failure due to an unexpected throwable.
    *
    * @param throwable
    *   the throwable that caused the failure
    */
  case Die(throwable: Throwable)

  /** Termination due to interruption.
    *
    * @param fiberId
    *   the identifier of the interrupted fiber
    * @param cause
    *   the reason for the interruption
    */
  case Interrupt(fiberId: FiberId, cause: InterruptCause)
}

/** A unique identifier for fibers.
  *
  * Uses monotonic Long values to ensure uniqueness within the process lifetime.
  *
  * @example
  *   {{{
  * val fiberId1 = FiberId.fresh()
  * val fiberId2 = FiberId.fresh()
  * assert(fiberId1 != fiberId2)
  *
  * val fiberRegistry = mutable.Map[FiberId, FiberState]()
  * fiberRegistry(fiberId1) = FiberState.Running
  *
  * logger.info(s"Fiber $fiberId1 started processing request")
  *   }}}
  */
opaque type FiberId = Long

/** Factory for creating fiber identifiers.
  */
object FiberId {

  /** Reserved identifier for the runtime's root boundary.
    *
    * Root fibers have no parent fiber; their parent is the runtime itself. `cleanup()` and
    * `shutdownRootFibers` interrupt root fibers with `InterruptCause.ParentTerminated(FiberId.Root,
    * ...)` to name that boundary. `fresh()` never produces this value (its seed is far above 0).
    */
  val Root: FiberId = 0L

  /** Process-unique ID generation.
    *
    * Layout: [0][15-bit processId][48-bit timestamp/counter]
    *   - Bit 63: Always 0 (ensures positive Long)
    *   - Bits 62-48: Process identifier (15 bits = 32K unique processes)
    *   - Bits 47-0: Timestamp-based counter (281 trillion unique IDs per process)
    */
  private val processUniqueStart = {
    val ProcessIdBits = 15
    val ProcessIdMask = (1L << ProcessIdBits) - 1
    val TimestampMask = (1L << 48) - 1

    val processId = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.hashCode.toLong & ProcessIdMask
    val timestamp = System.nanoTime() & TimestampMask

    (processId << 48) | timestamp
  }
  private val next = new java.util.concurrent.atomic.AtomicLong(processUniqueStart)

  /** Creates a new unique fiber identifier.
    *
    * @return
    *   a new FiberId
    *
    * @example
    *   {{{
    * val newFiberId = FiberId.fresh()
    *
    * val fiberIds = (1 to 1000).map(_ => FiberId.fresh())
    * assert(fiberIds.distinct.size == 1000)
    *   }}}
    */
  def fresh(): FiberId = next.getAndIncrement()

  /** Extension methods for FiberId */
  extension (id: FiberId) {

    /** Returns the underlying Long value of this fiber ID.
      *
      * @return
      *   the numeric representation of this fiber ID
      */
    def toLong: Long = id
  }
}

/** Structured cause of fiber interruption with diagnostic information.
  *
  * InterruptCause classifies why a fiber was interrupted. Each case carries the context relevant to
  * its scenario, for debugging, monitoring, and cleanup handling.
  *
  * ==Interruption Categories==
  *
  * '''Cancelled:''' An explicit cancellation request, initiated by user action, application logic,
  * or runtime management.
  *
  * '''Timeout:''' An operation exceeded its allowed duration. The effect is interrupted rather than
  * allowed to block indefinitely.
  *
  * '''ParentTerminated:''' A child fiber was terminated because its parent completed or was
  * interrupted (structured concurrency).
  *
  * '''ResourceExhausted:''' An operation was terminated because a system resource limit was
  * reached.
  *
  * '''Custom:''' An application-specific interruption reason, carrying a name, optional context,
  * and metadata.
  *
  * @example
  *   {{{
  * // Handle different interruption causes appropriately
  * def handleInterruption(cause: InterruptCause): Unit = cause match {
  *   case InterruptCause.Cancelled(reason) =>
  *     logger.info(s"Operation cancelled: ${reason.getOrElse("User request")}")
  *     metrics.incrementCounter("fiber.cancelled")
  *
  *   case InterruptCause.Timeout(duration, operation) =>
  *     val op = operation.getOrElse("unknown operation")
  *     logger.warn(s"Operation '$op' timed out after $duration")
  *     metrics.recordTimer("fiber.timeout", duration)
  *     alerting.sendTimeoutAlert(op, duration)
  *
  *   case InterruptCause.ParentTerminated(parentId, parentExit) =>
  *     logger.debug(s"Child fiber terminated due to parent $parentId: $parentExit")
  *     metrics.incrementCounter("fiber.parent_terminated")
  *
  *   case InterruptCause.ResourceExhausted(resource, details) =>
  *     val detail = details.getOrElse("No additional details")
  *     logger.error(s"Resource exhausted: $resource - $detail")
  *     metrics.incrementCounter(s"fiber.resource_exhausted.$resource")
  *     alerting.sendResourceAlert(resource, detail)
  *
  *   case InterruptCause.Custom(name, context, metadata) =>
  *     logger.info(s"Custom interruption: $name")
  *     context.foreach(ctx => logger.debug(s"Context: $ctx"))
  *     metadata.foreach { case (k, v) => logger.debug(s"$k: $v") }
  * }
  *
  * // Creating structured interruption causes
  * val userCancellation = InterruptCause.Cancelled(Some("User clicked stop button"))
  * val operationTimeout = InterruptCause.Timeout(Duration.ofSeconds(30), Some("database query"))
  * val customCause = InterruptCause.Custom(
  *   name = "circuit_breaker_open",
  *   context = Some("Downstream service unavailable"),
  *   metadata = Map("service" -> "payment-api", "failures" -> "5")
  * )
  *   }}}
  */
enum InterruptCause {

  /** Interruption initiated by an explicit cancellation request.
    *
    * The interrupted effect stops at its next suspension point. The `reason` field is descriptive:
    * it is attached when the cancel request is created and is not part of the interrupt delivery
    * mechanism.
    *
    * @param reason
    *   optional human-readable description explaining why cancellation was requested, useful for
    *   debugging and audit trails
    */
  case Cancelled(reason: Option[String] = None)

  /** Interruption caused by exceeding a time-based execution limit.
    *
    * Used by timeout operators to bound blocking work: when the limit expires, the effect receives
    * an interrupt with this cause.
    *
    * @param duration
    *   the timeout duration that was exceeded, providing context about the time limit
    * @param operation
    *   optional description of the specific operation which timed out, useful for diagnostics and
    *   performance analysis
    */
  case Timeout(duration: java.time.Duration, operation: Option[String] = None)

  /** Interruption caused by parent fiber termination in structured concurrency.
    *
    * When a parent fiber completes or is interrupted, the runtime interrupts its child fibers with
    * this cause before the scope is torn down.
    *
    * @param parentId
    *   the FiberId of the parent fiber that terminated, enabling correlation and debugging of fiber
    *   hierarchies
    * @param parentExit
    *   the complete Exit outcome of the parent fiber, providing full context about how the parent
    *   terminated and why children are being interrupted
    */
  case ParentTerminated(parentId: FiberId, parentExit: Exit[Any, Any])

  /** Interruption caused by system resource exhaustion or limits.
    *
    * This cause is attached when an operation must be terminated because a system-imposed limit was
    * reached, such as memory pressure or file descriptor exhaustion.
    *
    * @param resource
    *   description of the exhausted resource (e.g., "memory", "file descriptors", "connection
    *   pool"), enabling targeted monitoring and alerting
    * @param details
    *   optional additional context about the resource exhaustion, such as current usage levels or
    *   specific limits that were exceeded
    */
  case ResourceExhausted(resource: String, details: Option[String] = None)

  /** User-defined interruption cause for application-specific scenarios.
    *
    * This case carries a name, an optional context string, and optional key-value metadata. The
    * fields are purely diagnostic: interruption delivery does not inspect them.
    *
    * @param name
    *   human-readable identifier for the interruption cause should be consistent across similar
    *   scenarios for effective monitoring and handling
    * @param context
    *   optional structured context information providing details about the specific situation that
    *   led to the interruption
    * @param metadata
    *   optional key-value metadata for additional debugging information, enabling diagnostic data
    *   without sacrificing type safety
    */
  case Custom(
    name: String,
    context: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  )
}

/** A handle to a fiber computation.
  *
  * A Fiber is created when an effect is forked and represents that computation's execution. Await
  * its result with [[Fiber.await]] or request interruption with [[Fiber.interrupt]].
  *
  * ==Core Principles==
  *
  * '''Structured Concurrency:''' Fibers follow structured concurrency principles: child fibers are
  * managed by their parents, and when a parent terminates the runtime interrupts its children.
  *
  * '''Interruption:''' Interruption is delivered via Java thread interrupts, which land at blocking
  * and suspension points. A fiber that never suspends does not observe an interrupt until it does.
  * Finalizers registered with `ensure` or `bracket` run as the effect unwinds.
  *
  * '''Resource Management:''' Fibers integrate with Eru's resource management: `bracket`-style
  * finalizers run regardless of whether the fiber completes, dies, or is interrupted.
  *
  * '''Observability:''' Fiber execution can be observed through the EruObserver system to inspect
  * execution patterns and performance characteristics.
  *
  * ==Fiber Lifecycle==
  *
  *   1. '''Creation:''' Fibers are created when effects are forked for concurrent execution
  *   2. '''Execution:''' Fibers run their associated effects independently of other fibers
  *   3. '''Completion:''' Fibers complete with an Exit outcome indicating success, failure, or
  *      interruption
  *   4. '''Cleanup:''' Resources are automatically cleaned up regardless of completion mode
  *
  * @tparam E
  *   the type of typed errors that may occur during fiber execution
  * @tparam A
  *   the type of the success value produced on successful completion
  *
  * @example
  *   {{{
  * // A Fiber handle lets you await the exit result of a computation.
  * def handleResult[E, A](fiber: Fiber[E, A]): Eru[Nothing, Option[A]] =
  *   fiber.await.map {
  *     case Exit.Success(a)    => Some(a)
  *     case Exit.Failure(_)    => None
  *     case Exit.Die(t)        => throw t
  *     case Exit.Interrupt(_, _) => None
  *   }
  *   }}}
  */
trait Fiber[+E, +A] {

  /** The unique identifier of this fiber.
    *
    * This identifier is stable throughout the fiber's lifetime and can be used for correlation,
    * logging, and debugging purposes. The ID is unique within the process and enables tracking of
    * fiber relationships and execution patterns.
    *
    * @return
    *   the unique FiberId for this fiber instance
    */
  def id: FiberId

  /** Waits for this fiber to complete and returns its structured exit outcome.
    *
    * This operation suspends until this fiber completes, then returns the complete Exit outcome
    * without throwing exceptions. The effect's error channel is `Nothing`: all outcomes, including
    * defects (`Exit.Die`) and interruption (`Exit.Interrupt`), are delivered as the `Exit` value
    * rather than as typed errors.
    *
    * The returned effect is pure and referentially transparent, enabling safe composition with
    * other effects and retry logic. Multiple await calls on the same fiber will all receive the
    * same Exit outcome.
    *
    * '''Structured Concurrency:''' If the calling fiber is interrupted while awaiting, the
    * interruption propagates to the awaiter.
    *
    * '''Resource Safety:''' Awaiting a fiber does not affect resource cleanup - resources are
    * managed independently of await operations.
    *
    * @return
    *   an effect that, when executed, will complete with the fiber's Exit outcome
    *
    * @example
    *   {{{
    * // Safe fiber joining with comprehensive error handling
    * def joinFiber[E, A](fiber: Fiber[E, A]): Eru[String, A] = {
    *   fiber.await.flatMap {
    *     case Exit.Success(value) =>
    *       logger.info(s"Fiber ${fiber.id} completed successfully")
    *       Eru.succeed(value)
    *
    *     case Exit.Failure(error) =>
    *       logger.warn(s"Fiber ${fiber.id} failed with: $error")
    *       Eru.fail(s"Fiber failed: $error")
    *
    *     case Exit.Die(throwable) =>
    *       logger.error(s"Fiber ${fiber.id} died unexpectedly", throwable)
    *       Eru.fail(s"Fiber died: ${throwable.getMessage}")
    *
    *     case Exit.Interrupt(_, cause) =>
    *       logger.debug(s"Fiber ${fiber.id} was interrupted: $cause")
    *       Eru.fail(s"Fiber interrupted: $cause")
    *   }
    * }
    *   }}}
    */
  def await: Eru[Nothing, Exit[E, A]]

  /** Requests interruption of this fiber with a specific cause.
    *
    * If the fiber is currently executing on a thread, that thread receives a Java interrupt. The
    * fiber observes the interrupt at its next blocking or suspension point and completes with
    * `Exit.Interrupt`. If the fiber has already completed, this is a no-op.
    *
    * The request is asynchronous: this method returns once the interrupt has been issued, not when
    * the fiber terminates. The `cause` documents the request for diagnostics and observer handling.
    * Repeating the call on an active fiber repeats the thread interrupt, which is harmless because
    * the fiber completes interrupted at most once.
    *
    * '''Finalizers:''' Finalizers registered with `ensure` or `bracket` run as the fiber unwinds
    * after interruption.
    *
    * @param cause
    *   the structured reason for the interruption request, providing diagnostic information for
    *   debugging, monitoring, and proper error handling
    * @return
    *   an effect that completes when the interruption request has been issued (not when the fiber
    *   terminates)
    *
    * @example
    *   {{{
    * // Timeout-based interruption with proper cause
    * def timeoutFiber[E, A](
    *   fiber: Fiber[E, A],
    *   timeout: java.time.Duration,
    *   operation: String
    * ): Eru[Nothing, Unit] =
    *   fiber.interrupt(InterruptCause.Timeout(timeout, Some(operation)))
    *
    * // User-initiated cancellation
    * def cancelOnUserRequest[E, A](fiber: Fiber[E, A]): Eru[Nothing, Unit] = {
    *   fiber.interrupt(InterruptCause.Cancelled(Some("User cancellation request")))
    * }
    *
    * // Resource exhaustion interruption
    * def interruptOnResourcePressure[E, A](fiber: Fiber[E, A]): Eru[Nothing, Unit] = {
    *   fiber.interrupt(InterruptCause.ResourceExhausted(
    *     resource = "memory",
    *     details = Some("JVM heap usage exceeded 90%")
    *   ))
    * }
    *
    * // Custom application-specific interruption
    * def interruptForMaintenance[E, A](fiber: Fiber[E, A]): Eru[Nothing, Unit] = {
    *   fiber.interrupt(InterruptCause.Custom(
    *     name = "scheduled_maintenance",
    *     context = Some("System entering maintenance window"),
    *     metadata = Map(
    *       "maintenance_id" -> "MAINT-2023-001",
    *       "scheduled_time" -> "2023-01-15T02:00:00Z"
    *     )
    *   ))
    * }
    *   }}}
    */
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit]
}
