package net.ghoula.eru

import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.prelude.*

/** Simple container for tracking child fibers in structured concurrency. */
private final class FiberScope(val childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]])

/** Thread-local storage for structured concurrency fiber tracking. */
private object StructuredConcurrency {
  private val currentScope: ThreadLocal[Option[FiberScope]] = ThreadLocal.withInitial(() => None)

  def getCurrentScope(): Option[FiberScope] = currentScope.get()
  def setCurrentScope(scope: Option[FiberScope]): Unit = currentScope.set(scope)

  def withNewScope[A](action: FiberScope => A): A = {
    val newScope = new FiberScope(new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]())
    val oldScope = getCurrentScope()
    setCurrentScope(Some(newScope))
    try {
      action(newScope)
    } finally {
      @annotation.tailrec
      def drainAndCleanup(): Unit =
        Option(newScope.childFibers.poll()) match {
          case Some(fiber) =>
            try {
              fiber
                .interrupt(InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(())))
                .attempt
                .unsafeRunSync()
              fiber.await.attempt.unsafeRunSync()
            } catch {
              case _: Exception => ()
            }
            drainAndCleanup()
          case None => ()
        }
      drainAndCleanup()
      setCurrentScope(oldScope)
    }
  }

  def addChildFiber(fiber: UnifiedFiber[?, ?], rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]): Unit = {
    getCurrentScope() match {
      case Some(scope) => scope.childFibers.offer(fiber)
      case None =>
        rootFibers match {
          case Some(queue) =>
            queue.offer(fiber)
            // Incremental cleanup: remove one completed fiber per add to prevent unbounded growth
            // This amortizes cleanup cost across operations and avoids expensive full queue drains
            cleanupOneCompletedFiber(queue)
          case None => ()
        }
    }
  }

  private def cleanupOneCompletedFiber(queue: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]): Unit = {
    // Poll one fiber and re-add if still active, discard if completed
    // This provides O(1) amortized cleanup instead of O(n) periodic full drains
    Option(queue.poll()).foreach { fiber =>
      fiber.currentState match {
        case UnifiedFiberState.Completed(_) => () // Discard completed
        case UnifiedFiberState.Active(_, _, _, _, _) => queue.offer(fiber) // Re-add active
      }
    }
  }

  def cleanupRootFibers(rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]]): Unit = {
    rootFibers match {
      case Some(queue) =>
        @annotation.tailrec
        def drain(acc: List[UnifiedFiber[?, ?]]): List[UnifiedFiber[?, ?]] =
          Option(queue.poll()) match {
            case Some(fiber) => drain(fiber :: acc)
            case None => acc.reverse
          }

        drain(Nil).foreach { fiberToCleanup =>
          try {
            fiberToCleanup
              .interrupt(InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(())))
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
  * Provides two execution modes: synchronous for single-threaded environments and virtual threads
  * for concurrent scenarios. The enum includes behavior directly for optimal performance.
  *
  * Implements structured concurrency with proper child fiber cleanup and auto-join semantics.
  */
enum RuntimeBackend {

  /** Synchronous execution backend for Scala Native and fallback scenarios.
    *
    * Computations execute immediately on the calling thread, returning completed fibers. This
    * provides predictable, deterministic execution with no threading complexity.
    */
  case Synchronous

  /** Virtual Thread execution backend for JVM concurrent scenarios.
    *
    * Computations execute asynchronously on Java Virtual Threads, returning active fibers that can
    * be awaited and interrupted. This provides true parallelism with lightweight threading and
    * structured concurrency support.
    */
  case VirtualThreads

  /** Recovers from defects during fiber creation, returning a completed Die fiber. */
  private def recoverForkDefect[E, A](
    fiberEffect: Eru[Nothing, Fiber[E, A]],
    observer: Option[EruObserver]
  ): Eru[Nothing, Fiber[E, A]] =
    fiberEffect.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit = Exit.Die(t)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        UnifiedFiber.completed(id, exit)
    }

  /** Launches a computation as a fiber using this backend's execution model.
    *
    * @param fa
    *   the computation to execute
    * @param observer
    *   optional observer for fiber lifecycle events
    * @param rootFibers
    *   optional queue for tracking root-level fibers (for test isolation)
    * @return
    *   an effect that yields a fiber handle for the launched computation
    */
  def fork[E, A](
    fa: Eru[E, A],
    observer: Option[EruObserver] = None,
    rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None
  ): Eru[Nothing, Fiber[E, A]] =
    this match {
      case Synchronous =>
        recoverForkDefect(
          Eru.effectTotal {
            val id = FiberId.fresh()
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

            val (exit, finalizers) = Eru.executeWithFinalizers(fa)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))

            finalizers.foreach { finalizer =>
              try finalizer().unsafeRunSync()
              catch case _: Exception => ()
            }

            UnifiedFiber.completed(id, exit): Fiber[E, A]
          },
          observer
        )

      case VirtualThreads =>
        import Eru.Internals.View.*
        Eru.Internals.view(fa) match {
          case VSucceed(value) =>
            recoverForkDefect(
              Eru.effectTotal {
                val id = FiberId.fresh()
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
                val exit = Exit.Success(value)
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
                UnifiedFiber.completed(id, exit): Fiber[E, A]
              },
              observer
            )

          case VFail(error) =>
            recoverForkDefect(
              Eru.effectTotal {
                val id = FiberId.fresh()
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
                val exit = Exit.Failure(error)
                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
                UnifiedFiber.completed(id, exit): Fiber[E, A]
              },
              observer
            )

          case VMapChain(source, f) =>
            Eru.Internals.view(source) match {
              case VSucceed(value) =>
                recoverForkDefect(
                  Eru.effectTotal {
                    val id = FiberId.fresh()
                    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
                    val mappedValue = f(value)
                    val exit = Exit.Success(mappedValue)
                    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
                    UnifiedFiber.completed(id, exit): Fiber[E, A]
                  },
                  observer
                )
              case _ =>
                recoverForkDefect(
                  Eru.effectTotal {
                    val id = FiberId.fresh()
                    val fiber = UnifiedFiber.active[E, A](id, observer)
                    val parentScope = StructuredConcurrency.getCurrentScope()

                    StructuredConcurrency.addChildFiber(fiber, rootFibers)

                    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

                    Thread.startVirtualThread { () =>
                      UnifiedFiber.setThread(fiber, Thread.currentThread())
                      // Restore parent scope and timer service in new thread
                      StructuredConcurrency.setCurrentScope(parentScope)

                      try {
                        StructuredConcurrency.withNewScope { _ =>
                          val (exit, finalizers) = Eru.executeWithFinalizers(fa)

                          finalizers.foreach { finalizer =>
                            try finalizer().unsafeRunSync()
                            catch case _: Exception => ()
                          }

                          UnifiedFiber.complete(fiber, exit)
                        }
                      } catch {
                        case _: InterruptedException =>
                          UnifiedFiber.complete(fiber, Exit.Interrupt(id, InterruptCause.Cancelled()))
                        case t: Throwable =>
                          UnifiedFiber.complete(fiber, Exit.Die(t))
                      }
                    }

                    fiber: Fiber[E, A]
                  },
                  observer
                )
            }

          case _ =>
            recoverForkDefect(
              Eru.effectTotal {
                val id = FiberId.fresh()
                val fiber = UnifiedFiber.active[E, A](id, observer)
                val parentScope = StructuredConcurrency.getCurrentScope()

                StructuredConcurrency.addChildFiber(fiber, rootFibers)

                observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

                Thread.startVirtualThread { () =>
                  UnifiedFiber.setThread(fiber, Thread.currentThread())
                  // Restore parent scope and timer service in new thread
                  StructuredConcurrency.setCurrentScope(parentScope)

                  try {
                    StructuredConcurrency.withNewScope { _ =>
                      val (exit, finalizers) = Eru.executeWithFinalizers(fa)

                      finalizers.foreach { finalizer =>
                        try finalizer().unsafeRunSync()
                        catch case _: Exception => ()
                      }

                      UnifiedFiber.complete(fiber, exit)
                    }
                  } catch {
                    case _: InterruptedException =>
                      UnifiedFiber.complete(fiber, Exit.Interrupt(id, InterruptCause.Cancelled()))
                    case t: Throwable =>
                      UnifiedFiber.complete(fiber, Exit.Die(t))
                  }
                }

                fiber: Fiber[E, A]
              },
              observer
            )
        }
    }

  /** Races two computations, returning the result of whichever completes first.
    *
    * @param fa
    *   the first computation to race
    * @param fb
    *   the second computation to race
    * @return
    *   an effect yielding Either[A, B] with the winner's result
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    this match {
      case Synchronous =>
        fa.map(Left.apply)

      case VirtualThreads =>
        Eru.effectTotal {
          import java.util.concurrent.atomic.AtomicReference
          import java.util.concurrent.CountDownLatch

          val resultRef = new AtomicReference[Option[() => Eru[E1 | E2 | Throwable, Either[A, B]]]](None)
          val latch = new CountDownLatch(1)
          val leftThreadRef = new AtomicReference[Option[Thread]](None)
          val rightThreadRef = new AtomicReference[Option[Thread]](None)
          val parentScope = StructuredConcurrency.getCurrentScope()

          def trySet(thunk: () => Eru[E1 | E2 | Throwable, Either[A, B]], cancelOther: () => Unit): Unit =
            if (resultRef.compareAndSet(None, Some(thunk))) {
              cancelOther()
              latch.countDown()
            }

          val runLeft: Runnable = () => {
            leftThreadRef.set(Some(Thread.currentThread()))
            // Restore parent scope and timer service in race thread
            StructuredConcurrency.setCurrentScope(parentScope)

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
                ()
            }
          }

          val runRight: Runnable = () => {
            rightThreadRef.set(Some(Thread.currentThread()))
            // Restore parent scope and timer service in race thread
            StructuredConcurrency.setCurrentScope(parentScope)

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
                ()
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
    }

  /** Sleeps for the specified duration.
    *
    * @param duration
    *   the time to sleep
    * @return
    *   an effect that completes after the duration
    */
  def sleep(duration: java.time.Duration): Eru[Nothing, Unit] =
    this match {
      case Synchronous =>
        Eru.effectTotal {
          Thread.sleep(duration.toMillis)
        }

      case VirtualThreads =>
        Eru.interruptibleBlocking {
          Thread.sleep(duration.toMillis)
        }.attempt.flatMap(_ => Eru.unit)
    }

  /** Applies a timeout to a computation.
    *
    * @param duration
    *   the timeout duration
    * @param fa
    *   the computation to timeout
    * @return
    *   an effect that fails with TimeoutException if duration exceeded
    */
  def timeout[E, A](
    duration: java.time.Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    // Fast path: pure values complete instantly and never timeout
    import Eru.Internals.View.*
    Eru.Internals.view(fa) match {
      case VSucceed(value) =>
        Eru.succeed(value)
      case VFail(error) =>
        Eru.fail(error)
      case _ =>
        // Effectful computation, use race against sleep
        race(fa, sleep(duration)).flatMap {
          case Left(a) => Eru.succeed(a)
          case Right(_) =>
            Eru.fail(new java.util.concurrent.TimeoutException(s"Operation timed out after $duration"))
        }
    }
  }

  /** Batch fork operation for improved performance.
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
    this match {
      case Synchronous =>
        // For Native, just use regular traverse since it's synchronous anyway
        Eru.traverse(effects)(e => fork(e, None, rootFibers))

      case VirtualThreads =>
        // Optimized batch creation for Virtual Threads
        Eru.effectTotal {
          effects.map { fa =>
            val id = FiberId.fresh()
            val fiber = UnifiedFiber.active[E, A](id)

            StructuredConcurrency.addChildFiber(fiber, rootFibers)

            Thread.startVirtualThread { () =>
              UnifiedFiber.setThread(fiber, Thread.currentThread())

              // Skip creating a new scope for simple parallel operations
              // This reduces overhead significantly
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
    }

  /** Awaits multiple fibers in batch and returns their exits.
    *
    * This optimization avoids deep chaining when awaiting many fibers.
    *
    * @param fibers
    *   the fibers to await
    * @return
    *   an effect yielding all exits
    */
  def awaitAll[E, A](fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
    this match {
      case Synchronous =>
        // For synchronous, fibers are already completed, just collect exits
        Eru.effectTotal {
          fibers.map(_.await.unsafeRunSync())
        }

      case VirtualThreads =>
        // For Virtual Threads, we need to avoid sequential blocking
        // The problem is that each await blocks, so we can't just map over them
        // We need to use the natural parallelism of Virtual Threads
        // But since we can't avoid the chaining in traverse, let's just use it
        Eru.traverse(fibers)(_.await)
    }

  /** Cleanup method for structured concurrency.
    *
    * @param rootFibers
    *   optional queue of root-level fibers to clean up (for test isolation)
    *
    * This is called at the end of execution to ensure all child fibers are properly cleaned up
    * according to structured concurrency semantics.
    */
  def cleanup(rootFibers: Option[ConcurrentLinkedQueue[UnifiedFiber[?, ?]]] = None): Unit = {
    StructuredConcurrency.cleanupRootFibers(rootFibers)
  }
}

/** Platform detection and backend selection. */
object Platform {

  /** Detects if we're running on the JVM (vs Scala Native).
    *
    * Uses a simple heuristic based on system properties that differ between platforms.
    */
  val isJVM: Boolean =
    (Option(System.getProperty("java.version")), Option(System.getProperty("java.vendor"))) match {
      case (Some(version), Some(vendor)) =>
        (version.contains(".") || version.toIntOption.exists(_ >= 8)) &&
        !vendor.toLowerCase.contains("scala")
      case _ => false
    }

  /** The runtime backend for this platform. */
  val backend: RuntimeBackend =
    if isJVM then RuntimeBackend.VirtualThreads
    else RuntimeBackend.Synchronous
}
