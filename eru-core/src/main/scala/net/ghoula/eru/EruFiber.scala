package net.ghoula.eru

/** A pure, immutable handle to a running fiber computation.
  *
  * `EruFiber[E, A]` represents a handle to a computation that is running concurrently on a separate
  * fiber. This is a description of a fiber reference, not the execution itself. It provides methods
  * to await the fiber's completion or interrupt it, while maintaining the pure, referentially
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
  * @param id
  *   the unique identifier of this fiber
  */
final case class EruFiber[+E, +A](id: FiberId) {

  /** Creates an Eru effect that awaits this fiber's completion.
    *
    * The returned effect will suspend until this fiber completes, then produce an Exit value
    * representing the fiber's final result. This operation is pure and referentially transparent -
    * it describes the intent to wait for the fiber without actually performing the wait.
    *
    * @return
    *   an Eru effect that yields the fiber's exit result when executed
    */
  def await: Eru[E, Exit[E, A]] = Eru.await(this)

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

  /** Creates a new EruFiber handle with a fresh fiber ID.
    *
    * This method is primarily for internal use during fiber creation. User code should typically
    * use `Eru.fork` to create fibers.
    *
    * @tparam E
    *   the error type of the fiber's computation
    * @tparam A
    *   the success type of the fiber's computation
    * @return
    *   a new EruFiber handle with a unique ID
    */
  private[eru] def fresh[E, A]: EruFiber[E, A] = EruFiber(FiberId.fresh())

  /** Creates an EruFiber handle with a specific fiber ID.
    *
    * This method is for internal use when creating fiber handles with predetermined IDs during
    * runtime execution.
    *
    * @param id
    *   the fiber ID to use
    * @tparam E
    *   the error type of the fiber's computation
    * @tparam A
    *   the success type of the fiber's computation
    * @return
    *   an EruFiber handle with the specified ID
    */
  private[eru] def withId[E, A](id: FiberId): EruFiber[E, A] = EruFiber(id)
}
