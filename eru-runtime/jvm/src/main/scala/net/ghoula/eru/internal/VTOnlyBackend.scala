package net.ghoula.eru.internal

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, ScheduledExecutorService, TimeUnit}

import net.ghoula.eru.*

/** JVM-only Virtual Threads backend (H9.2 fork/await; H9.3 timers non-blocking).
  *
  * zipPar and race still delegate to the sequential backend for now.
  */
private[eru] final class VTOnlyBackend extends ConcurrencyBackend {
  private val delegate: ConcurrencyBackend = DefaultBackends.sequential
  private val scheduler: ScheduledExecutorService =
    Executors.newScheduledThreadPool(1, r => Thread.ofVirtual().name("eru-scheduler").unstarted(r))

  val capabilities: BackendCapabilities = BackendCapabilities(
    virtualThreads = true,
    structuredScopes = false,
    timersNonBlocking = true
  )

  private def computeExit[E, A](fa: Eru[E, A]): Exit[E, A] =
    try Result.toExit(fa.attempt.unsafeRunSync())
    catch { case t: Throwable => Exit.Die(t) }

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
      * @return
      *   an effect that yields the fiber's Exit outcome when execution completes
      */
    def await: Eru[Nothing, Exit[E, A]] =
      Eru.effect {
        latch.await()
        exitRef.get()
      }.attempt.map {
        case Result.Success(x) => x
        case Result.Failure(_) => exitRef.get()
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
          // Note: The actual Exit.Interrupt will be set by the running effect when it
          // observes the interruption at the next effect boundary
        }
      }.attempt.flatMap(_ => Eru.unit)
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

      val runnable: Runnable = () => {
        threadRef.set(Some(Thread.currentThread()))
        val exit = computeExit(fa)
        exitAR.set(exit)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        latch.countDown()
      }

      val thread = java.lang.Thread.startVirtualThread(runnable)
      // Set thread reference immediately for early interrupt requests
      threadRef.compareAndSet(None, Some(thread))

      new VTFiber[E, A](id, exitAR, latch, threadRef, interrupted)
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit = Exit.Die(t)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        // Create a dummy thread reference for the error case
        val dummyThreadRef = new AtomicReference[Option[Thread]](None)
        new VTFiber[E, A](
          id,
          new AtomicReference[Exit[E, A]](exit),
          new CountDownLatch(0),
          dummyThreadRef,
          new java.util.concurrent.atomic.AtomicBoolean(false)
        )
    }

  /** Runs two effects in parallel and combines their results.
    *
    * Both effects execute concurrently on separate Virtual Threads. If either effect fails, dies,
    * or is interrupted, the other effect is cancelled via thread interruption to prevent resource
    * leaks and ensure structured concurrency.
    *
    * The implementation preserves Eru's correctness guarantees: finalizers run exactly once in FILO
    * order, and all termination paths are handled properly through Exit types.
    *
    * @param fa
    *   the first effect to run
    * @param fb
    *   the second effect to run
    * @return
    *   an effect yielding the pair of results, or the first failure encountered
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    Eru.effect {
      val leftRef = new AtomicReference[Exit[E1, A]]()
      val rightRef = new AtomicReference[Exit[E2, B]]()
      val latch = new CountDownLatch(2)
      val leftThreadRef = new AtomicReference[Option[Thread]](None)
      val rightThreadRef = new AtomicReference[Option[Thread]](None)
      val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)

      val runLeft: Runnable = () => {
        leftThreadRef.set(Some(Thread.currentThread()))
        val exit = computeExit(fa)
        leftRef.set(exit)
        // Cancel right side on any non-success exit
        exit match {
          case Exit.Success(_) => // No cancellation needed
          case _ =>
            if (cancelled.compareAndSet(false, true)) {
              rightThreadRef.get().foreach(_.interrupt())
            }
        }
        latch.countDown()
      }

      val runRight: Runnable = () => {
        rightThreadRef.set(Some(Thread.currentThread()))
        val exit = computeExit(fb)
        rightRef.set(exit)
        // Cancel left side on any non-success exit
        exit match {
          case Exit.Success(_) => // No cancellation needed
          case _ =>
            if (cancelled.compareAndSet(false, true)) {
              leftThreadRef.get().foreach(_.interrupt())
            }
        }
        latch.countDown()
      }

      java.lang.Thread.startVirtualThread(runLeft)
      java.lang.Thread.startVirtualThread(runRight)
      try {
        latch.await()
        (leftRef.get(), rightRef.get())
      } catch {
        case _: InterruptedException =>
          // zipPar was interrupted, re-throw to be handled by attempt
          throw new InterruptedException("zipPar interrupted")
      }
    }.attempt.flatMap {
      case Result.Success((l, r)) =>
        (l, r) match {
          case (Exit.Success(a), Exit.Success(b)) => Eru.succeed((a, b))
          case (Exit.Die(t), _) => Eru.effect(throw t)
          case (_, Exit.Die(t)) => Eru.effect(throw t)
          case (Exit.Interrupt(_, cause), _) =>
            Eru.effect(throw new InterruptedException(s"Fiber interrupted: $cause"))
          case (_, Exit.Interrupt(_, cause)) =>
            Eru.effect(throw new InterruptedException(s"Fiber interrupted: $cause"))
          case (Exit.Failure(e1), _) => Eru.fail(e1)
          case (_, Exit.Failure(e2)) => Eru.fail(e2)
        }
      case Result.Failure(t) => Eru.effect(throw t)
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
        val ex: Exit[E1, A] = computeExit(fa)
        ex match {
          case Exit.Success(a) =>
            trySet(() => Eru.succeed(Left(a)), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e1) =>
            trySet(() => Eru.fail(e1), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effect(throw t), () => rightThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, c) =>
            trySet(
              () => Eru.effect(throw new InterruptedException(s"Fiber interrupted: $c")),
              () => rightThreadRef.get().foreach(_.interrupt())
            )
        }
      }

      val runRight: Runnable = () => {
        rightThreadRef.set(Some(Thread.currentThread()))
        val ex: Exit[E2, B] = computeExit(fb)
        ex match {
          case Exit.Success(b) =>
            trySet(() => Eru.succeed(Right(b)), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Failure(e2) =>
            trySet(() => Eru.fail(e2), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Die(t) =>
            trySet(() => Eru.effect(throw t), () => leftThreadRef.get().foreach(_.interrupt()))
          case Exit.Interrupt(_, c) =>
            trySet(
              () => Eru.effect(throw new InterruptedException(s"Fiber interrupted: $c")),
              () => leftThreadRef.get().foreach(_.interrupt())
            )
        }
      }

      java.lang.Thread.startVirtualThread(runLeft)
      java.lang.Thread.startVirtualThread(runRight)
      try {
        latch.await()
        resultRef.get()
      } catch {
        case _: InterruptedException =>
          // Race was interrupted, return no result to be handled by the outer attempt
          throw new InterruptedException("Race interrupted")
      }
    }.attempt.flatMap {
      case Result.Success(Some(thunk)) => thunk()
      case Result.Success(None) => Eru.effect(throw new IllegalStateException("race: no result set"))
      case Result.Failure(t) => Eru.effect(throw t)
    }

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    Eru.blocking {
      val delay = Math.max(0L, duration.toMillis)
      if (delay == 0L) {
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
        try {
          future.get() // Virtual Thread will park here, not blocking carrier thread
        } catch {
          case _: InterruptedException =>
            // Thread was interrupted (likely due to cancellation), complete normally
            // This is expected behavior in concurrent scenarios
            ()
        }
      }
    }.attempt.flatMap(_ => Eru.unit)

  def timeout[E, A](
    duration: Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    // Race fa against a non-blocking timer; if timer wins, fail with TimeoutException
    val timer = sleep(duration)
    race(fa, timer).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.effect(throw new TimeoutException(s"Operation timed out after $duration"))
    }
  }

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] =
    delegate.retry(policy)(fa)

  /** Executes a collection of effects in parallel and returns their results in order.
    *
    * This implementation leverages Virtual Threads' lightweight nature by spawning one VT per
    * effect and collecting results efficiently using a CountDownLatch. Failed effects cancel all
    * remaining effects to ensure structured concurrency.
    *
    * @param effects
    *   the collection of effects to execute in parallel
    * @return
    *   an effect yielding the list of results in input order
    */
  override def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] =
    if (effects.isEmpty) {
      Eru.succeed(Nil)
    } else {
      Eru.effect {
        val size = effects.length
        val results = new Array[Exit[E, A]](size)
        val latch = new CountDownLatch(size)
        val threadRefs = new Array[AtomicReference[Option[Thread]]](size)
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)

        // Initialize thread references
        (0 until size).foreach(i => threadRefs(i) = new AtomicReference[Option[Thread]](None))

        // Launch all effects concurrently
        effects.zipWithIndex.foreach { case (effect, index) =>
          val runnable: Runnable = () => {
            threadRefs(index).set(Some(Thread.currentThread()))
            val exit = computeExit(effect)
            results(index) = exit

            // Cancel all other threads on any non-success exit
            exit match {
              case Exit.Success(_) => // Continue normally
              case _ =>
                if (cancelled.compareAndSet(false, true)) {
                  threadRefs.zipWithIndex.foreach { case (ref, i) =>
                    if (i != index) ref.get().foreach(_.interrupt())
                  }
                }
            }
            latch.countDown()
          }

          java.lang.Thread.startVirtualThread(runnable)
        }

        try {
          latch.await()
          results.toList
        } catch {
          case _: InterruptedException =>
            // Cancel all threads if parSequence itself is interrupted
            if (cancelled.compareAndSet(false, true)) {
              threadRefs.foreach(_.get().foreach(_.interrupt()))
            }
            throw new InterruptedException("parSequence interrupted")
        }
      }.attempt.flatMap {
        case Result.Success(exits) =>
          // Process exits in order, failing fast on first error
          def processExits(remaining: List[Exit[E, A]], acc: List[A]): Eru[E | Throwable, List[A]] =
            remaining match {
              case Nil => Eru.succeed(acc.reverse)
              case Exit.Success(value) :: tail => processExits(tail, value :: acc)
              case Exit.Failure(error) :: _ => Eru.fail(error)
              case Exit.Die(throwable) :: _ => Eru.effect(throw throwable)
              case Exit.Interrupt(_, cause) :: _ =>
                Eru.effect(throw new InterruptedException(s"Effect interrupted: $cause"))
            }
          processExits(exits, Nil)
        case Result.Failure(t) => Eru.effect(throw t)
      }
    }

  /** Executes effects derived from a collection of inputs in parallel.
    *
    * This is the key bulk operation that addresses parTraverse performance. It combines input
    * mapping with parallel execution in a single efficient operation, avoiding the overhead of
    * individual fiber management.
    *
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @return
    *   an effect yielding the list of results in input order
    */
  override def parTraverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]] =
    parSequence(inputs.map(f))

  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    Eru.blocking {
      // Use a CompletableFuture to enable async resumption
      val future = new java.util.concurrent.CompletableFuture[Either[E | Throwable, A]]()

      // Create callback that completes the future when invoked
      val cb: Either[E, A] => Unit = result => {
        if (!future.isDone) {
          future.complete(result)
        }
      }

      // Execute registration asynchronously on a Virtual Thread
      java.util.concurrent.CompletableFuture.supplyAsync(
        () => {
          try {
            val registrationResult = register(cb).attempt.unsafeRunSync()
            registrationResult match {
              case Result.Success(_) =>
                // Registration succeeded, callback may be invoked async
                ()
              case Result.Failure(t) =>
                // Registration failed, complete future with error
                if (!future.isDone) {
                  future.complete(Left(t))
                }
            }
          } catch {
            case t: Throwable =>
              // Registration threw exception, complete future with error
              if (!future.isDone) {
                future.complete(Left(t))
              }
          }
        },
        java.util.concurrent.ForkJoinPool.commonPool()
      )

      try {
        // Park the Virtual Thread until callback is invoked or timeout
        // This is non-blocking for carrier threads - VT will be parked
        future.get()
      } catch {
        case _: InterruptedException =>
          // Virtual Thread was interrupted, likely due to cancellation
          // Complete with interruption cause
          Left(new InterruptedException("Suspend operation interrupted"))
        case ex: java.util.concurrent.ExecutionException =>
          // Unwrap execution exception from async registration
          val cause = Option(ex.getCause).getOrElse(ex)
          Left(cause)
        case t: Throwable =>
          // Other errors during suspend operation
          Left(t)
      }
    }.attempt.map {
      case Result.Success(result) => result
      case Result.Failure(t) =>
        // Blocking operation failed, return error
        Left(t)
    }
}
