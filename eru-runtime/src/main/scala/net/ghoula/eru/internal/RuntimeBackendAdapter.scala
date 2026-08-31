package net.ghoula.eru.internal

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.trace.EruTrace

/** Internal adapter providing legacy ConcurrencyBackend interface compatibility.
  *
  * This adapter bridges the unified RuntimeBackend implementation with the legacy interface,
  * maintaining backward compatibility while providing modern structured concurrency features.
  *
  * Each adapter instance maintains its own root fiber collection for proper test isolation.
  */
private[eru] final class RuntimeBackendAdapter extends ConcurrencyBackend {

  private val rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] = new ConcurrentLinkedQueue()

  /** Lazy, instance-local executor so multiple Eru applications do not contend on a shared pool. */
  private lazy val privateExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

  /** Timer wheel for `Eru.at` / `Eru.after`, owned by this adapter.
    *
    * Lazy so it is only created for the virtual-threads runtime. The interpreter reaches it via
    * `TimerService.get`, which reads a thread-local pushed by this adapter's fork / race /
    * handleSuspend entry points. Construction does NOT install the wheel into any global or default
    * provider — that would re-introduce the multi-runtime stomp. The `EruRuntime.shared` singleton
    * installs the default provider once, so a bare `Eru.at(...).unsafeRunSync()` from an unrelated
    * thread still falls back to the shared runtime's wheel; runtimes created via
    * `EruRuntime.create()` remain fully isolated. Forced at construction so the wheel exists before
    * any entry point reaches it.
    */
  private lazy val timerWheel: HashedTimerWheel = new HashedTimerWheel()

  timerWheel

  val capabilities: BackendCapabilities = new BackendCapabilities(
    virtualThreads = true,
    structuredScopes = true,
    timersNonBlocking = true
  )

  def computeExit[E, A](fa: Eru[E, A], fiberId: FiberId): Exit[E, A] = {
    val _ = fiberId
    val (exit, finalizers) = Eru.executeWithFinalizers(fa)
    finalizers.foreach { finalizer =>
      try finalizer().unsafeRunSync()
      catch case _: Exception => ()
    }
    exit
  }

  /** The adapter's timer wheel, passed as `childTimer` into every entry point that spawns a virtual
    * thread.
    *
    * `RuntimeBackend`'s capture uses `childTimer.orElse(TimerService.get)`, so virtual threads
    * spawned through a specific runtime see that runtime's wheel regardless of the caller's
    * thread-local state — `r1.fork(Eru.at(...))` routes to r1's wheel even when called from a
    * thread that never touched r1 before. `forkBatch` deliberately omits the `childTimer`: its
    * implementation in `RuntimeBackend.forkBatch` skips structured concurrency for performance, and
    * mirroring that skip keeps the timer discipline consistent with the scope discipline. If
    * `forkBatch` children ever need `Eru.at` / sleep in an isolated runtime, revisit with a
    * benchmark.
    */
  private val ownTimer: Option[TimerService] = Some(timerWheel)

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    RuntimeBackend.fork(fa, observer, Some(rootFibers), ownTimer)

  /** Fork with custom fiber tracking queue for incremental cleanup. */
  override def forkWithTracking[E, A](
    fa: Eru[E, A],
    customTracking: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]
  ): Eru[Nothing, Fiber[E, A]] =
    RuntimeBackend.fork(fa, None, Some(customTracking), ownTimer)

  /** Fork as daemon without structured concurrency tracking. */
  override def forkDaemon[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    RuntimeBackend.fork(fa, observer, None, ownTimer, isDaemon = true)

  override def forkBatch[E, A](effects: List[Eru[E, A]]): Eru[Nothing, List[Fiber[E, A]]] =
    RuntimeBackend.forkBatch(effects, Some(rootFibers))

  override def awaitAll[E, A](fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
    RuntimeBackend.awaitAll(fibers)

  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    RuntimeBackend.race(fa, fb, ownTimer)

  def sleep(duration: Duration): Eru[Nothing, Unit] = {
    if (duration.isNegative || duration.isZero) Eru.unit
    else sleepImplAUnpark(duration)
  }

  /** Wheel-scheduled sleep via `LockSupport.park`/`unpark` on the adapter's own timer wheel.
    *
    * Wall-time dependency is consolidated behind [[TimerService]]: the wheel's daemon reads
    * `System.currentTimeMillis` and calls `Thread.sleep(tickDurationMs)`. The wheel task flips an
    * [[java.util.concurrent.atomic.AtomicBoolean]] and unparks the sleeper; the sleeper loops on
    * `LockSupport.park()` until the flag is set or the thread is interrupted. No latch, no AQS, no
    * CompletableFuture.
    *
    * Invariants:
    *   - `park()` may return spuriously, so the sleeper must loop on the fired condition.
    *   - `interruptibleBlocking` propagates `InterruptedException` to `Exit.Interrupt` via
    *     `evalInterruptible` → `InterruptedWithFinalizers`.
    *   - The wheel entry is cancelled on the interrupt/exit path to bound retention of the
    *     scheduled task until the wheel rotates past.
    *
    * The timer is determined by the adapter the sleep was dispatched from, never by the
    * thread-local state at interpret time. The deadline is computed in pure duration space via
    * `scheduleAfter` rather than round-tripped through `schedule(epochMillis, ...)`, so the wheel
    * never reads wall time on this path and the type-level "Monotonic" claim of the user-facing API
    * holds at the implementation level.
    */
  private def sleepImplAUnpark(duration: Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      val t = Thread.currentThread()
      val fired = new java.util.concurrent.atomic.AtomicBoolean(false)
      val delayMillis = (duration.toNanos + 999_999L) / 1_000_000L
      val handle = timerWheel.scheduleAfter(
        delayMillis,
        () => {
          fired.set(true)
          java.util.concurrent.locks.LockSupport.unpark(t)
        }
      )
      try
        while (!fired.get() && !t.isInterrupted)
          java.util.concurrent.locks.LockSupport.park()
      finally if (!fired.get()) handle.cancel()
      if (t.isInterrupted) {
        val _ = Thread.interrupted()
        throw new InterruptedException("sleep interrupted")
      }
    }

  def timeout[E, A](duration: Duration)(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
    RuntimeBackend.timeout(duration, ownTimer)(fa)

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import Eru.Internals.View.*
    Eru.Internals.view(fa) match {
      case VSucceed(value) =>
        Eru.succeed(value)
      case _ =>
        import EruRuntime.Policy.*
        def delay(i: Int): Option[java.time.Duration] = policy match {
          case NoDelay(n) => if (i < n) Some(java.time.Duration.ZERO) else None
          case Exponential(base, maxRet) => if (i < maxRet) Some(base.multipliedBy(1L << i)) else None
        }
        def loop(i: Int): Eru[E, A] =
          fa.recoverWith {
            case t: Throwable => Eru.fail(t)
            case e =>
              delay(i) match {
                case Some(d) => sleep(d).flatMap(_ => loop(i + 1))
                case None => Eru.fail(e)
              }
          }
        loop(0)
    }
  }

  /** Registers a suspension callback on a virtual thread from the adapter's private executor.
    *
    * The registration runs on a `privateExecutor` virtual thread, which does not inherit the
    * caller's thread-locals. The captured trace context is restored and the adapter's timer wheel
    * is pushed so `Eru.at` / sleep / `.traced` inside `register` route through the correct
    * runtime's wheel and stay on the parent's trace lineage, mirroring the fork/race propagation in
    * `RuntimeBackend`.
    *
    * An `InterruptedException` propagates out of this thunk so the interpreter's Effect-branch
    * catch converts it to `InterruptedWithFinalizers` — producing `Exit.Interrupt` with the fiber's
    * accumulated finalizer stack. The interrupt flag is cleared before re-throwing: the contract
    * hands back either a typed value or an interrupt, never a thread with a sticky interrupt bit;
    * the interpreter owns the `InterruptCause` from here on.
    */
  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] = {
    Eru.blocking {
      val future = new java.util.concurrent.CompletableFuture[Either[E | Throwable, A]]()

      val parentTrace = EruTrace.getCurrentContext

      val cb: Either[E, A] => Unit = result => {
        if (!future.isDone) {
          future.complete(result)
        }
      }

      java.util.concurrent.CompletableFuture.supplyAsync(
        () => {
          ownTimer.foreach(t => TimerService.setCurrent(Some(t)))
          EruTrace.setCurrentContext(parentTrace)
          try {
            val registrationResult = register(cb).attempt.unsafeRunSync()
            registrationResult match {
              case Result.Success(_) => ()
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
        privateExecutor
      )

      try {
        future.get()
      } catch {
        case ie: InterruptedException =>
          val _ = Thread.interrupted()
          throw ie
        case ex: java.util.concurrent.ExecutionException =>
          val cause = Option(ex.getCause).getOrElse(ex)
          Left(cause)
        case t: Throwable =>
          Left(t)
      }
    }.attempt.map {
      case Result.Success(result) => result
      case Result.Failure(t) => Left(t)
    }
  }

  override private[eru] def timerForTests: Option[TimerService] =
    Some(timerWheel)

  /** Cleans up the adapter's root fibers.
    *
    * The timer wheel and the private executor are deliberately NOT shut down here. `cleanup()` is
    * invoked per test suite via `EruTestSuite.afterAll()`, but the wheel's daemon is part of the
    * execution engine — the same adapter (and therefore the same wheel) is reused across suites via
    * `EruRuntime.shared`. Shutting it down mid-JVM-lifetime strands any later sleep / `Eru.at` call
    * on a dead wheel (schedule accepts the entry but no daemon drains it → park forever). The wheel
    * is a daemon virtual thread: cheap when idle, dies with the JVM. The private executor is
    * likewise left for GC when the adapter is collected, as it may still hold pending tasks.
    */
  override def cleanup(): Unit = {
    RuntimeBackend.cleanup(Some(rootFibers))
  }

  /** Interrupts and joins the adapter's root fibers, reporting the cleanup to the observer.
    *
    * Root fibers have no parent fiber: the runtime's root boundary is the parent, named by the
    * reserved `FiberId.Root`, and the `parentExit` `Success(())` represents the application's
    * orderly end via this method.
    */
  override def shutdownRootFibers(observer: Option[EruObserver]): Eru[Nothing, (Int, Int)] = {
    Eru.effectTotal {
      val fibersToCleanup = scala.collection.mutable.ListBuffer.empty[UnifiedFiber[?, ?]]
      var fiber = Option(rootFibers.poll())
      while (fiber.nonEmpty) {
        fibersToCleanup += fiber.get
        fiber = Option(rootFibers.poll())
      }

      val total = fibersToCleanup.size
      val parentId = FiberId.Root

      observer.foreach { obs =>
        obs.onEvent(EruObserver.EruEvent.StructuredCleanupStarted(parentId, total))
      }

      var interrupted = 0
      var alreadyCompleted = 0

      fibersToCleanup.foreach { fiberToCleanup =>
        try {
          val wasActive = fiberToCleanup.currentState match {
            case UnifiedFiberState.Active(_, _, _, _, _, _) => true
            case UnifiedFiberState.Completed(_) => false
          }

          observer.foreach { obs =>
            obs.onEvent(
              EruObserver.EruEvent.ChildInterruptionRequested(
                parentId,
                fiberToCleanup.id,
                InterruptCause.ParentTerminated(parentId, Exit.Success(())),
                wasActive
              )
            )
          }

          if wasActive then {
            fiberToCleanup
              .interrupt(InterruptCause.ParentTerminated(parentId, Exit.Success(())))
              .attempt
              .unsafeRunSync()
            interrupted += 1
          } else {
            alreadyCompleted += 1
          }

          fiberToCleanup.await.attempt.unsafeRunSync()
        } catch {
          case _: Exception => ()
        }
      }

      observer.foreach { obs =>
        obs.onEvent(EruObserver.EruEvent.StructuredCleanupCompleted(parentId, interrupted, alreadyCompleted))
      }

      (interrupted, alreadyCompleted)
    }
  }
}

/** Factory for creating RuntimeBackend adapters. */
private[eru] object RuntimeBackendAdapter {

  /** Creates an adapter for the virtual-threads backend. */
  def virtualThreads(): ConcurrencyBackend =
    new RuntimeBackendAdapter()
}
