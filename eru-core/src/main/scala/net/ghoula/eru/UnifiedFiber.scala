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
    */
  case Active[E, A](
    latch: CountDownLatch,
    exitRef: AtomicReference[Exit[E, A]],
    threadRef: AtomicReference[Option[Thread]]
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

    case UnifiedFiberState.Active(latch, exitRef, _) =>
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

    case UnifiedFiberState.Active(_, _, threadRef) =>
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
    * @return
    *   an active UnifiedFiber with coordination primitives
    */
  def active[E, A](id: FiberId): UnifiedFiber[E, A] = {
    val latch = new CountDownLatch(1)
    val exitRef = new AtomicReference[Exit[E, A]]()
    val threadRef = new AtomicReference[Option[Thread]](None)
    new UnifiedFiber(id, UnifiedFiberState.Active(latch, exitRef, threadRef))
  }

  /** Completes an active fiber with the given exit result.
    *
    * This method is used by runtime backends to transition an active fiber to the completed state
    * by setting its result and releasing waiters.
    *
    * @param fiber
    *   the active fiber to complete
    * @param exit
    *   the completion result
    */
  def complete[E, A](fiber: UnifiedFiber[E, A], exit: Exit[E, A]): Unit = {
    fiber.state match {
      case UnifiedFiberState.Active(latch, exitRef, _) =>
        exitRef match {
          case ref: AtomicReference[Exit[E, A]] @unchecked =>
            ref.set(exit)
            latch.countDown()
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
      case UnifiedFiberState.Active(_, _, threadRef) =>
        threadRef.set(Some(thread))
      case UnifiedFiberState.Completed(_) =>
        ()
    }
  }
}
