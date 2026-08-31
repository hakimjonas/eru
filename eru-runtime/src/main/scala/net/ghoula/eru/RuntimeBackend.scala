package net.ghoula.eru

import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.prelude.*
import net.ghoula.eru.trace.EruTrace

/** Simple container for tracking child fibers in structured concurrency.
  *
  * Each entry pairs a child fiber with whether it is a daemon: containment applies to every child,
  * joining applies only to non-daemons.
  */
private final class FiberScope(
  val childFibers: ConcurrentLinkedQueue[(UnifiedFiber[?, ?], Boolean)]
)

/** Thread-local storage for structured concurrency fiber tracking. */
private object StructuredConcurrency {
  private val currentScope: ThreadLocal[Option[FiberScope]] = ThreadLocal.withInitial(() => None)

  def getCurrentScope: Option[FiberScope] = currentScope.get()
  def setCurrentScope(scope: Option[FiberScope]): Unit = currentScope.set(scope)

  /** Runs `action` in a new scope. On completion the scope unwinds in two phases: every child is
    * interrupted with the real parent identity and exit, then joined children are awaited. Daemons
    * are interrupted but not awaited — their finalizers run asynchronously.
    */
  def withNewScope[A](parentId: FiberId, parentExit: => Exit[Any, Any])(action: FiberScope => A): A = {
    val newScope = new FiberScope(new ConcurrentLinkedQueue[(UnifiedFiber[?, ?], Boolean)]())
    val oldScope = getCurrentScope
    setCurrentScope(Some(newScope))
    try action(newScope)
    finally {
      val children = scala.collection.mutable.ListBuffer.empty[(UnifiedFiber[?, ?], Boolean)]
      var child = Option(newScope.childFibers.poll())
      while (child.nonEmpty) {
        children += child.get
        child = Option(newScope.childFibers.poll())
      }

      children.foreach { case (fiber, _) =>
        try fiber.interrupt(InterruptCause.ParentTerminated(parentId, parentExit)).attempt.unsafeRunSync()
        catch { case _: Exception => () }
      }

      children.foreach { case (fiber, isDaemon) =>
        if (!isDaemon) {
          try fiber.await.attempt.unsafeRunSync()
          catch { case _: Exception => () }
        }
      }

      setCurrentScope(oldScope)
    }
  }

  /** Records a fiber in the current scope (or the runtime's root collection), pruning one completed
    * entry per add so long-lived scopes stay bounded; cleanup is amortized across operations
    * instead of a full drain.
    */
  def addChildFiber(
    fiber: UnifiedFiber[?, ?],
    isDaemon: Boolean,
    rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]
  ): Unit = {
    getCurrentScope match {
      case Some(scope) =>
        scope.childFibers.offer((fiber, isDaemon))
        cleanupOneCompletedScopeEntry(scope.childFibers)
      case None =>
        rootFibers match {
          case Some(queue) =>
            queue.offer(fiber)
            cleanupOneCompletedFiber(queue)
          case None => ()
        }
    }
  }

  private def cleanupOneCompletedScopeEntry(queue: ConcurrentLinkedQueue[(UnifiedFiber[?, ?], Boolean)]): Unit = {
    Option(queue.poll()).foreach { case (fiber, isDaemon) =>
      if (isStillActive(fiber)) queue.offer((fiber, isDaemon))
    }
  }

  private def cleanupOneCompletedFiber(queue: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]): Unit = {
    Option(queue.poll()).foreach { fiber =>
      if (isStillActive(fiber)) queue.offer(fiber)
    }
  }

  /** A fiber is still active while its completion latch has not counted down. `currentState` never
    * transitions (it is fixed at construction), so the latch is the completion signal.
    */
  private def isStillActive(fiber: UnifiedFiber[?, ?]): Boolean =
    fiber.currentState match {
      case UnifiedFiberState.Active(latch, _, _, _, _, _) => latch.getCount > 0L
      case UnifiedFiberState.Completed(_) => false
    }

  def cleanupRootFibers(rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]): Unit = {
    rootFibers match {
      case Some(queue) =>
        val fibersToCleanup = scala.collection.mutable.ListBuffer.empty[UnifiedFiber[?, ?]]
        var fiber = Option(queue.poll())
        while (fiber.nonEmpty) {
          fibersToCleanup += fiber.get
          fiber = Option(queue.poll())
        }

        fibersToCleanup.foreach { fiberToCleanup =>
          try {
            fiberToCleanup
              .interrupt(InterruptCause.ParentTerminated(FiberId.Root, Exit.Success(())))
              .attempt
              .unsafeRunSync()
            fiberToCleanup.await.attempt.unsafeRunSync()
          } catch {
            case _: Exception => ()
          }
        }
      case None => ()
    }
  }
}

