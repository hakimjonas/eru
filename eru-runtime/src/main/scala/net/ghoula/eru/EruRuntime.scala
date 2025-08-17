package net.ghoula.eru

import java.time.Duration

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
/** Timer abstraction for platform-specific timer implementations. */
private[eru] trait Timer {
  def schedule(delay: Duration, task: () => Unit): Unit
}

object EruRuntime {


  /** Combines two effects, executing them in parallel on separate fibers.
    *
    * Semantics:
    *   - If both succeed, returns the pair of results.
    *   - If either fails (typed failure) or dies (defect), interrupts the other fiber and waits for
    *     its completion (finalizers included) before returning the original failure/defect.
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    for {
      leftRef <- Ref.make(Option.empty[Exit[E1, A]])
      rightRef <- Ref.make(Option.empty[Exit[E2, B]])
      lf <- fork(fa)
      rf <- fork(fb)
      _ <- fork(lf.await.flatMap(ex => leftRef.set(Some(ex)))).flatMap(_ => Eru.unit)
      _ <- fork(rf.await.flatMap(ex => rightRef.set(Some(ex)))).flatMap(_ => Eru.unit)
      _ <- Eru.effect {
        def failedL(e: Exit[E1, A]): Boolean = e match { case Exit.Success(_) => false; case _ => true }
        def failedR(e: Exit[E2, B]): Boolean = e match { case Exit.Success(_) => false; case _ => true }
        Scheduler.pumpUntil { () =>
          val l = leftRef.get.unsafeRunSync()
          val r = rightRef.get.unsafeRunSync()
          (l.isDefined && r.isDefined) || l.exists(failedL) || r.exists(failedR)
        }
      }.attempt.flatMap(_ => Eru.unit)
      le <- leftRef.get
      re <- rightRef.get
      out <- (le, re) match {
        case (Some(Exit.Success(a)), Some(Exit.Success(b))) => Eru.succeed((a, b))
        case (Some(Exit.Success(_)), Some(Exit.Failure(e))) =>
          lf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Success(_)), Some(Exit.Die(t))) =>
          lf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
        case (Some(Exit.Failure(e)), _) =>
          rf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Die(t)), _) =>
          rf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
        case _ => Eru.effect(throw new RuntimeException("zipPar interrupted"))
      }
    } yield out

  /** Races two effects in parallel and yields the first termination result.
    *
    * The winner can be a success, typed failure, or defect. The losing fiber is interrupted and
    * fully awaited before the race completes to ensure finalizers are run.
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    for {
      leftRef <- Ref.make(Option.empty[Exit[E1, A]])
      rightRef <- Ref.make(Option.empty[Exit[E2, B]])
      lf <- fork(fa)
      rf <- fork(fb)
      _ <- fork(lf.await.flatMap(ex => leftRef.set(Some(ex)))).flatMap(_ => Eru.unit)
      _ <- fork(rf.await.flatMap(ex => rightRef.set(Some(ex)))).flatMap(_ => Eru.unit)
      _ <- Eru.effect {
        Scheduler.pumpUntil { () =>
          val l = leftRef.get.unsafeRunSync()
          val r = rightRef.get.unsafeRunSync()
          l.isDefined || r.isDefined
        }
      }.attempt.flatMap(_ => Eru.unit)
      le <- leftRef.get
      re <- rightRef.get
      res <- (le, re) match {
        case (Some(Exit.Success(a)), _) =>
          rf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.succeed(Left(a)))
        case (_, Some(Exit.Success(b))) =>
          lf.interrupt(InterruptCause.Cancelled).flatMap(_ => lf.await).flatMap(_ => Eru.succeed(Right(b)))
        case (Some(Exit.Failure(e)), _) =>
          rf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (_, Some(Exit.Failure(e))) =>
          lf.interrupt(InterruptCause.Cancelled).flatMap(_ => lf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Die(t)), _) =>
          rf.interrupt(InterruptCause.Cancelled).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
        case (_, Some(Exit.Die(t))) =>
          lf.interrupt(InterruptCause.Cancelled).flatMap(_ => lf.await).flatMap(_ => Eru.effect(throw t))
        case _ => Eru.effect(throw new RuntimeException("race interrupted"))
      }
    } yield res

  /** A token that re-enables interruptibility inside a masked region. */
  trait Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A]
  }

  private object IdentityUnmask extends Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A] = fa
  }

  private object Scheduler {
    private val queue = scala.collection.mutable.Queue[() => Unit]()
    private var parkedFibers: Int = 0

    def schedule(thunk: () => Unit): Unit =
      queue.enqueue(thunk)

    def parkFiber(): Unit =
      parkedFibers += 1

    def unparkFiber(): Unit =
      parkedFibers = Math.max(0, parkedFibers - 1)

    def pumpUntil(done: () => Boolean): Unit = {
      while !done() || (parkedFibers > 0 && queue.nonEmpty) do {
        if queue.nonEmpty then {
          val task = queue.dequeue()
          task()
        } else if parkedFibers > 0 then {
          // Non-blocking wait when there are parked fibers waiting for external events
          try java.lang.Thread.sleep(1)
          catch { case _: InterruptedException => () }
        } else {
          // No parked fibers and condition not met, exit
          return
        }
      }
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

  /** Cooperative yield: suspends the current fiber and reschedules it on the next turn. */
  private val YieldMarker = "__eru_yield_now__"
  def yieldNow: Eru[Nothing, Unit] = Eru.unit.debug(YieldMarker)

  /** Makes a region uninterruptible (placeholder semantics for now). */
  def uninterruptible[E, A](fa: Eru[E, A]): Eru[E, A] = fa

  /** Masks interruption within a region, providing an Unmask to selectively restore
    * interruptibility (placeholder semantics for now).
    */
  def mask[E, A](k: Unmask => Eru[E, A]): Eru[E, A] = k(IdentityUnmask)

  /** Suspends for approximately the specified duration without blocking a thread.
    *
    * The scheduler remains responsive: completion is enqueued onto the event loop via a timer
    * callback, and this effect pumps the scheduler until the wakeup has been processed.
    *
    * @param duration
    *   the delay before completion
    * @return
    *   an effect that completes with Unit after the delay
    */
  def sleep(duration: Duration): Eru[Nothing, Unit] =
    Eru.effect {
      var completed = false
      Platform.timer.schedule(duration, () => {
        Scheduler.schedule(() => completed = true)
      })
      Scheduler.pumpUntil(() => completed)
      ()
    }.attempt.flatMap(_ => Eru.unit)

  /** Fails with a TimeoutException if the given effect does not complete within the duration.
    *
    * Implemented via race with [[sleep]]. If the timeout wins, the original effect is interrupted
    * and its finalizers are awaited by the race machinery before this returns.
    *
    * @param duration
    *   the maximum allowed duration
    * @param fa
    *   the effect to run with a timeout
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @return
    *   an effect that either succeeds with A or fails with TimeoutException
    */
  def timeout[E, A](
    duration: Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    race(fa, sleep(duration)).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.effect(throw new TimeoutException())
    }
  }

  /** Retry policy describing how to reschedule failures. */
  sealed trait Policy
  object Policy {

    /** Retry up to `n` times without delay. */
    final case class Recurs(n: Int) extends Policy

    /** Exponential backoff: base * 2^i, up to `maxRetries` attempts (not counting the initial try).
      */
    final case class Exponential(base: Duration, maxRetries: Int) extends Policy
  }

  /** Re-executes an effect on typed failure according to the given policy.
    *
    * Defects (Throwable) are propagated as defects; only typed failures participate in retry.
    *
    * @param policy
    *   the retry policy to apply
    * @param fa
    *   the effect to retry
    */
  def retry[E, A](policy: Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import Policy.*
    def delayFor(i: Int): Option[Duration] = policy match {
      case Recurs(n) => if (i < n) Some(Duration.ZERO) else None
      case Exponential(base, max) => if (i < max) Some(base.multipliedBy(1L << i)) else None
    }
    def loop(i: Int): Eru[E, A] =
      fa.recoverWith { case e =>
        delayFor(i) match {
          case Some(d) => sleep(d).flatMap(_ => loop(i + 1))
          case None => Eru.fail(e)
        }
      }
    loop(0)
  }

  private final class RuntimeFiber[E, A](val id: FiberId, fa: Eru[E, A], observer: Option[EruObserver])
      extends Fiber[E, A] {
    import Eru.Internals.View

    private var interrupted: Option[InterruptCause] = None
    private var exit0: Option[Exit[E, A]] = None

    // Simplified step-wise interpreter state using List-based stacks
    private var current: Eru[Any, Any] = fa.asInstanceOf[Eru[Any, Any]]
    private var conts: List[Any => Eru[Any, Any]] = Nil
    private var handlers: List[Handler] = Nil
    private var finalizers: List[() => Eru[Nothing, Unit]] = Nil

    // Handler types for error handling stack
    private sealed trait Handler
    private case class Recover(pf: PartialFunction[Any, Eru[Any, Any]]) extends Handler
    private case class MapErr(f: Any => Any) extends Handler
    private case object AttemptH extends Handler

    private def exitFromResult(result: Result[E, A]): Exit[E, A] =
      result match {
        case Result.Success(value) => Exit.Success(value)
        case Result.Failure(err) =>
          err match {
            case t: Throwable => Exit.Die(t)
            case e => Exit.Failure(e)
          }
      }

    private def collectEnsures[E, A](e0: Eru[E, A]): List[() => Eru[Nothing, Unit]] = {
      def loop[E1, A1](e: Eru[E1, A1]): List[() => Eru[Nothing, Unit]] =
        Eru.Internals.view(e) match {
          case View.VEnsure(source, fin) => fin :: loop(source)
          case View.VChain(source, _) => loop(source)
          case View.VRecoverWith(source, _) => loop(source)
          case View.VMapError(source, _) => loop(source)
          case View.VDebug(source, _) => loop(source)
          case View.VAttempt(source) => loop(source)
          case View.VZip(left, right) => loop(left) ++ loop(right)
          case _ => Nil
        }
      loop(e0)
    }

    private def drainFinalizers(): Unit = {
      // Execute finalizers in LIFO order (reverse of accumulation)
      finalizers.reverse.foreach { fin =>
        fin().attempt.unsafeRunSync()
      }
      finalizers = Nil
    }


    private def completeWith(exit: Exit[E, A]): Unit = {
      if (exit0.isEmpty) {
        val finalExit = interrupted match {
          case Some(cause) => Exit.Interrupt(id, cause)
          case None => exit
        }
        drainFinalizers()
        exit0 = Some(finalExit)
        observer.foreach(_.onEvent(EruEvent.FiberCompleted(id, finalExit)))
      }
    }

    private def scheduleIfPending(): Unit = {
      if (exit0.isEmpty) {
        Scheduler.schedule(() => run())
      }
    }

    private def handleFailure(error: Any): Unit = {
      var e: Any = error
      var hs = handlers
      var handled = false

      while (!handled && hs.nonEmpty) {
        hs.head match {
          case MapErr(f) =>
            e = f(e)
            hs = hs.tail
          case Recover(pf) if pf.isDefinedAt(e) =>
            handlers = hs.tail
            current = pf(e)
            handled = true
          case AttemptH =>
            handlers = hs.tail
            current = Eru.succeed(Result.Failure(e)).asInstanceOf[Eru[Any, Any]]
            handled = true
          case _ =>
            hs = hs.tail
        }
      }

      if (!handled) {
        completeWith(Exit.Failure(e.asInstanceOf[E]))
      } else {
        scheduleIfPending()
      }
    }

    private def handleSuccess(value: Any): Unit = {
      if (conts.nonEmpty) {
        val k = conts.head
        conts = conts.tail
        
        // Simple direct execution optimization: handle common Succeed case immediately
        try {
          k(value) match {
            case Eru.Succeed(nextValue) =>
              // Common case: continuation produced immediate success - continue directly
              if (conts.nonEmpty) {
                handleSuccess(nextValue)  // Tail-recursive call for chained simple operations
              } else {
                completeWith(Exit.Success(nextValue.asInstanceOf[A]))
              }
            case other =>
              // Complex case: schedule normally
              current = other.asInstanceOf[Eru[Any, Any]]
              scheduleIfPending()
          }
        } catch {
          case _: Throwable =>
            // Fallback: if anything goes wrong, use normal execution path
            current = k(value).asInstanceOf[Eru[Any, Any]]
            scheduleIfPending()
        }
      } else {
        completeWith(Exit.Success(value.asInstanceOf[A]))
      }
    }

    def run(): Unit = {
      if (exit0.nonEmpty) return

      // Check for interruption before each step
      interrupted match {
        case Some(cause) =>
          completeWith(Exit.Interrupt(id, cause))
          return
        case None => ()
      }

      Eru.Internals.view(current) match {
        case View.VSucceed(value) =>
          handleSuccess(value)

        case View.VFail(error) =>
          conts = Nil  // Clear continuations on failure
          handleFailure(error)

        case View.VEffect(thunk) =>
          thunk() match {
            case Right(value) => handleSuccess(value)
            case Left(t) => completeWith(Exit.Die(t).asInstanceOf[Exit[E, A]])
          }

        case View.VChain(source, f) =>
          // Limited chain unwinding optimization: process multiple Chain nodes with depth limit
          conts = f.asInstanceOf[Any => Eru[Any, Any]] :: conts
          current = source.asInstanceOf[Eru[Any, Any]]
          
          // Unwind nested Chain operations with a depth limit to prevent excessive overhead
          var unwinding = true
          var unwindDepth = 0
          val maxUnwindDepth = 10  // Limit unwinding to prevent stack buildup
          
          while (unwinding && unwindDepth < maxUnwindDepth) {
            Eru.Internals.view(current) match {
              case View.VChain(nextSource, nextF) =>
                conts = nextF.asInstanceOf[Any => Eru[Any, Any]] :: conts
                current = nextSource.asInstanceOf[Eru[Any, Any]]
                unwindDepth += 1
              case _ =>
                unwinding = false
            }
          }
          scheduleIfPending()

        case View.VMapChain(source, f) =>
          // Fused map chain: create a continuation that applies the composed function directly
          val mapCont: Any => Eru[Any, Any] = { value =>
            Eru.succeed(f.asInstanceOf[Any => Any](value)).asInstanceOf[Eru[Any, Any]]
          }
          conts = mapCont :: conts
          current = source.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VRecoverWith(source, pf) =>
          handlers = Recover(pf.asInstanceOf[PartialFunction[Any, Eru[Any, Any]]]) :: handlers
          current = source.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VMapError(source, f) =>
          handlers = MapErr(f.asInstanceOf[Any => Any]) :: handlers
          current = source.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VZip(left, right) =>
          val zipCont: Any => Eru[Any, Any] = { leftValue =>
            right.asInstanceOf[Eru[Any, Any]].map(rightValue => (leftValue, rightValue)).asInstanceOf[Eru[Any, Any]]
          }
          conts = zipCont :: conts
          current = left.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VAttempt(source) =>
          handlers = AttemptH :: handlers
          current = source.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VDebug(source, label) =>
          if (label() == YieldMarker) {
            // Yield: reschedule to allow other fibers to run
            current = source.asInstanceOf[Eru[Any, Any]]
            scheduleIfPending()
          } else {
            observer.foreach(_.onEvent(EruEvent.Step(ScopeId.fresh(), label())))
            current = source.asInstanceOf[Eru[Any, Any]]
            scheduleIfPending()
          }

        case View.VEnsure(source, finalizer) =>
          finalizers = finalizer :: finalizers
          current = source.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()

        case View.VSuspend(register) =>
          // Suspend implementation would go here if needed
          // For now, fallback to synchronous execution
          val res = observer match {
            case Some(_) =>
              val noop = new EruObserver { def onEvent(event: EruEvent): Unit = () }
              current.asInstanceOf[Eru[E, A]].attempt.unsafeRunSyncWith(noop)
            case None => current.asInstanceOf[Eru[E, A]].attempt.unsafeRunSync()
          }
          val out = exitFromResult(res)
          completeWith(out)
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
