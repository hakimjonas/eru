package net.ghoula.eru

/** A handle to a fiber computation.
  *
  * @tparam E
  *   the error type of the fiber's computation
  * @tparam A
  *   the success type of the fiber's computation
  * @param id
  *   the unique identifier of this fiber
  * @param exit
  *   the completion result of the fiber computation
  * @param finalizers
  *   the accumulated finalizers from fiber execution
  */
final class EruFiber[+E, +A](
  val id: FiberId,
  private[eru] val exit: Exit[E, A],
  private[eru] val finalizers: List[() => Eru[Nothing, Unit]]
) extends Fiber[E, A] {

  /** Awaits this fiber's completion.
    *
    * @return
    *   an effect that yields the fiber's exit result
    */
  def await: Eru[Nothing, Exit[E, A]] =
    Eru.await(this).attempt.map {
      case Result.Success(exit) => exit
      case Result.Failure(_) =>
        throw new IllegalStateException("Fiber await failed unexpectedly")
    }

  /** Interrupts this fiber with the specified cause.
    *
    * @param cause
    *   the reason for interrupting this fiber
    * @return
    *   an effect that interrupts this fiber
    */
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
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
  ): EruFiber[E, A] = new EruFiber(FiberId.fresh(), exit, finalizers)

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
  ): EruFiber[E, A] = new EruFiber(id, exit, finalizers)

}
