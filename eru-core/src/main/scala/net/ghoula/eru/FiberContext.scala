package net.ghoula.eru

/** Represents the execution state of a fiber.
  *
  * FiberState tracks the current execution status of a fiber throughout its lifecycle, enabling
  * proper coordination and structured concurrency semantics.
  */
enum FiberState {

  /** The fiber is currently running or ready to run. */
  case Running

  /** The fiber is suspended, waiting for an asynchronous operation to complete. */
  case Suspended

  /** The fiber has completed successfully or with a failure. */
  case Done

  /** The fiber has been interrupted and is cleaning up resources. */
  case Interrupted
}

/** Immutable context information for a fiber.
  *
  * FiberContext contains all the metadata and state information associated with a fiber, including
  * its identity, current state, and parentage information for structured concurrency. This is a
  * pure data structure that describes a fiber's context without containing the execution logic
  * itself.
  *
  * @param id
  *   the unique identifier for this fiber
  * @param state
  *   the current execution state of the fiber
  * @param parentId
  *   the ID of the parent fiber, if any (for structured concurrency)
  * @param startTime
  *   the timestamp when this fiber was created (in nanoseconds)
  */
final case class FiberContext(
  id: FiberId,
  state: FiberState,
  parentId: Option[FiberId],
  startTime: Long
) {

  /** Creates a new FiberContext with the specified state.
    *
    * @param newState
    *   the new state for the fiber
    * @return
    *   a new FiberContext with the updated state
    */
  def withState(newState: FiberState): FiberContext =
    copy(state = newState)

  /** Creates a new FiberContext marking this fiber as a child of the specified parent.
    *
    * @param parent
    *   the parent fiber ID
    * @return
    *   a new FiberContext with the specified parent
    */
  def withParent(parent: FiberId): FiberContext =
    copy(parentId = Some(parent))

  /** Checks if this fiber is a child of the specified parent fiber.
    *
    * @param parent
    *   the potential parent fiber ID
    * @return
    *   true if this fiber is a child of the specified parent
    */
  def isChildOf(parent: FiberId): Boolean =
    parentId.contains(parent)

  /** Returns the age of this fiber in nanoseconds.
    *
    * @return
    *   the number of nanoseconds since this fiber was created
    */
  def ageNanos: Long =
    System.nanoTime() - startTime
}

object FiberContext {

  /** Creates a new root fiber context with no parent.
    *
    * @return
    *   a new FiberContext for a root fiber
    */
  def root(): FiberContext =
    FiberContext(
      id = FiberId.fresh(),
      state = FiberState.Running,
      parentId = None,
      startTime = System.nanoTime()
    )

  /** Creates a new child fiber context with the specified parent.
    *
    * @param parentId
    *   the ID of the parent fiber
    * @return
    *   a new FiberContext for a child fiber
    */
  def child(parentId: FiberId): FiberContext =
    FiberContext(
      id = FiberId.fresh(),
      state = FiberState.Running,
      parentId = Some(parentId),
      startTime = System.nanoTime()
    )
}
