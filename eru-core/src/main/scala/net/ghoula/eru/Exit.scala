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
  // Use process-unique starting point to avoid conflicts between multiple Eru instances
  private val processUniqueStart = {
    val processId = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.hashCode & 0xffffL
    val nanoTime = System.nanoTime() & 0xffffffffffffL
    (processId.toLong << 48) | (nanoTime & 0xffffL)
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

/** Structured cause of fiber interruption with comprehensive diagnostic information.
  *
  * InterruptCause provides a rich, type-safe classification of why a fiber was interrupted,
  * enabling precise handling of different cancellation scenarios. This structured approach supports
  * by capturing detailed context that aids in debugging, monitoring, and proper resource cleanup
  * during fiber termination.
  *
  * The cause system is designed to handle the full spectrum of interruption scenarios in concurrent
  * programs, from user-initiated cancellation to system-imposed limits and structured concurrency
  * requirements. Each cause type provides specific context relevant to its interruption scenario.
  *
  * ==Interruption Categories==
  *
  * '''Canceled:''' Explicit cancellation requests from users or the runtime system, typically
  * representing intentional termination of operations.
  *
  * '''Timeout:''' Time-based interruptions where operations exceed their allowed duration, crucial
  * for maintaining system responsiveness and preventing resource leaks.
  *
  * '''ParentTerminated:''' Structured concurrency interruptions where child fibers are terminated
  * due to parent fiber completion, maintaining hierarchical execution guarantees.
  *
  * '''ResourceExhausted:''' System-limit interruptions where resource constraints force operation
  * termination, enabling graceful degradation under resource pressure.
  *
  * '''Custom:''' Application-specific interruption reasons that provide domain-specific context
  * while maintaining type safety and structured handling.
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
    * This cause represents intentional termination of fiber execution, typically initiated by user
    * action, application logic, or runtime management. Cancellation is the most common form of
    * cooperative interruption and usually indicates normal termination of operations that are no
    * longer needed.
    *
    * @param reason
    *   optional human-readable description explaining why cancellation was requested, useful for
    *   debugging and audit trails
    */
  case Cancelled(reason: Option[String] = None)

  /** Interruption caused by exceeding a time-based execution limit.
    *
    * This cause represents scenarios where operations are terminated due to time constraints,
    * preventing indefinite blocking and ensuring system responsiveness. Timeouts are crucial for
    * maintaining quality of service and preventing resource exhaustion in distributed systems.
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
    * This cause represents the structured concurrency principle where child fibers are
    * automatically interrupted when their parent fiber terminates. This ensures that resource
    * cleanup and execution boundaries are maintained hierarchically, preventing orphaned fibers and
    * resource leaks in concurrent programs.
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
    * This cause represents scenarios where operations must be terminated due to resource
    * constraints such as memory limits, file descriptor exhaustion, or other system-imposed
    * restrictions. This enables graceful degradation and prevents system-wide failures due to
    * resource pressure.
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
    * This cause provides a type-safe way for applications to create custom interruption reasons
    * with rich diagnostic information. It enables domain-specific error handling while maintaining
    * a structured approach to interruption management.
    *
    * @param name
    *   human-readable identifier for the interruption cause should be consistent across similar
    *   scenarios for effective monitoring and handling
    * @param context
    *   optional structured context information providing details about the specific situation that
    *   led to the interruption
    * @param metadata
    *   optional key-value metadata for additional debugging information, enabling rich diagnostic
    *   data without sacrificing type safety
    */
  case Custom(
    name: String,
    context: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  )
}

