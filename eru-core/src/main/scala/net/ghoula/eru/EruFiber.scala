package net.ghoula.eru

/** A pure, immutable handle to a completed fiber computation.
  *
  * `EruFiber[E, A]` represents a handle to a computation that has been eagerly evaluated to
  * completion on a logical fiber. In Phase 2, fibers are evaluated immediately using Strategy A
  * (eager evaluation), storing their final result and accumulated finalizers directly in the fiber
  * handle. This provides zero-cast implementation while maintaining the pure, referentially
  * transparent nature of the Eru effect system.
  *
  * Key characteristics:
  *   - Pure and immutable: contains no mutable state or side effects
  *   - Type-safe: preserves the error type E and success type A of the underlying computation
  *   - Structured concurrency ready: supports proper parentage and cleanup semantics
  *   - Observable: integrates with the Eru observability system
  *
  * @tparam E
  *   the error type of the fiber's computation (covariant)
  * @tparam A
  *   the success type of the fiber's computation (covariant)
  *
  * Phase 2 Implementation Notes:
  *   - Fibers are eagerly evaluated using Strategy A: Fork operations execute child computations
  *     immediately to completion and store results in the fiber handle
  *   - Auto-join semantics prevent finalizer leakage from unawaited fibers
  *   - Multiple await operations are safe and always return the same result
  *   - Interruption is not yet implemented and returns Eru.unit as placeholder
  *
  * @param id
  *   the unique identifier of this fiber
  * @param exit
  *   the pre-computed completion result of the fiber (Phase 2: eagerly evaluated)
  * @param finalizers
  *   the accumulated finalizers from fiber execution in FILO order (Phase 2: pre-accumulated)
  */
final case class EruFiber[+E, +A](
  id: FiberId,
  private[eru] val exit: Exit[E, A],
  private[eru] val finalizers: List[() => Eru[Nothing, Unit]]
) extends Fiber[E, A] {

  /** Creates an Eru effect that awaits this fiber's completion.
    *
    * In Phase 2, fibers are eagerly evaluated to completion, so this operation immediately returns
    * the pre-computed Exit value without suspension. Multiple await operations on the same fiber
    * are safe and referentially transparent - they always return the same result. This operation is
    * pure and describes the intent to retrieve the fiber's result.
    *
    * @return
    *   an Eru effect that yields the fiber's exit result when executed
    */
  def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit)

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
    // This will be implemented in later phases when the runtime supports interruption
    // For now, this is just a placeholder that demonstrates the intended API
    // The cause parameter will be used in later phases
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
