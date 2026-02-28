package net.ghoula.eru

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Fiber state machine with two possible states: completed or active.
  *
  * Completed fibers have a known result, while active fibers are currently executing and can be
  * awaited or interrupted.
  */
enum UnifiedFiberState[+E, +A] {

  /** A fiber that has completed execution with a known result.
    *
    * @param exit
    *   the final outcome of the fiber's execution
    */
  case Completed(exit: Exit[E, A])

  /** A fiber that is currently executing on a thread.
    *
    * @param latch
    *   coordination primitive for await operations
    * @param exitRef
    *   atomic reference holding the result once available
    * @param threadRef
    *   atomic reference to the executing thread for interruption
    * @param observerRef
    *   atomic reference to the observer for lifecycle events
    * @param fiberId
    *   the unique identifier for this fiber (needed for observer events)
    */
  case Active[E, A](
    latch: CountDownLatch,
    exitRef: AtomicReference[Exit[E, A]],
    threadRef: AtomicReference[Option[Thread]],
    observerRef: AtomicReference[Option[EruObserver]],
    fiberId: FiberId
  ) extends UnifiedFiberState[E, A]
}

/** A fiber that can be in either completed or active state.
  *
  * @tparam E
  *   the error type of the fiber's computation
  * @tparam A
  *   the success type of the fiber's computation
  * @param id
  *   the unique identifier of this fiber
  * @param state
  *   the current state of the fiber
  */
final class UnifiedFiber[+E, +A](
  val id: FiberId,
  private val state: UnifiedFiberState[E, A]
) extends Fiber[E, A] {

  /** Waits for this fiber to complete and returns its exit outcome.
    *
    * For completed fibers, this returns immediately with the stored result. For active fibers, this
    * blocks until the fiber completes execution.
    *
    * @return
    *   an effect that yields the fiber's exit result
    */
  def await: Eru[Nothing, Exit[E, A]] = state match {
    case UnifiedFiberState.Completed(exit) =>
      Eru.succeed(exit)

    case UnifiedFiberState.Active(latch, exitRef, _, _, _) =>
      Eru.interruptibleBlocking {
        latch.await()
        exitRef.get()
      }.attempt.map {
        case Result.Success(exit) => exit
        case Result.Failure(t) =>
          Option(exitRef.get()).getOrElse(Exit.Die(t))
      }
  }

  /** Requests cooperative interruption of this fiber.
    *
    * For completed fibers, this is a no-op since they're already done. For active fibers, this
    * sends an interrupt signal to the executing thread.
    *
    * @param cause
    *   the structured reason for the interruption
    * @return
    *   an effect that completes when the interruption request is issued
    */
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = state match {
    case UnifiedFiberState.Completed(_) =>
      Eru.unit

    case UnifiedFiberState.Active(_, _, threadRef, _, _) =>
      Eru.effect {
        threadRef.get().foreach(_.interrupt())
      }.attempt.flatMap(_ => Eru.unit)
  }

  /** Exposes the current state for backend operations.
    *
    * This is used internally by runtime backends for state management and should not be used by
    * application code.
    */
  private[eru] def currentState: UnifiedFiberState[E, A] = state

  override def toString: String = s"UnifiedFiber($id, $state)"

  override def equals(obj: Any): Boolean = obj match {
    case other: UnifiedFiber[_, _] => id == other.id
    case _ => false
  }

  override def hashCode(): Int = id.hashCode()
}

object UnifiedFiber {

  /** Creates a completed fiber with the given exit result.
    *
    * This is used for fibers that execute immediately (synchronous backends) or for representing
    * already-completed computations.
    *
    * @param id
    *   the fiber identifier
    * @param exit
    *   the completion result
    * @return
    *   a completed UnifiedFiber
    */
  def completed[E, A](id: FiberId, exit: Exit[E, A]): UnifiedFiber[E, A] =
    new UnifiedFiber(id, UnifiedFiberState.Completed(exit))

  /** Creates an active fiber ready for asynchronous execution.
    *
    * This is used for fibers that will execute on separate threads (virtual thread backends) and
    * need coordination primitives.
    *
    * @param id
    *   the fiber identifier
    * @param observer
    *   optional observer for fiber lifecycle events
    * @return
    *   an active UnifiedFiber with coordination primitives
    */
  def active[E, A](id: FiberId, observer: Option[EruObserver] = None): UnifiedFiber[E, A] = {
    val latch = new CountDownLatch(1)
    val exitRef = new AtomicReference[Exit[E, A]]()
    val threadRef = new AtomicReference[Option[Thread]](None)
    val observerRef = new AtomicReference[Option[EruObserver]](observer)
    new UnifiedFiber(id, UnifiedFiberState.Active(latch, exitRef, threadRef, observerRef, id))
  }

  /** Completes an active fiber with the given exit result.
    *
    * This method is used by runtime backends to transition an active fiber to the completed state
    * by setting its result and releasing waiters. If the fiber has an observer, it emits a
    * FiberCompleted event.
    *
    * @param fiber
    *   the active fiber to complete
    * @param exit
    *   the completion result
    * @param skipObserver
    *   if true, skip emitting observer event (use when caller will emit it)
    */
  def complete[E, A](fiber: UnifiedFiber[E, A], exit: Exit[E, A], skipObserver: Boolean = false): Unit = {
    fiber.state match {
      case UnifiedFiberState.Active(latch, exitRef, _, observerRef, fiberId) =>
        // Safe: fiber state machine guarantees matching type parameters
        exitRef match {
          case ref: AtomicReference[Exit[E, A]] @unchecked =>
            ref.set(exit)
            latch.countDown()

            if !skipObserver then {
              observerRef.getAndSet(None).foreach { obs =>
                val widenedExit: Exit[Any, Any] = exit match {
                  case Exit.Success(a) => Exit.Success(a)
                  case Exit.Failure(e) => Exit.Failure(e)
                  case Exit.Die(t) => Exit.Die(t)
                  case Exit.Interrupt(id, c) => Exit.Interrupt(id, c)
                }
                obs.onEvent(EruObserver.EruEvent.FiberCompleted(fiberId, widenedExit))
              }
            }
        }
      case UnifiedFiberState.Completed(_) =>
        ()
    }
  }

  /** Sets the thread reference for an active fiber.
    *
    * This is called by runtime backends when a fiber starts executing on a specific thread,
    * enabling proper interrupt support.
    *
    * @param fiber
    *   the active fiber
    * @param thread
    *   the executing thread
    */
  def setThread[E, A](fiber: UnifiedFiber[E, A], thread: Thread): Unit = {
    fiber.state match {
      case UnifiedFiberState.Active(_, _, threadRef, _, _) =>
        threadRef.set(Some(thread))
      case UnifiedFiberState.Completed(_) =>
        ()
    }
  }
}