/** A lightweight, user-space thread of execution in Eru's asynchronous runtime.
  *
  * Fiber represents the fundamental unit of concurrent computation in Eru's asynchronous runtime,
  * providing structured concurrency with cooperative interruption and resource safety guarantees.
  * Each fiber encapsulates the execution of an effect and provides a safe, composable interface for
  * concurrent programming that eliminates common concurrency pitfalls.
  *
  * ==Core Principles==
  *
  * '''Structured Concurrency:''' Fibers follow structured concurrency principles where child fibers
  * are automatically managed by their parents, preventing resource leaks and orphaned computations.
  * When a parent fiber terminates, all child fibers are cooperatively interrupted.
  *
  * '''Cooperative Interruption:''' Interruption is cooperative rather than preemptive, allowing
  * fibers to complete critical sections and perform proper cleanup before termination. This ensures
  * resource safety and prevents a corrupted state.
  *
  * '''Safe Resource Management:''' Fibers integrate with Eru's resource management system to ensure
  * that resources are properly cleaned up even when interruption occurs, preventing resource leaks
  * in concurrent programs.
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
  * // Basic fiber operations
  * def concurrentProcessing[A, B](
  *   taskA: Eru[String, A],
  *   taskB: Eru[String, B]
  * ): Eru[String, (A, B)] = {
  *   for {
  *     fiberA <- taskA.fork  // Start taskA in a new fiber
  *     fiberB <- taskB.fork  // Start taskB in a new fiber
  *     resultA <- fiberA.await.flatMap {
  *       case Exit.Success(a) => Eru.succeed(a)
  *       case Exit.Failure(e) => Eru.fail(e)
  *       case Exit.Die(t) => Eru.die(t)
  *       case Exit.Interrupt(_, cause) => Eru.fail(s"Interrupted: $cause")
  *     }
  *     resultB <- fiberB.await.flatMap {
  *       case Exit.Success(b) => Eru.succeed(b)
  *       case Exit.Failure(e) => Eru.fail(e)
  *       case Exit.Die(t) => Eru.die(t)
  *       case Exit.Interrupt(_, cause) => Eru.fail(s"Interrupted: $cause")
  *     }
  *   } yield (resultA, resultB)
  * }
  *
  * // Timeout with graceful interruption
  * def withTimeout[E, A](
  *   effect: Eru[E, A],
  *   duration: java.time.Duration
  * ): Eru[E, A] = {
  *   for {
  *     fiber <- effect.fork
  *     result <- Eru.race(
  *       fiber.await.map {
  *         case Exit.Success(a) => a
  *         case Exit.Failure(e) => throw new Exception(s"Effect failed: $e")
  *         case Exit.Die(t) => throw t
  *         case Exit.Interrupt(_, _) => throw new Exception("Effect interrupted")
  *       },
  *       Eru.sleep(duration) *> fiber.interrupt(
  *         InterruptCause.Timeout(duration, Some("user timeout"))
  *       ) *> Eru.fail("Operation timed out")
  *     )
  *   } yield result
  * }
  *
  * // Resource-safe concurrent processing
  * def processWithResources[A](data: List[A]): Eru[Throwable, List[String]] = {
  *   val processItem = (item: A) => Eru.resource {
  *     // Acquire expensive resource
  *     val resource = new ExpensiveResource()
  *     resource.process(item.toString)
  *   } { resource =>
  *     // Guaranteed cleanup even on interruption
  *     Eru.effect(resource.close())
  *   }
  *
  *   // Process all items concurrently
  *   data.map(processItem).forkAll.flatMap(fibers =>
  *     fibers.map(_.await).sequence.map(exits =>
  *       exits.collect { case Exit.Success(result) => result }
  *     )
  *   )
  * }
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
    * This operation blocks the calling fiber until this fiber completes, then returns the complete
    * Exit outcome without throwing exceptions. The await operation is safe and will never fail -
    * all possible outcomes are captured in the Exit structure.
    *
    * The returned effect is pure and referentially transparent, enabling safe composition with
    * other effects and retry logic. Multiple await calls on the same fiber will all receive the
    * same Exit outcome.
    *
    * '''Structured Concurrency:''' If the calling fiber is interrupted while awaiting, the
    * interruption will be propagated appropriately while maintaining fiber relationships.
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

  /** Requests cooperative interruption of this fiber with a specific cause.
    *
    * This operation sends an interruption signal to the fiber with detailed information about why
    * the interruption was requested. Interruption is cooperative, meaning the fiber will complete
    * any critical sections and perform proper cleanup before terminating.
    *
    * The interrupt request is asynchronous and non-blocking - this method returns immediately after
    * the interruption signal is sent. The actual interruption timing depends on the fiber's current
    * execution state and interrupt masking.
    *
    * '''Cooperative Nature:''' Interruption respects critical sections and finalizers, ensuring
    * that resource cleanup and important operations can be completed safely.
    *
    * '''Idempotent Operation:''' Multiple interrupt calls on the same fiber are safe and idempotent -
    * the fiber will only be interrupted once with the first provided cause.
    *
    * '''Structured Concurrency:''' When a fiber is interrupted, all of its child fibers are also
    * interrupted automatically to maintain structured concurrency guarantees.
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
    * ): Eru[Nothing, Unit] = {
    *   Eru.sleep(timeout) *>
    *     fiber.interrupt(InterruptCause.Timeout(timeout, Some(operation)))
    * }
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
