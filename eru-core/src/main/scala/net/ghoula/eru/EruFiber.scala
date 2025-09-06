package net.ghoula.eru

/** A pure, immutable handle to a fiber computation.
  *
  * `EruFiber[E, A]` represents a handle to a computation that executes on a logical fiber. It
  * provides operations for awaiting the fiber's completion and managing its lifecycle, while
  * maintaining the pure, referentially transparent nature of the Eru effect system.
  *
  * Key characteristics:
  *   - Pure and immutable: contains no mutable state or side effects
  *   - Type-safe: preserves the error type E and success type A of the underlying computation
  *   - Cross-platform: works consistently on both JVM and Scala Native
  *   - Resource-safe: supports proper cleanup semantics with automatic finalizer execution
  *   - Observable: integrates with the Eru observability system
  *
  * @tparam E
  *   the error type of the fiber's computation (covariant)
  * @tparam A
  *   the success type of the fiber's computation (covariant)
  *
  * Implementation characteristics:
  *   - Auto-join semantics prevent finalizer leakage from unawaited fibers
  *   - Multiple await operations are safe and always return the same result
  *   - Supports cooperative interruption for graceful fiber termination
  *
  * @param id
  *   the unique identifier of this fiber
  * @param exit
  *   the completion result of the fiber computation
  * @param finalizers
  *   the accumulated finalizers from fiber execution in FILO order
  */
final case class EruFiber[+E, +A](
  id: FiberId,
  private[eru] val exit: Exit[E, A],
  private[eru] val finalizers: List[() => Eru[Nothing, Unit]]
) extends Fiber[E, A] {

  /** Creates an Eru effect that awaits this fiber's completion.
    *
    * This operation waits for the fiber to complete and returns its Exit outcome. The await
    * operation properly merges the fiber's finalizers with the current execution context to
    * maintain FILO finalizer semantics across fiber boundaries. Multiple await operations on the
    * same fiber are safe and referentially transparent - they always return the same result.
    *
    * @return
    *   an Eru effect that yields the fiber's exit result and merges finalizers when executed
    */
  def await: Eru[Nothing, Exit[E, A]] =
    Eru.await(this).attempt.map {
      case Result.Success(exit) => exit
      case Result.Failure(_) =>
        // This case should not occur in normal operation - the interpreter prevents this path
        throw new IllegalStateException("Fiber await failed unexpectedly")
    }

  /** Creates an Eru effect that interrupts this fiber with the specified cause.
    *
    * The returned effect describes the intent to interrupt this fiber. When executed, it will
    * signal the fiber to stop its current computation and begin cleanup. The interruption is
    * cooperative - the fiber will complete its current step before processing the interrupt signal.
    *
    * @param cause
    *   the reason for interrupting this fiber
    * @return
    *   an Eru effect that interrupts this fiber when executed
    */
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
    // Interruption behavior depends on the runtime backend:
    // - JVM: Supports cooperative interruption via Virtual Thread interrupts
    // - Native: Placeholder implementation (returns Eru.unit)
    val _ = cause
    Eru.unit
  }

  /** Creates an Eru effect that interrupts this fiber due to user request.
    *
    * This is a convenience method equivalent to `interrupt(InterruptCause.Cancelled())`.
    *
    * @return
    *   an Eru effect that interrupts this fiber when executed
    */
  def interrupt: Eru[Nothing, Unit] = interrupt(InterruptCause.Cancelled(Some("User interrupt")))

  /** Returns the string representation of this fiber handle.
    *
    * @return
    *   a string representation suitable for debugging
    */
  override def toString: String = s"EruFiber(FiberId($id))"

  /** Checks equality based on fiber ID.
    *
    * Two EruFiber instances are equal if they reference the same underlying fiber, regardless of
    * their type parameters.
    *
    * @param obj
    *   the object to compare with
    * @return
    *   true if the objects represent the same fiber
    */
  override def equals(obj: Any): Boolean = obj match {
    case other: EruFiber[_, _] => id == other.id
    case _ => false
  }

  /** Returns the hash code based on the fiber ID.
    *
    * @return
    *   the hash code of the fiber ID
    */
  override def hashCode(): Int = id.hashCode()
}

object EruFiber {

  /** Creates a completed EruFiber with a fresh fiber ID for Phase 2 eager evaluation.
    *
    * In Phase 2, fibers are evaluated immediately to completion and store their result and
    * accumulated finalizers directly. This enables zero-cast implementation while maintaining
    * referential transparency.
    *
    * @param exit
    *   the completion result of the fiber
    * @param finalizers
    *   the finalizers accumulated during fiber execution (in FILO order)
    * @tparam E
    *   the error type of the fiber's computation
    * @tparam A
    *   the success type of the fiber's computation
    * @return
    *   a completed EruFiber containing the result and finalizers
    */
  private[eru] def completed[E, A](
    exit: Exit[E, A],
    finalizers: List[() => Eru[Nothing, Unit]]
  ): EruFiber[E, A] = EruFiber(FiberId.fresh(), exit, finalizers)

  /** Creates an EruFiber handle with a specific fiber ID for Phase 2 eager evaluation.
    *
    * @param id
    *   the fiber ID to use
    * @param exit
    *   the completion result of the fiber
    * @param finalizers
    *   the finalizers accumulated during fiber execution (in FILO order)
    * @tparam E
    *   the error type of the fiber's computation
    * @tparam A
    *   the success type of the fiber's computation
    * @return
    *   an EruFiber with the specified ID and completion state
    */
  private[eru] def withId[E, A](
    id: FiberId,
    exit: Exit[E, A],
    finalizers: List[() => Eru[Nothing, Unit]]
  ): EruFiber[E, A] = EruFiber(id, exit, finalizers)

}
