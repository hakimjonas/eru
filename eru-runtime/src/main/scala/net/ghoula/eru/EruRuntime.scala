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

  /** Combines two effects, intended to run in parallel in the async runtime.
    *
    * Placeholder semantics (0.3.0 Milestone A): this version is sequential and will be upgraded to
    * true parallel execution in a later increment without changing the API.
    *
    * @param fa
    *   the left effect
    * @param fb
    *   the right effect
    * @return
    *   an effect that yields a pair of results
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2, (A, B)] =
    fa.zip(fb)

  /** Competes two effects, returning the first successful result, tagged with its side.
    *
    * Placeholder semantics (0.3.0 Milestone A): evaluates left first; if it succeeds, returns
    * Left(a); otherwise evaluates right and returns Right(b) on success. Failure/defect propagation
    * is consistent with the core interpreter and will be upgraded to true racing.
    *
    * @param fa
    *   the left effect
    * @param fb
    *   the right effect
    * @return
    *   an effect yielding Left(a) or Right(b)
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2, Either[A, B]] =
    fa.map(Left(_)).orElse(fb.map(Right(_)))

  /** A token that re-enables interruptibility inside a masked region. */
  trait Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A]
  }

  private object IdentityUnmask extends Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A] = fa
  }

  private object Scheduler {
    private val queue = scala.collection.mutable.Queue[() => Unit]()

    def schedule(thunk: () => Unit): Unit =
      queue.enqueue(thunk)

    def pumpUntil(done: () => Boolean): Unit =
      while (!done() && queue.nonEmpty) do {
        val task = queue.dequeue()
        task()
      }
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
    * Minimal cooperative behavior: schedules the computation on the internal event loop and returns
    * a running fiber. The fiber completes when scheduled; awaiting will pump the scheduler until
    * completion.
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val fid = FiberId.fresh()
      val fiber = new RuntimeFiber[E, A](fid, fa, None)
      Scheduler.schedule(() => fiber.run())
      fiber
    }.attempt.map {
      case Result.Success(f) => f
      case Result.Failure(t: Throwable) =>
        val fid = FiberId.fresh()
        val exit: Exit[E, A] = Exit.Die(t)
        new CompletedFiber[E, A](fid, exit)
    }

  /** Forks with an observer, emitting fiber lifecycle events. */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val fid = FiberId.fresh()
      val fiber = new RuntimeFiber[E, A](fid, fa, Some(observer))
      observer.onEvent(EruEvent.FiberStarted(fid))
      Scheduler.schedule(() => fiber.run())
      fiber
    }.attempt.map {
      case Result.Success(f) => f
      case Result.Failure(t: Throwable) =>
        val fid = FiberId.fresh()
        val exit: Exit[E, A] = Exit.Die(t)
        observer.onEvent(EruEvent.FiberCompleted(fid, exit))
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

  private final class RuntimeFiber[E, A](val id: FiberId, fa: Eru[E, A], observer: Option[EruObserver])
      extends Fiber[E, A] {
    private var interrupted: Option[InterruptCause] = None
    private var exit0: Option[Exit[E, A]] = None

    def run(): Unit = {
      if (exit0.isEmpty) {
        val ex: Exit[E, A] =
          interrupted match {
            case Some(cause) => Exit.Interrupt(id, cause)
            case None =>
              val res: Result[E, A] = observer match {
                case Some(_) =>
                  val noop = new EruObserver { def onEvent(event: EruEvent): Unit = () }
                  fa.attempt.unsafeRunSyncWith(noop)
                case None => fa.attempt.unsafeRunSync()
              }
              val out = exitFromResult(res)
              interrupted match {
                case Some(cause) => Exit.Interrupt(id, cause)
                case None => out
              }
          }
        exit0 = Some(ex)
        observer.foreach(_.onEvent(EruEvent.FiberCompleted(id, ex)))
      }
    }

    def await: Eru[Nothing, Exit[E, A]] =
      Eru.effect {
        Scheduler.pumpUntil(() => exit0.nonEmpty)
        exit0.get
      }.attempt.map {
        case Result.Success(ex) => ex
        case Result.Failure(t: Throwable) => Exit.Die(t)
      }

    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] =
      Eru.effect {
        if (exit0.isEmpty) {
          interrupted = Some(cause)
          observer.foreach(_.onEvent(EruEvent.FiberInterrupted(id, cause)))
        }
        ()
      }.attempt.flatMap(_ => Eru.unit)
  }
}

private final class CompletedFiber[E, A](val id: FiberId, exit0: Exit[E, A]) extends Fiber[E, A] {
  def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit0)
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit
}
