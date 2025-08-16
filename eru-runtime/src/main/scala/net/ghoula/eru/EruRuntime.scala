package net.ghoula.eru

/** Minimal runtime surface for 0.3.0 Milestone A.
  *
  * This object provides a synchronous stub for fiber operations to establish the public API surface
  * and semantics. It does not introduce true concurrency yet; effects are evaluated immediately
  * when a fiber is forked and the resulting Fiber is already completed. Interruption is recorded
  * but not enforced.
  *
  * The goal is to unblock tests and documentation while we iterate on the scheduler in subsequent
  * milestones.
  */
object EruRuntime {

  /** A token that re-enables interruptibility inside a masked region. */
  trait Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A]
  }

  private object IdentityUnmask extends Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A] = fa
  }

  private def exitFromResult[E, A](result: Result[E, A]): Exit[E, A] =
    result match {
      case Result.Success(value) => Exit.Success(value)
      case Result.Failure(err) =>
        err match {
          case t: Throwable => Exit.Die(t)
          case e => Exit.Failure(e)
        }
    }

  /** Forks a computation into a fiber and returns it immediately.
    *
    * Current minimal behavior: evaluates the computation synchronously and returns a completed
    * Fiber capturing the Exit. No concurrent scheduling yet.
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val fid = FiberId.fresh()
      val result: Result[E, A] = fa.attempt.unsafeRunSync()
      val exit: Exit[E, A] = exitFromResult(result)
      new CompletedFiber[E, A](fid, exit)
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t: Throwable) =>
        val fid = FiberId.fresh()
        val exit: Exit[E, A] = Exit.Die(t)
        new CompletedFiber[E, A](fid, exit)
    }

  /** Forks with an observer, emitting fiber lifecycle events. */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val fid = FiberId.fresh()
      observer.onEvent(EruEvent.FiberStarted(fid))
      val noop = new EruObserver { def onEvent(event: EruEvent): Unit = () }
      val result: Result[E, A] = fa.attempt.unsafeRunSyncWith(noop)
      val exit: Exit[E, A] = exitFromResult(result)
      observer.onEvent(EruEvent.FiberCompleted(fid, exit))
      new CompletedFiber[E, A](fid, exit)
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t: Throwable) =>
        val fid = FiberId.fresh()
        observer.onEvent(EruEvent.FiberCompleted(fid, Exit.Die(t)))
        val exit: Exit[E, A] = Exit.Die(t)
        new CompletedFiber[E, A](fid, exit)
    }

  /** Cooperative yield (placeholder). */
  def yieldNow: Eru[Nothing, Unit] = Eru.unit

  /** Makes a region uninterruptible (placeholder semantics for now). */
  def uninterruptible[E, A](fa: Eru[E, A]): Eru[E, A] = fa

  /** Masks interruption within a region, providing an Unmask to selectively restore
    * interruptibility (placeholder semantics for now).
    */
  def mask[E, A](k: Unmask => Eru[E, A]): Eru[E, A] = k(IdentityUnmask)

  private final class CompletedFiber[E, A](val id: FiberId, exit0: Exit[E, A]) extends Fiber[E, A] {
    private var interrupted: Option[InterruptCause] = None

    def await: Eru[Nothing, Exit[E, A]] = Eru.succeed {
      interrupted match {
        case Some(cause) => Exit.Interrupt(id, cause)
        case None => exit0
      }
    }

    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.effect {
      interrupted = Some(cause)
      ()
    }.attempt.flatMap(_ => Eru.unit)
  }
}
