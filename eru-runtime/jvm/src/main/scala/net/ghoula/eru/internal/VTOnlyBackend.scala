package net.ghoula.eru.internal

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, ScheduledExecutorService, TimeUnit}

import net.ghoula.eru.*

/** JVM-only Virtual Threads backend.
  *
  * zipPar and race still delegate to the sequential backend for now.
  */
private[eru] final class VTOnlyBackend extends ConcurrencyBackend {
  private val delegate: ConcurrencyBackend = DefaultBackends.sequential
  private val scheduler: ScheduledExecutorService =
    Executors.newScheduledThreadPool(1, r => Thread.ofVirtual().name("eru-scheduler").unstarted(r))

  // ThreadLocal to track the current fiber context for structured concurrency
  private val currentFiberContext: ThreadLocal[Option[FiberContext]] = ThreadLocal.withInitial(() => None)

  // ThreadLocal to track if we're in the root unsafeRunSync call
  private val isRootCall: ThreadLocal[Boolean] = ThreadLocal.withInitial(() => false)

  // Global root context to track children forked from main thread (unsafeRunSync)
  private val rootFiberContext = FiberContext(
    FiberId.fresh(), // Root fiber context
    new java.util.concurrent.ConcurrentLinkedQueue[VTFiber[?, ?]]()
  )

  // Context representing a parent fiber that can have child fibers registered
  private case class FiberContext(
    id: FiberId,
    childFibers: java.util.concurrent.ConcurrentLinkedQueue[VTFiber[?, ?]]
  )

  val capabilities: BackendCapabilities = BackendCapabilities(
    virtualThreads = true,
    structuredScopes = false,
    timersNonBlocking = true
  )

  def computeExit[E, A](fa: Eru[E, A], fiberId: FiberId): Exit[E, A] = {
    val _ = fiberId // Suppress unused parameter warning

    // Detect if this is the root call from unsafeRunSync (no fiber context = root)
    val isRoot = currentFiberContext.get().isEmpty && !isRootCall.get()
    if (isRoot) {
      isRootCall.set(true)
    }

    try {
      // UNIFIED SOLUTION: Use executeWithFinalizers directly to avoid circular dependency
      // This now uses the unified fiber-aware interpreter consistently
      val (exit, finalizers) = Eru.executeWithFinalizers(fa)

      // Execute finalizers synchronously to maintain structured concurrency guarantees
      executeDrainedFinalizersSync(finalizers)

      exit
    } finally {
      // If this was the root call, clean up any outstanding root children
      if (isRoot) {
        cleanup()
        isRootCall.set(false)
      }
    }
  }

  /** Synchronously executes finalizers in FILO order with coordination guarantee. This ensures the
    * parent's executeStructuredCleanup can reliably await completion.
    */
  private def executeDrainedFinalizersSync(finalizers: List[() => Eru[Nothing, Unit]]): Unit = {
    // Finalizers are built in LIFO order by core, execute directly
    finalizers.foreach { finalizer =>
      try {
        finalizer().unsafeRunSync()
      } catch {
        case ex: Exception =>
          println(
            s"FINALIZER: Exception in finalizer: ${ex.getMessage}"
          )
          // Continue executing other finalizers even if one fails
          // This matches the drainFinalizers behavior
          ()
      }
    }
//    println("FINALIZER: All finalizers completed synchronously")
  }

  private final class VTFiber[E, A](
    val id: FiberId,
    exitRef: AtomicReference[Exit[E, A]],
    latch: CountDownLatch,
    threadRef: AtomicReference[Option[Thread]],
    interrupted: java.util.concurrent.atomic.AtomicBoolean
  ) extends Fiber[E, A] {

    /** Waits for this fiber to complete and returns its structured exit outcome.
      *
      * This operation blocks the calling fiber until this fiber completes via Virtual Thread
      * execution, then returns the complete Exit outcome. The await operation handles all possible
      * fiber termination states including success, failure, defect, and interruption.
      *
      * The implementation uses a deterministic approach: it relies on the CountDownLatch for proper
      * completion signaling. If interrupted while waiting, it checks the final state exactly once
      * without timing dependencies. This maintains correctness and observability.
      *
      * @return
      *   an effect that yields the fiber's Exit outcome when execution completes
      */
    def await: Eru[Nothing, Exit[E, A]] =
      Eru.effect {
        try {
          latch.await()
          exitRef.get()
        } catch {
          case _: InterruptedException =>
            Option(exitRef.get()) match {
              case Some(exit) =>
                exit
              case None =>
                Exit.Interrupt(id, InterruptCause.Cancelled(Some("Fiber await interrupted")))
            }
        }
      }.attempt.map {
        case Result.Success(exit) => exit
        case Result.Failure(throwable) =>
          Option(exitRef.get()).getOrElse(
            Exit.Die(new RuntimeException(s"Fiber await failed unexpectedly: ${throwable.getMessage}", throwable))
          )
      }

    /** Requests cooperative interruption of this fiber's Virtual Thread.
      *
      * This implementation sends Thread.interrupt() to the underlying Virtual Thread and marks the
      * fiber as interrupted. The interruption is cooperative - the fiber will complete any critical
      * sections and perform proper cleanup before terminating.
      *
      * Multiple interrupt calls are idempotent and safe - only the first interruption cause is
      * recorded and acted upon.
      *
      * @param cause
      *   the structured reason for requesting interruption
      * @return
      *   an effect that completes when the interruption request has been issued
      */
    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] =
      Eru.effect {
        if (interrupted.compareAndSet(false, true)) {
          threadRef.get().foreach(_.interrupt())
        }
      }.attempt.flatMap(_ => Eru.unit)

  }

  /** Executes structured cleanup of all child fibers for the given context.
    *
    * This method ensures all child fibers are properly interrupted and awaited, implementing
    * structured concurrency semantics. It waits for each child to actually terminate, ensuring
    * finalizers have time to execute.
    */
  private def executeStructuredCleanup(fiberContext: FiberContext): Unit = {
    var childFiber = Option(fiberContext.childFibers.poll())
    while (childFiber.nonEmpty) {
      try {
//        println(s"CLEANUP: Interrupting child ${childFiber.get.id}")
        // Interrupt the child fiber
        childFiber.get.interrupt(InterruptCause.ParentTerminated(fiberContext.id, Exit.Success(()))).unsafeRunSync()
//        println(s"CLEANUP: Interrupt sent to child ${childFiber.get.id}")

        // Wait for the child to complete - this allows finalizers to execute
        // CRITICAL: Must wait for child completion to ensure finalizers execute
        // This is essential for mathematical correctness of structured concurrency
        val _ = childFiber.get.await.unsafeRunSync()
        // Child completed successfully
      } catch {
        case _: Exception =>
        // Exception during child cleanup - continue with other cleanups
        // Continue with other cleanups
      }
      childFiber = Option(fiberContext.childFibers.poll())
    }
//    println(s"CLEANUP: All children cleaned up for parent ${fiberContext.id}")
  }

  /** Launches an effect on a Virtual Thread and returns a fiber handle.
    *
    * The effect runs asynchronously on its own Virtual Thread while the fiber handle provides await
    * and interrupt capabilities. Observer events are emitted for fiber lifecycle tracking when an
    * observer is provided.
    *
    * The returned fiber supports cooperative interruption via Thread.interrupt() and guarantees
    * proper resource cleanup according to Eru's finalizer semantics.
    *
    * @param fa
    *   the effect to execute on a Virtual Thread
    * @param observer
    *   optional observer for fiber lifecycle events
    * @return
    *   an effect yielding a fiber handle for the launched computation
    */
  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val id = FiberId.fresh()
      val exitAR = new AtomicReference[Exit[E, A]]()
      val latch = new CountDownLatch(1)
      val interrupted = new java.util.concurrent.atomic.AtomicBoolean(false)
      val threadRef = new AtomicReference[Option[Thread]](None)

      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

      // Create the child fiber before starting the thread
      val childFiber = new VTFiber[E, A](id, exitAR, latch, threadRef, interrupted)

      // Register child with parent context, or root context if no parent
      currentFiberContext.get() match {
        case Some(parentContext) =>
          // Register this child fiber to be cleaned up when parent completes
          parentContext.childFibers.offer(childFiber)
//          println(
//            s"FORK: Registered child ${id} with parent ${parentContext.id}. Parent child count: ${parentContext.childFibers.size()}"
//          )
        case None =>
          // Register with root context for cleanup when main computation completes
          rootFiberContext.childFibers.offer(childFiber)
//          println(
//            s"FORK: Registered root child ${id} with root context. Root child count: ${rootFiberContext.childFibers.size()}"
//          )
      }

      val runnable: Runnable = () => {
        // Set current fiber context for this thread
        val fiberContext = FiberContext(id, new java.util.concurrent.ConcurrentLinkedQueue[VTFiber[?, ?]]())
        currentFiberContext.set(Some(fiberContext))

        try {
          threadRef.set(Some(Thread.currentThread()))
          val exit = computeExit(fa, id)
          // CRITICAL: Clean up child fibers BEFORE setting exit result
          // This ensures structured concurrency: children terminate before parent completes
//          println(
//            s"FIBER: Fiber ${id} completed, cleaning up children. Child count: ${fiberContext.childFibers.size()}"
//          )
          executeStructuredCleanup(fiberContext)
          exitAR.set(exit)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        } catch {
          case t: Throwable =>
            // This is a fallback safety net. Ideally, all Throwables should be caught by the interpreter
            // inside computeExit and returned as an Exit value. If we get here, it indicates a bug
            // or a leak in the interpreter. We capture it as a Die exit state.
            val exit = Exit.Die(t)
            exitAR.set(exit)
            observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
            // Still attempt to clean up children even if the parent fiber died unexpectedly.
            executeStructuredCleanup(fiberContext)
        } finally {
          currentFiberContext.remove()
          latch.countDown()
        }
      }

      val thread = java.lang.Thread.startVirtualThread(runnable)
      threadRef.compareAndSet(None, Some(thread))

      childFiber
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit = Exit.Die(t)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        val dummyThreadRef = new AtomicReference[Option[Thread]](None)
        new VTFiber[E, A](
          id,
          new AtomicReference[Exit[E, A]](exit),
          new CountDownLatch(0),
          dummyThreadRef,
          new java.util.concurrent.atomic.AtomicBoolean(false)
        )
    }

  /** Races two effects, returning the result of whichever completes first.
    *
    * Both effects execute concurrently on separate Virtual Threads. The first effect to complete
    * (successfully or with failure) wins the race, and the losing effect is cancelled via thread
    * interruption to prevent resource leaks.
    *
    * Race semantics are non-deterministic by design - either effect may win depending on execution
    * timing. The implementation ensures structured concurrency by cancelling the loser and
    * preserving proper finalizer execution.
    *
    * @param fa
    *   the first effect to race
    * @param fb
    *   the second effect to race
    * @return
    *   an effect yielding Either[A, B] with the winner's result
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    Eru.effect {
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
        val leftId = FiberId.fresh()
        val ex: Exit[E1, A] = computeExit(fa, leftId)
        ex match {
          case Exit.Success(a) =>
            trySet(() => Eru.succeed(Left(a)), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e1) =>
            trySet(() => Eru.fail(e1), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effect(throw t), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, _) =>
            // Child fiber was interrupted (likely cancelled by the other side winning)
            // This should not cause the race to be interrupted - just ignore this fiber
            // The race continues until a winner is found or the coordinator is interrupted
            ()
        }
      }

      val runRight: Runnable = () => {
        rightThreadRef.set(Some(Thread.currentThread()))
        val rightId = FiberId.fresh()
        val ex: Exit[E2, B] = computeExit(fb, rightId)
        ex match {
          case Exit.Success(b) =>
            trySet(() => Eru.succeed(Right(b)), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e2) =>
            trySet(() => Eru.fail(e2), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effect(throw t), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, _) =>
            // Child fiber was interrupted (likely cancelled by the other side winning)
            // This should not cause the race to be interrupted - just ignore this fiber
            // The race continues until a winner is found or the coordinator is interrupted
            ()
        }
      }

      java.lang.Thread.startVirtualThread(runLeft)
      java.lang.Thread.startVirtualThread(runRight)
      try {
        latch.await()
        resultRef.get()
      } catch {
        case ie: InterruptedException =>
          // Race coordinator was interrupted - preserve the InterruptedException for the interpreter to handle
          // The interpreter will catch this and convert it to Exit.Interrupt properly
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

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      val delay = Math.max(0L, duration.toMillis)
      if (delay == 0L) {
        // Check for interruption even on zero-duration sleep
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("Sleep interrupted")
        }
        ()
      } else {
        val future = new java.util.concurrent.CompletableFuture[Unit]()
        scheduler.schedule(
          new Runnable {
            def run(): Unit = future.complete(())
          },
          delay,
          TimeUnit.MILLISECONDS
        )
        // Let InterruptedException propagate - the interpreter will handle it properly
        future.get() // Virtual Thread will park here, not blocking carrier thread
      }
    }

  def timeout[E, A](
    duration: Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    val timer = sleep(duration)
    race(fa, timer).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.effect(throw new TimeoutException(s"Operation timed out after $duration"))
    }
  }

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] =
    delegate.retry(policy)(fa)

  /** Cleanup method called at the end of unsafeRunSync to finalize backend state.
    *
    * This ensures structured concurrency by cleaning up all root-level child fibers that were
    * forked during the main computation but never awaited.
    */
  override def cleanup(): Unit = {
//    println(s"ROOT CLEANUP: Cleaning up root children. Root child count: ${rootFiberContext.childFibers.size()}")
    executeStructuredCleanup(rootFiberContext)
  }

  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    Eru.blocking {
      val future = new java.util.concurrent.CompletableFuture[Either[E | Throwable, A]]()

      val cb: Either[E, A] => Unit = result => {
        if (!future.isDone) {
          future.complete(result)
        }
      }

      java.util.concurrent.CompletableFuture.supplyAsync(
        () => {
          try {
            val registrationResult = register(cb).attempt.unsafeRunSync()
            registrationResult match {
              case Result.Success(_) =>
                ()
              case Result.Failure(t) =>
                if (!future.isDone) {
                  future.complete(Left(t))
                }
            }
          } catch {
            case t: Throwable =>
              if (!future.isDone) {
                future.complete(Left(t))
              }
          }
        },
        java.util.concurrent.ForkJoinPool.commonPool()
      )

      try {
        future.get()
      } catch {
        case _: InterruptedException =>
          Left(new InterruptedException("Suspend operation interrupted"))
        case ex: java.util.concurrent.ExecutionException =>
          val cause = Option(ex.getCause).getOrElse(ex)
          Left(cause)
        case t: Throwable =>
          Left(t)
      }
    }.attempt.map {
      case Result.Success(result) => result
      case Result.Failure(t) =>
        Left(t)
    }
}
