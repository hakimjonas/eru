package net.ghoula.eru

import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.prelude.*

/** Simple container for tracking child fibers in structured concurrency. */
private final class FiberScope(val childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]])

/** Thread-local storage for structured concurrency fiber tracking. */
private object StructuredConcurrency {
  private val currentScope: ThreadLocal[Option[FiberScope]] = ThreadLocal.withInitial(() => None)

  // Root fiber collection for auto-join cleanup
  private val rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] = new ConcurrentLinkedQueue()

  def getCurrentScope(): Option[FiberScope] = currentScope.get()
  def setCurrentScope(scope: Option[FiberScope]): Unit = currentScope.set(scope)

  def withNewScope[A](action: FiberScope => A): A = {
    val newScope = new FiberScope(new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]())
    val oldScope = getCurrentScope()
    setCurrentScope(Some(newScope))
    try {
      action(newScope)
    } finally {
      // Interrupt all child fibers before exiting scope
      var child = Option(newScope.childFibers.poll())
      while (child.nonEmpty) {
        child.get
          .interrupt(InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(())))
          .attempt
          .unsafeRunSync() // Fire and forget
        child = Option(newScope.childFibers.poll())
      }
      setCurrentScope(oldScope)
    }
  }

  def addChildFiber(fiber: UnifiedFiber[?, ?]): Unit = {
    getCurrentScope() match {
      case Some(scope) => scope.childFibers.offer(fiber)
      case None => rootFibers.offer(fiber) // Add to root collection for auto-join
    }
  }

  def cleanupRootFibers(): Unit = {
    var fiber = Option(rootFibers.poll())
    while (fiber.nonEmpty) {
      try {
        // Wait for fiber to complete naturally (auto-join)
        fiber.get.await.attempt.unsafeRunSync()
      } catch {
        case _: Exception => () // Continue cleanup even if fiber fails
      }
      fiber = Option(rootFibers.poll())
    }
  }
}

/** Simplified runtime backend using Scala 3 enums.
  *
  * This replaces the complex ConcurrencyBackend hierarchy with a simple enum that clearly
  * represents the two execution modes: synchronous and virtual threads. The enum includes behavior
  * directly, eliminating multiple abstraction layers.
  *
  * Implements structured concurrency with proper child fiber cleanup.
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

  /** Launches a computation as a fiber using this backend's execution model.
    *
    * @param fa
    *   the computation to execute
    * @param observer
    *   optional observer for fiber lifecycle events
    * @return
    *   an effect that yields a fiber handle for the launched computation
    */
  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    this match {
      case Synchronous =>
        Eru.effect {
          val id = FiberId.fresh()
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

          val (exit, finalizers) = Eru.executeWithFinalizers(fa)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))

          // Execute finalizers directly - they are already in FILO order from executeWithFinalizers
          finalizers.foreach { finalizer =>
            try finalizer().unsafeRunSync()
            catch case _: Exception => () // Swallow finalizer errors
          }

          UnifiedFiber.completed(id, exit): Fiber[E, A]
        }.attempt.map {
          case Result.Success(fiber) => fiber
          case Result.Failure(t) =>
            // Create a failed fiber as fallback
            val id = FiberId.fresh()
            val exit = Exit.Die(t)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            UnifiedFiber.completed(id, exit)
        }

      case VirtualThreads =>
        Eru.effect {
          val id = FiberId.fresh()
          val fiber = UnifiedFiber.active[E, A](id)

          // Add this fiber as a child of the current scope
          StructuredConcurrency.addChildFiber(fiber)

          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

          Thread.startVirtualThread { () =>
            UnifiedFiber.setThread(fiber, Thread.currentThread())

            // Execute with structured concurrency scope
            StructuredConcurrency.withNewScope { _ =>
              val (exit, finalizers) = Eru.executeWithFinalizers(fa)

              // Execute finalizers directly - they are already in FILO order from executeWithFinalizers
              finalizers.foreach { finalizer =>
                try finalizer().unsafeRunSync()
                catch case _: Exception => () // Swallow finalizer errors
              }

              UnifiedFiber.complete(fiber, exit)
              observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            } // Child fibers are automatically interrupted here
          }

          fiber: Fiber[E, A]
        }.attempt.map {
          case Result.Success(fiber) => fiber
          case Result.Failure(t) =>
            // Create a failed fiber as fallback
            val id = FiberId.fresh()
            val exit = Exit.Die(t)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            UnifiedFiber.completed(id, exit)
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
        // Sequential execution - first computation always "wins"
        fa.map(Left.apply)

      case VirtualThreads =>
        Eru.effect {
          import java.util.concurrent.atomic.AtomicReference
          import java.util.concurrent.CountDownLatch

          val resultRef = new AtomicReference[Option[() => Eru[E1 | E2 | Throwable, Either[A, B]]]](None)
          val latch = new CountDownLatch(1)
          val leftThreadRef = new AtomicReference[Option[Thread]](None)
          val rightThreadRef = new AtomicReference[Option[Thread]](None)

          def trySet(thunk: () => Eru[E1 | E2 | Throwable, Either[A, B]], cancelOther: () => Unit): Unit =
            if (resultRef.compareAndSet(None, Some(thunk))) {
              cancelOther()
              latch.countDown()
            }

          val runLeft: Runnable = () => {
            leftThreadRef.set(Some(Thread.currentThread()))
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
                trySet(() => Eru.effect(throw t), () => rightThreadRef.get().foreach(_.interrupt()))
              case Exit.Interrupt(_, _) =>
                ()
            }
          }

          val runRight: Runnable = () => {
            rightThreadRef.set(Some(Thread.currentThread()))
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
                trySet(() => Eru.effect(throw t), () => leftThreadRef.get().foreach(_.interrupt()))
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
          case Result.Success(None) => Eru.effect(throw new IllegalStateException("race: no result set"))
          case Result.Failure(t) => Eru.effect(throw t)
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
        Eru.effect {
          Thread.sleep(duration.toMillis)
        }.attempt.flatMap(_ => Eru.unit)

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
    race(fa, sleep(duration)).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) =>
        Eru.effect(throw new java.util.concurrent.TimeoutException(s"Operation timed out after $duration"))
    }
  }

  /** Cleanup method for structured concurrency.
    *
    * This is called at the end of execution to ensure all child fibers are properly cleaned up
    * according to structured concurrency semantics.
    */
  def cleanup(): Unit = this match {
    case Synchronous =>
      // No cleanup needed for synchronous execution
      ()
    case VirtualThreads =>
      // Clean up any unawaited root-level fibers (auto-join behavior)
      StructuredConcurrency.cleanupRootFibers()
  }
}

/** Platform detection and backend selection. */
object Platform {

  /** Detects if we're running on the JVM (vs Scala Native). */
  val isJVM: Boolean =
    try {
      Option(Class.forName("java.lang.Thread").getDeclaredMethod("isVirtual")).isDefined
    } catch {
      case _: Exception => false
    }

  /** The runtime backend for this platform. */
  val backend: RuntimeBackend =
    if isJVM then RuntimeBackend.VirtualThreads
    else RuntimeBackend.Synchronous
}