/** Runtime backend implementation using Scala 3 enums.
  *
  * Virtual Threads backend: fibers run on Java virtual threads. Structured scopes track child
  * fibers and interrupt them when the parent's scope unwinds.
  */
private[eru] object RuntimeBackend {

  /** Launches a computation as a fiber using this backend's execution model.
    *
    * The fiber runs on a fresh virtual thread, which does not inherit ThreadLocal values, so the
    * parent scope, timer, and trace context are captured and re-set on the child — a fork inside
    * `.traced(...)` therefore does not silently start a fresh trace lineage.
    *
    * When the fiber is interrupted, the cause recorded on the fiber (before its thread was
    * interrupted) is carried into the exit, and the exit names the fiber's real id —
    * `executeWithFinalizers` only sees an opaque `InterruptedException` and would otherwise report
    * a generic cancellation with a fresh id.
    *
    * @param fa
    *   the computation to execute
    * @param observer
    *   optional observer for fiber lifecycle events
    * @param rootFibers
    *   optional queue for tracking root-level fibers (for test isolation)
    * @param childTimer
    *   optional timer service for the forked fiber
    * @return
    *   an effect that yields a fiber handle for the launched computation
    */
  def fork[E, A](
    fa: Eru[E, A],
    observer: Option[EruObserver] = None,
    rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None,
    childTimer: Option[TimerService] = None,
    isDaemon: Boolean = false
  ): Eru[Nothing, Fiber[E, A]] = {
    import Eru.Internals.View.*
    Eru.Internals.view(fa) match {
      case VSucceed(value) =>
        Eru.effectTotal {
          val id = FiberId.fresh()
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
          val exit = Exit.Success(value)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
          UnifiedFiber.completed(id, exit): Fiber[E, A]
        }.attempt.map {
          case Result.Success(fiber) => fiber
          case Result.Failure(t) =>
            val id = FiberId.fresh()
            val exit = Exit.Die(t)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            UnifiedFiber.completed(id, exit)
        }

      case VFail(error) =>
        Eru.effectTotal {
          val id = FiberId.fresh()
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
          val exit = Exit.Failure(error)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
          UnifiedFiber.completed(id, exit): Fiber[E, A]
        }.attempt.map {
          case Result.Success(fiber) => fiber
          case Result.Failure(t) =>
            val id = FiberId.fresh()
            val exit = Exit.Die(t)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            UnifiedFiber.completed(id, exit)
        }

      case VMapChain(source, f) =>
        Eru.Internals.view(source) match {
          case VSucceed(value) =>
            Eru.effectTotal {
              val id = FiberId.fresh()
              observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
              val mappedValue = f(value)
              val exit = Exit.Success(mappedValue)
              observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
              UnifiedFiber.completed(id, exit): Fiber[E, A]
            }.attempt.map {
              case Result.Success(fiber) => fiber
              case Result.Failure(t) =>
                val id = FiberId.fresh()
                val exit = Exit.Die(t)
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
                UnifiedFiber.completed(id, exit)
            }
          case _ =>
            Eru.effectTotal {
              val id = FiberId.fresh()
              val fiber = UnifiedFiber.active[E, A](id, observer)
              val parentScope = StructuredConcurrency.getCurrentScope
              val parentTimer = childTimer.orElse(TimerService.get)
              val parentTrace = EruTrace.getCurrentContext

              StructuredConcurrency.addChildFiber(fiber, isDaemon, rootFibers)

              observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

              Thread.startVirtualThread { () =>
                UnifiedFiber.setThread(fiber, Thread.currentThread())
                StructuredConcurrency.setCurrentScope(parentScope)
                TimerService.setCurrent(parentTimer)
                EruTrace.setCurrentContext(parentTrace)

                try {
                  var parentExit: Exit[Any, Any] = Exit.Success(())
                  StructuredConcurrency.withNewScope(id, parentExit) { _ =>
                    val (exit0, finalizers) = Eru.executeWithFinalizers(fa)
                    val exit = exit0 match {
                      case Exit.Interrupt(_, _) =>
                        val cause = fiber.currentState match {
                          case UnifiedFiberState.Active(_, _, _, _, _, interruptCauseRef) =>
                            interruptCauseRef.get().getOrElse(InterruptCause.Cancelled())
                          case UnifiedFiberState.Completed(_) => InterruptCause.Cancelled()
                        }
                        Exit.Interrupt(id, cause)
                      case other => other
                    }
                    val widened: Exit[Any, Any] = exit match {
                      case Exit.Success(a) => Exit.Success(a)
                      case Exit.Failure(e) => Exit.Failure(e)
                      case Exit.Die(t) => Exit.Die(t)
                      case Exit.Interrupt(i, c) => Exit.Interrupt(i, c)
                    }
                    parentExit = widened

                    finalizers.foreach { finalizer =>
                      try finalizer().unsafeRunSync()
                      catch case _: Exception => ()
                    }

                    UnifiedFiber.complete(fiber, exit)
                  }
                } catch {
                  case _: InterruptedException =>
                    val cause = fiber.currentState match {
                      case UnifiedFiberState.Active(_, _, _, _, _, interruptCauseRef) =>
                        interruptCauseRef.get().getOrElse(InterruptCause.Cancelled())
                      case UnifiedFiberState.Completed(_) => InterruptCause.Cancelled()
                    }
                    UnifiedFiber.complete(fiber, Exit.Interrupt(id, cause))
                  case t: Throwable =>
                    UnifiedFiber.complete(fiber, Exit.Die(t))
                }
              }

              fiber: Fiber[E, A]
            }.attempt.map {
              case Result.Success(fiber) => fiber
              case Result.Failure(t) =>
                val id = FiberId.fresh()
                val exit = Exit.Die(t)
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
                UnifiedFiber.completed(id, exit)
            }
        }

      case _ =>
        Eru.effectTotal {
          val id = FiberId.fresh()
          val fiber = UnifiedFiber.active[E, A](id, observer)
          val parentScope = StructuredConcurrency.getCurrentScope
          val parentTimer = childTimer.orElse(TimerService.get)
          val parentTrace = EruTrace.getCurrentContext

          StructuredConcurrency.addChildFiber(fiber, isDaemon, rootFibers)

          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

          Thread.startVirtualThread { () =>
            UnifiedFiber.setThread(fiber, Thread.currentThread())
            StructuredConcurrency.setCurrentScope(parentScope)
            TimerService.setCurrent(parentTimer)
            EruTrace.setCurrentContext(parentTrace)

            try {
              var parentExit: Exit[Any, Any] = Exit.Success(())
              StructuredConcurrency.withNewScope(id, parentExit) { _ =>
                val (exit0, finalizers) = Eru.executeWithFinalizers(fa)
                val exit = exit0 match {
                  case Exit.Interrupt(_, _) =>
                    val cause = fiber.currentState match {
                      case UnifiedFiberState.Active(_, _, _, _, _, interruptCauseRef) =>
                        interruptCauseRef.get().getOrElse(InterruptCause.Cancelled())
                      case UnifiedFiberState.Completed(_) => InterruptCause.Cancelled()
                    }
                    Exit.Interrupt(id, cause)
                  case other => other
                }
                val widened: Exit[Any, Any] = exit match {
                  case Exit.Success(a) => Exit.Success(a)
                  case Exit.Failure(e) => Exit.Failure(e)
                  case Exit.Die(t) => Exit.Die(t)
                  case Exit.Interrupt(i, c) => Exit.Interrupt(i, c)
                }
                parentExit = widened

                finalizers.foreach { finalizer =>
                  try finalizer().unsafeRunSync()
                  catch case _: Exception => ()
                }

                UnifiedFiber.complete(fiber, exit)
              }
            } catch {
              case _: InterruptedException =>
                val cause = fiber.currentState match {
                  case UnifiedFiberState.Active(_, _, _, _, _, interruptCauseRef) =>
                    interruptCauseRef.get().getOrElse(InterruptCause.Cancelled())
                  case UnifiedFiberState.Completed(_) => InterruptCause.Cancelled()
                }
                UnifiedFiber.complete(fiber, Exit.Interrupt(id, cause))
              case t: Throwable =>
                UnifiedFiber.complete(fiber, Exit.Die(t))
            }
          }

          fiber: Fiber[E, A]
        }.attempt.map {
          case Result.Success(fiber) => fiber
          case Result.Failure(t) =>
            val id = FiberId.fresh()
            val exit = Exit.Die(t)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            UnifiedFiber.completed(id, exit)
        }
    }

  }

  /** Races two computations, returning the result of whichever completes first.
    *
    * Race threads re-set the parent scope, timer, and trace context captured on the caller thread
    * (virtual threads do not inherit ThreadLocal values). An interrupted participant resolves the
    * race with an interruption outcome; without this, a race where both sides are interrupted never
    * releases the latch.
    *
    * @param fa
    *   the first computation to race
    * @param fb
    *   the second computation to race
    * @return
    *   an effect yielding Either[A, B] with the winner's result
    */
  def race[E1, E2, A, B](
    fa: Eru[E1, A],
    fb: Eru[E2, B],
    childTimer: Option[TimerService] = None
  ): Eru[E1 | E2 | Throwable, Either[A, B]] =
    Eru.effectTotal {
      import java.util.concurrent.atomic.AtomicReference
      import java.util.concurrent.CountDownLatch

      val resultRef = new AtomicReference[Option[() => Eru[E1 | E2 | Throwable, Either[A, B]]]](None)
      val latch = new CountDownLatch(1)
      val leftThreadRef = new AtomicReference[Option[Thread]](None)
      val rightThreadRef = new AtomicReference[Option[Thread]](None)
      val parentScope = StructuredConcurrency.getCurrentScope
      val parentTimer = childTimer.orElse(TimerService.get)
      val parentTrace = EruTrace.getCurrentContext

      def trySet(thunk: () => Eru[E1 | E2 | Throwable, Either[A, B]], cancelOther: () => Unit): Unit =
        if (resultRef.compareAndSet(None, Some(thunk))) {
          cancelOther()
          latch.countDown()
        }

      val runLeft: Runnable = () => {
        leftThreadRef.set(Some(Thread.currentThread()))
        StructuredConcurrency.setCurrentScope(parentScope)
        TimerService.setCurrent(parentTimer)
        EruTrace.setCurrentContext(parentTrace)

        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => ()
        }
        exit match {
          case Exit.Success(a) =>
            trySet(() => Eru.succeed(Left(a)), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e1) =>
            trySet(() => Eru.fail(e1), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effectTotal(throw t), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, _) =>
            trySet(
              () => Eru.interruptibleBlocking { throw new InterruptedException("Race participant interrupted") },
              () => rightThreadRef.get().foreach(_.interrupt())
            )
        }
      }

      val runRight: Runnable = () => {
        rightThreadRef.set(Some(Thread.currentThread()))
        StructuredConcurrency.setCurrentScope(parentScope)
        TimerService.setCurrent(parentTimer)
        EruTrace.setCurrentContext(parentTrace)

        val (exit, finalizers) = Eru.executeWithFinalizers(fb)
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => ()
        }
        exit match {
          case Exit.Success(b) =>
            trySet(() => Eru.succeed(Right(b)), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e2) =>
            trySet(() => Eru.fail(e2), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effectTotal(throw t), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, _) =>
            trySet(
              () => Eru.interruptibleBlocking { throw new InterruptedException("Race participant interrupted") },
              () => leftThreadRef.get().foreach(_.interrupt())
            )
        }
      }

      Thread.startVirtualThread(runLeft)
      Thread.startVirtualThread(runRight)
      try {
        latch.await()
        resultRef.get()
      } catch {
        case ie: InterruptedException =>
          Some(() =>
            Eru.interruptibleBlocking {
              throw ie
            }
          )
      }
    }.attempt.flatMap {
      case Result.Success(Some(thunk)) => thunk()
      case Result.Success(None) => Eru.effectTotal(throw new IllegalStateException("race: no result set"))
      case Result.Failure(t) => Eru.effectTotal(throw t)
    }

  /** Sleeps for the specified duration, truncated to milliseconds.
    *
    * Negative durations throw `IllegalArgumentException` from `Thread.sleep`; callers such as
    * `EruRuntime.sleep` guard against them.
    *
    * @param duration
    *   the time to sleep
    * @return
    *   an effect that completes after the duration
    */
  def sleep(duration: java.time.Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      Thread.sleep(duration.toMillis)
    }.attempt.flatMap(_ => Eru.unit)

  /** Applies a timeout to a computation.
    *
    * @param duration
    *   the timeout duration
    * @param childTimer
    *   optional timer service used for the timeout race
    * @param fa
    *   the computation to timeout
    * @return
    *   an effect that fails with TimeoutException if duration exceeded
    */
  def timeout[E, A](
    duration: java.time.Duration,
    childTimer: Option[TimerService] = None
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import Eru.Internals.View.*
    Eru.Internals.view(fa) match {
      case VSucceed(value) =>
        Eru.succeed(value)
      case VFail(error) =>
        Eru.fail(error)
      case _ =>
        race(fa, sleep(duration), childTimer).flatMap {
          case Left(a) => Eru.succeed(a)
          case Right(_) =>
            Eru.fail(new java.util.concurrent.TimeoutException(s"Operation timed out after $duration"))
        }
    }
  }

  /** Batch fork operation for improved performance.
    *
    * Unlike `fork`, forked fibers run without a structured scope: batch forking skips the scope
    * machinery to reduce overhead significantly, so children are tracked as root fibers only.
    *
    * @param effects
    *   list of effects to fork
    * @param rootFibers
    *   optional queue for root fiber tracking
    * @return
    *   an effect yielding all created fibers
    */
  def forkBatch[E, A](
    effects: List[Eru[E, A]],
    rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None
  ): Eru[Nothing, List[Fiber[E, A]]] =
    Eru.effectTotal {
      effects.map { fa =>
        val id = FiberId.fresh()
        val fiber = UnifiedFiber.active[E, A](id)

        StructuredConcurrency.addChildFiber(fiber, isDaemon = false, rootFibers)

        Thread.startVirtualThread { () =>
          UnifiedFiber.setThread(fiber, Thread.currentThread())

          val (exit, finalizers) = Eru.executeWithFinalizers(fa)

          finalizers.foreach { finalizer =>
            try finalizer().unsafeRunSync()
            catch case _: Exception => ()
          }

          UnifiedFiber.complete(fiber, exit)
        }

        fiber: Fiber[E, A]
      }
    }

  /** Awaits multiple fibers in batch and returns their exits.
    *
    * @param fibers
    *   the fibers to await
    * @return
    *   an effect yielding all exits
    */
  def awaitAll[E, A](fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
    Eru.traverse(fibers)(_.await)

  /** Interrupts and awaits the tracked root fibers.
    *
    * Called manually (via `EruRuntime.cleanup()` or test suites); not called automatically by
    * `unsafeRunSync`.
    *
    * @param rootFibers
    *   optional queue of root-level fibers to clean up (for test isolation)
    */
  def cleanup(rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None): Unit = {
    StructuredConcurrency.cleanupRootFibers(rootFibers)
  }
}
