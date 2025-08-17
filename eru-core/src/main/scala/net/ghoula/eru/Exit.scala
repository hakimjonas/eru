package net.ghoula.eru

/** Structured outcome for effectful computations in the asynchronous runtime.
  *
  * Exit represents the result of running an effect in a fiber. It distinguishes between typed
  * failures (domain errors), defects (unexpected Throwables), and cooperative interruption. This
  * separation enables principled resource safety, cancellation, and diagnostics, and aligns with
  * Eru’s edge semantics in the synchronous interpreter.
  *
  * @tparam E
  *   the type of the typed error (domain failure)
  * @tparam A
  *   the type of the success value
  */
enum Exit[+E, +A] {

  /** Represents a successful computation containing a value of type `A`. */
  case Success(value: A)

  /** Represents a computation that failed with a typed, non-Throwable error `E`. */
  case Failure(error: E)

  /** Represents an unexpected, untyped failure with a Throwable (a defect). */
  case Die(throwable: Throwable)

  /** Represents an interrupted computation with the responsible fiber id and cause. */
  case Interrupt(fiberId: FiberId, cause: InterruptCause)
}

/** A unique identity for a fiber.
  *
  * FiberId is modeled as an opaque type for domain integrity and future-proofing across platforms.
  * Ids can be generated via [[FiberId.fresh]].
  */
opaque type FiberId = Long

/** Factory and utilities for [[FiberId]]. */
object FiberId {
  private var next: Long = 1L

  /** Generates a fresh [[FiberId]].
    *
    * @return
    *   a new fiber id unique within the process scope
    */
  def fresh(): FiberId = {
    val id = next
    next = next + 1L
    id
  }
}

/** Cause of interruption for fibers with enhanced diagnostic information.
  *
  * InterruptCause provides structured, rich diagnostic information about why a fiber was interrupted.
  * This enhanced structure supports exceptional observability by capturing context that helps with
  * debugging and monitoring fiber lifecycles.
  */
enum InterruptCause {

  /** Interruption initiated by the runtime or user.
    *
    * @param reason optional human-readable description of why cancellation was requested
    */
  case Cancelled(reason: Option[String] = None)

  /** Interruption caused by a timeout policy.
    *
    * @param duration the timeout duration that was exceeded
    * @param operation optional description of what operation timed out
    */
  case Timeout(duration: java.time.Duration, operation: Option[String] = None)

  /** Interruption caused by parent fiber termination.
    *
    * When a parent fiber terminates (successfully or with failure), child fibers are interrupted
    * to maintain structured concurrency guarantees.
    *
    * @param parentId the FiberId of the terminating parent fiber
    * @param parentExit the Exit outcome of the parent fiber
    */
  case ParentTerminated(parentId: FiberId, parentExit: Exit[Any, Any])

  /** Interruption caused by resource exhaustion or system limits.
    *
    * @param resource description of the exhausted resource (e.g., "memory", "file descriptors")
    * @param details optional additional context about the resource exhaustion
    */
  case ResourceExhausted(resource: String, details: Option[String] = None)

  /** User-defined interruption cause for application-specific scenarios.
    *
    * This allows applications to create custom interruption reasons with their own
    * diagnostic information while maintaining type safety.
    *
    * @param name human-readable name for the interruption cause
    * @param context optional structured context information
    * @param metadata optional key-value metadata for debugging
    */
  case Custom(
    name: String,
    context: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  )
}

/** A lightweight, user-space thread of execution in the asynchronous runtime.
  *
  * A Fiber encapsulates the execution of an effect. It can be awaited safely to obtain an [[Exit]]
  * value and interrupted cooperatively with a specific [[InterruptCause]]. Implementations are
  * provided by the 0.3.x runtime.
  *
  * @tparam E
  *   the type of typed errors that may occur during execution
  * @tparam A
  *   the type of the success value produced on completion
  */
trait Fiber[+E, +A] {

  /** The unique identity of this fiber. */
  def id: FiberId

  /** Waits for this fiber to complete, yielding a structured [[Exit]] outcome.
    *
    * @return
    *   an `Eru[Nothing, Exit[E, A]]` that, when run, joins this fiber without throwing
    */
  def await: Eru[Nothing, Exit[E, A]]

  /** Requests cooperative interruption of this fiber with the provided cause.
    *
    * Interruption is cooperative and subject to masking semantics in critical sections.
    *
    * @param cause
    *   the reason for interruption
    * @return
    *   an `Eru[Nothing, Unit]` completing when the request has been issued
    */
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit]
}
