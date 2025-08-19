package net.ghoula.eru

import java.time.Duration

/** Single-threaded fiber runtime for Eru effects.
  *
  * This runtime provides a complete fiber-based execution model with proper interruption support,
  * resource management, and cooperative scheduling. Effects are executed on lightweight fibers
  * managed by an internal scheduler queue. The runtime supports:
  *
  * - Fiber forking with `fork` - creates new fibers for concurrent execution
  * - Interruption masking with `uninterruptible` and `mask`
  * - Proper suspension/resumption for asynchronous operations
  * - Resource cleanup through finalizers
  * - Cooperative yielding and timeout support
  * - Parallel composition with `zipPar` and `race`
  *
  * While single-threaded, this runtime provides the full semantic foundation for
  * future multi-threaded implementations and serves as the reference implementation
  * for Eru's effect system.
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
          lf.interrupt(InterruptCause.Cancelled(Some("zipPar partner failed"))).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Success(_)), Some(Exit.Die(t))) =>
          lf.interrupt(InterruptCause.Cancelled(Some("zipPar partner died"))).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
        case (Some(Exit.Failure(e)), _) =>
          rf.interrupt(InterruptCause.Cancelled(Some("zipPar partner failed"))).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Die(t)), _) =>
          rf.interrupt(InterruptCause.Cancelled(Some("zipPar partner died"))).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
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
          rf.interrupt(InterruptCause.Cancelled(Some("race lost - left won"))).flatMap(_ => rf.await).flatMap(_ => Eru.succeed(Left(a)))
        case (_, Some(Exit.Success(b))) =>
          lf.interrupt(InterruptCause.Cancelled(Some("race lost - right won"))).flatMap(_ => lf.await).flatMap(_ => Eru.succeed(Right(b)))
        case (Some(Exit.Failure(e)), _) =>
          rf.interrupt(InterruptCause.Cancelled(Some("race terminated - left failed"))).flatMap(_ => rf.await).flatMap(_ => Eru.fail(e))
        case (_, Some(Exit.Failure(e))) =>
          lf.interrupt(InterruptCause.Cancelled(Some("race terminated - right failed"))).flatMap(_ => lf.await).flatMap(_ => Eru.fail(e))
        case (Some(Exit.Die(t)), _) =>
          rf.interrupt(InterruptCause.Cancelled(Some("race terminated - left died"))).flatMap(_ => rf.await).flatMap(_ => Eru.effect(throw t))
        case (_, Some(Exit.Die(t))) =>
          lf.interrupt(InterruptCause.Cancelled(Some("race terminated - right died"))).flatMap(_ => lf.await).flatMap(_ => Eru.effect(throw t))
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

  /** Effect that runs its source with interruption disabled */
  private final class UninterruptibleEffect[E, A](val source: Eru[E, A]) extends Eru[E, A] {
    def attempt: Eru[Nothing, Result[E, A]] = source.attempt
    override def toString: String = s"Uninterruptible($source)"
  }

  /** Effect that runs its source with interruption masked, providing unmask capability */
  private final class MaskEffect[E, A](val k: Unmask => Eru[E, A]) extends Eru[E, A] {
    def attempt: Eru[Nothing, Result[E, A]] = k(IdentityUnmask).attempt
    override def toString: String = s"Mask($k)"
  }

  /** Unmask implementation that can restore interruptibility */
  private final class RuntimeUnmask(originalState: Boolean) extends Unmask {
    def apply[E, A](fa: Eru[E, A]): Eru[E, A] = new RestoreInterruptibilityEffect(fa, originalState)
  }

  /** Effect that temporarily restores a specific interruptibility state */
  private final class RestoreInterruptibilityEffect[E, A](val source: Eru[E, A], val restoreState: Boolean) extends Eru[E, A] {
    def attempt: Eru[Nothing, Result[E, A]] = source.attempt
    override def toString: String = s"RestoreInterruptibility($source, $restoreState)"
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

  /** Makes a region uninterruptible.
    *
    * During execution of the provided effect, interruption is disabled. If the fiber
    * is interrupted while uninterruptible, the interruption will be recorded but
    * not acted upon until interruptibility is restored.
    *
    * @param fa the effect to run without interruption
    * @return an effect that runs fa with interruption disabled
    */
  def uninterruptible[E, A](fa: Eru[E, A]): Eru[E, A] = {
    new UninterruptibleEffect(fa)
  }

  /** Masks interruption within a region, providing an Unmask to selectively restore
    * interruptibility.
    *
    * The provided function receives an Unmask token that can be used to temporarily
    * restore interruptibility within the masked region. This is useful for operations
    * that need to be interruptible within an otherwise uninterruptible context.
    *
    * @param k function that receives an Unmask and produces an effect
    * @return an effect that runs with interruption masked
    */
  def mask[E, A](k: Unmask => Eru[E, A]): Eru[E, A] = new MaskEffect(k)

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
      case Right(_) => Eru.effect(throw new TimeoutException(s"Operation timed out after ${duration}"))
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

  /** Internal fiber implementation for the single-threaded runtime.
    *
    * This class manages the execution state of a fiber, including:
    * - current: The current effect being executed
    * - conts: Stack of continuations (functions to apply to successful results)
    * - handlers: Stack of error handlers (recovery functions, mapError, attempt)
    * - finalizers: Stack of cleanup actions to run on completion or interruption
    * - interruptible: Whether this fiber can be interrupted (managed by mask/uninterruptible)
    * - interrupted: The interruption cause if this fiber has been interrupted
    * - exit0: The final exit result once the fiber completes
    *
    * The main execution happens in the run() method which interprets the effect DSL
    * step by step, managing stacks and scheduling continuations appropriately.
    */
  private final class RuntimeFiber[E, A](val id: FiberId, fa: Eru[E, A], observer: Option[EruObserver])
      extends Fiber[E, A] {
    import scala.annotation.tailrec
    import Eru.Internals.View

    private var interrupted: Option[InterruptCause] = None
    private var exit0: Option[Exit[E, A]] = None
    private var interruptible: Boolean = true

    // Simplified step-wise interpreter state using List-based stacks
    private var current: Eru[Any, Any] = fa.asInstanceOf[Eru[Any, Any]]
    private var conts: List[Any => Eru[Any, Any]] = Nil
    private var handlers: List[Handler] = Nil
    private var finalizers: List[() => Eru[Nothing, Unit]] = Nil

    @tailrec
    private def runLoop[E1, A1](eru0: Eru[E1, A1], cont: A1 => Eru[Any, Any]): Unit = {
      // Pure fast path: evaluate fully pure chains without yielding to scheduler
      try {
        Eru.Internals.tryEvalPure(eru0) match {
          case Some(v) =>
            current = cont(v)
            scheduleIfPending()
            return
          case None => ()
        }
      } catch {
        case t: Throwable =>
          completeWith(Exit.Die(t).asInstanceOf[Exit[E, A]])
          return
      }

      Eru.Internals.view(eru0) match {
        case View.VChain[E1a, From, To](source, f) =>
          val cont2: From => Eru[Any, Any] = (a: From) => f(a).flatMap(cont)
          runLoop[E1a, From](source, cont2)
        case View.VChain2[E1a, From, Mid, To](source, f1, f2) =>
          val cont2: From => Eru[Any, Any] = (a: From) => f1(a).flatMap(mid => f2(mid).flatMap(cont))
          runLoop[E1a, From](source, cont2)
        case View.VChain3[E1a, From, Mid1, Mid2, To](source, f1, f2, f3) =>
          val cont2: From => Eru[Any, Any] = (a: From) => f1(a).flatMap(b1 => f2(b1).flatMap(b2 => f3(b2).flatMap(cont)))
          runLoop[E1a, From](source, cont2)
        case View.VMapChain[E1a, From, To](source, f) =>
          val cont2: From => Eru[Any, Any] = (a: From) => Eru.succeed(f(a)).flatMap(cont)
          runLoop[E1a, From](source, cont2)
        case View.VSucceed(value) =>
          current = cont(value)
          scheduleIfPending()
        case _ =>
          val k: Any => Eru[Any, Any] = (x: Any) => cont(x.asInstanceOf[A1])
          conts = k :: conts
          current = eru0.asInstanceOf[Eru[Any, Any]]
          scheduleIfPending()
      }
    }

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

    /** Sets the interruptible flag and returns the previous value */
    private def setInterruptible(value: Boolean): Boolean = {
      val prev = interruptible
      interruptible = value
      prev
    }

    /** Gets the current interruptible flag */
    private def isInterruptible: Boolean = interruptible

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

      // Check for interruption before each step, but only if interruptible
      if (interruptible) {
        interrupted match {
          case Some(cause) =>
            completeWith(Exit.Interrupt(id, cause))
            return
          case None => ()
        }
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

        case View.VChain(_, _) =>
          val idCont: Any => Eru[Any, Any] = (t: Any) => Eru.succeed(t)
          runLoop(current.asInstanceOf[Eru[Any, Any]], idCont)

        case View.VChain2(_, _, _) =>
          val idCont: Any => Eru[Any, Any] = (t: Any) => Eru.succeed(t)
          runLoop(current.asInstanceOf[Eru[Any, Any]], idCont)

        case View.VChain3(_, _, _, _) =>
          val idCont: Any => Eru[Any, Any] = (t: Any) => Eru.succeed(t)
          runLoop(current.asInstanceOf[Eru[Any, Any]], idCont)

        case View.VMapChain(_, _) =>
          val idCont: Any => Eru[Any, Any] = (t: Any) => Eru.succeed(t)
          runLoop(current.asInstanceOf[Eru[Any, Any]], idCont)

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
          // Proper suspend implementation: park the fiber and register callback to unparkfiber
          Scheduler.parkFiber()
          val callback: Either[Any, Any] => Unit = { result =>
            result match {
              case Right(value) =>
                handleSuccess(value)
              case Left(error) =>
                handleFailure(error)
            }
            Scheduler.unparkFiber()
          }
          // Register the callback - when it's invoked, it will resume this fiber
          register(callback)

        // Handle interruption masking effects
        case _ if current.isInstanceOf[UninterruptibleEffect[_, _]] =>
          val uninterruptible = current.asInstanceOf[UninterruptibleEffect[Any, Any]]
          val previousState = setInterruptible(false)
          // Use ensure to restore the previous interruptible state
          current = uninterruptible.source.ensure(Eru.effect { setInterruptible(previousState) }.attempt.flatMap(_ => Eru.unit))
          scheduleIfPending()

        case _ if current.isInstanceOf[MaskEffect[_, _]] =>
          val maskEffect = current.asInstanceOf[MaskEffect[Any, Any]]
          val previousState = setInterruptible(false)
          val unmask = new RuntimeUnmask(previousState)
          current = maskEffect.k(unmask).ensure(Eru.effect { setInterruptible(previousState) }.attempt.flatMap(_ => Eru.unit))
          scheduleIfPending()

        case _ if current.isInstanceOf[RestoreInterruptibilityEffect[_, _]] =>
          val restore = current.asInstanceOf[RestoreInterruptibilityEffect[Any, Any]]
          val previousState = setInterruptible(restore.restoreState)
          current = restore.source.ensure(Eru.effect { setInterruptible(previousState) }.attempt.flatMap(_ => Eru.unit))
          scheduleIfPending()
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

/** Extension methods providing runtime-dependent timeout and retry functionality for `Eru[E, A]`.
  *
  * These methods follow the principle of radical ergonomics, making powerful concurrent operations
  * like timeouts and retries discoverable as natural extensions of the Eru type itself when using
  * the runtime module. They integrate seamlessly with the concurrent runtime infrastructure.
  */
extension [E, A](eru: Eru[E, A]) {

  /** Fails with a TimeoutException if this effect does not complete within the specified duration.
    *
    * This method provides a convenient, discoverable way to add timeouts to any Eru effect.
    * The timeout is implemented via racing with a sleep effect, ensuring proper interruption
    * and finalizer execution if the timeout wins.
    *
    * @param duration
    *   the maximum allowed duration
    * @return
    *   an effect that either succeeds with A or fails with TimeoutException
    */
  def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    EruRuntime.timeout(duration)(eru)
  }

  /** Fails with a TimeoutException if this effect does not complete within the specified duration,
    * or returns the fallback value instead of failing.
    *
    * This variant provides graceful degradation when timeouts occur, allowing applications to
    * continue with a reasonable default rather than failing.
    *
    * @param duration
    *   the maximum allowed duration
    * @param fallback
    *   the value to return if timeout occurs
    * @return
    *   an effect that either succeeds with A or the fallback value
    */
  def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1): Eru[E | Throwable, A1] = {
    EruRuntime.timeout(duration)(eru).recover {
      case _: java.util.concurrent.TimeoutException => fallback
    }
  }

  /** Retries this effect according to the specified policy when it fails with a typed error.
    *
    * This method provides a discoverable way to add retry logic to any Eru effect. Only typed
    * failures participate in retry; defects (Throwables) are propagated immediately without retry.
    *
    * @param policy
    *   the retry policy defining retry count and delay strategy
    * @return
    *   an effect that retries on typed failure according to the policy
    */
  def retry(policy: EruRuntime.Policy): Eru[E, A] = {
    EruRuntime.retry(policy)(eru)
  }

  /** Retries this effect up to the specified number of times without delay.
    *
    * This is a convenience method for simple retry scenarios where you just want to retry
    * a fixed number of times immediately upon failure.
    *
    * @param maxRetries
    *   the maximum number of retry attempts (not counting the initial try)
    * @return
    *   an effect that retries up to maxRetries times on typed failure
    */
  def retryN(maxRetries: Int): Eru[E, A] = {
    EruRuntime.retry(EruRuntime.Policy.Recurs(maxRetries))(eru)
  }

  /** Retries this effect with exponential backoff starting from the base duration.
    *
    * This method implements a common retry pattern with exponential backoff, where each retry
    * waits longer than the previous one (base * 2^attempt).
    *
    * @param baseDuration
    *   the initial delay duration
    * @param maxRetries
    *   the maximum number of retry attempts
    * @return
    *   an effect that retries with exponential backoff on typed failure
    */
  def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int): Eru[E, A] = {
    EruRuntime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(eru)
  }

  /** Runs two effects in parallel, combining their results into a tuple.
    *
    * This provides a discoverable way to run effects in parallel. If either effect fails,
    * the other is interrupted and the failure is propagated.
    *
    * @param other
    *   the effect to run in parallel with this one
    * @return
    *   an effect that produces a tuple of both results
    */
  def zipPar[E1 >: E, B](other: Eru[E1, B]): Eru[E1 | Throwable, (A, B)] = {
    EruRuntime.zipPar(eru, other)
  }

  /** Races this effect against another, returning the first to complete.
    *
    * This provides a discoverable way to race effects. The winner can be either success
    * or failure. The losing effect is interrupted and fully awaited.
    *
    * @param other
    *   the effect to race against this one
    * @return
    *   an effect that produces Either[A, B] representing which effect won
    */
  def race[E1 >: E, B](other: Eru[E1, B]): Eru[E1 | Throwable, Either[A, B]] = {
    EruRuntime.race(eru, other)
  }

  /** Forks this effect into a fiber, returning the fiber immediately.
    *
    * This provides a discoverable way to run effects concurrently. The fiber can be
    * awaited or interrupted later.
    *
    * @return
    *   an effect that produces a Fiber representing the concurrent computation
    */
  def fork: Eru[Nothing, Fiber[E, A]] = {
    EruRuntime.fork(eru)
  }

  /** Forks this effect with an observer for debugging and monitoring.
    *
    * This variant allows you to observe fiber lifecycle events and execution steps.
    *
    * @param observer
    *   the observer to receive lifecycle events
    * @return
    *   an effect that produces a Fiber representing the concurrent computation
    */
  def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] = {
    EruRuntime.forkWithObserver(eru, observer)
  }
}
