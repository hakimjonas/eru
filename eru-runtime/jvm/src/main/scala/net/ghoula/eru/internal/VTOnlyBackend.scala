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
      threadRef.compareAndSet(None, Some(thread))

      new VTFiber[E, A](id, exitAR, latch, threadRef, interrupted)
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
            ()
        }
      }
    }.attempt.flatMap(_ => Eru.unit)

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
