package net.ghoula.eru.internal

import java.time.Duration

import net.ghoula.eru.*

/** Capabilities exposed by a concurrency backend.
  *
  * @param virtualThreads
  *   whether the backend runs effects on Java Virtual Threads
  * @param structuredScopes
  *   whether Structured Concurrency scopes are in use
  * @param timersNonBlocking
  *   whether timers/sleeps are implemented without blocking threads
  */
final class BackendCapabilities(
  val virtualThreads: Boolean,
  val structuredScopes: Boolean,
  val timersNonBlocking: Boolean
)

/** SPI for providing concurrency semantics to `EruRuntime`.
  *
  * A backend implements fork, parallel composition, race, sleep/timeout, and retry. Implementations
  * must preserve Eru's public semantics; differences in capabilities are surfaced via
  * [[BackendCapabilities]].
  *
  * The JVM Virtual Thread backend is registered through `BackendProvider` and selected by default.
  * Custom backends can be supplied via `EruRuntime.withBackend`.
  */
trait ConcurrencyBackend {
  def capabilities: BackendCapabilities

  /** Launches the effect and returns a fiber handle.
    * @param fa
    *   the effect to run
    * @param observer
    *   optional observer to receive fiber lifecycle events
    * @return
    *   an effect that yields a fiber handle
    */
  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]]

  /** Fork with custom fiber tracking queue for incremental cleanup.
    * @param fa
    *   the effect to run
    * @param customTracking
    *   queue for tracking fibers; the default implementation ignores it, backends may override
    * @return
    *   an effect that yields a fiber handle
    */
  private[eru] def forkWithTracking[E, A](
    fa: Eru[E, A],
    @scala.annotation.unused customTracking: java.util.concurrent.ConcurrentLinkedQueue[
      net.ghoula.eru.UnifiedFiber[?, ?]
    ]
  ): Eru[Nothing, Fiber[E, A]] =
    fork(fa, None)

  /** Forks an effect as a daemon fiber without structured concurrency tracking.
    *
    * Daemon fibers are not tracked for automatic cleanup at program shutdown, making this ideal for
    * long-running servers that fork thousands of short-lived handlers (e.g., HTTP connection
    * handlers). The fiber still manages its own resources via finalizers.
    *
    * Use this for fire-and-forget patterns where the fiber's lifecycle is self-contained. Use
    * regular `fork` when you need structured concurrency guarantees and automatic cleanup at
    * program termination.
    *
    * @param fa
    *   the effect to execute
    * @param observer
    *   optional observer to receive fiber lifecycle events
    * @return
    *   an effect that yields a fiber handle
    */
  def forkDaemon[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]] =
    fork(fa, observer)

  /** Launches multiple effects in batch and returns fiber handles.
    *
    * This is an optimization for parallel operations that need to fork many effects. Default
    * implementation uses traverse, but backends can override for better performance.
    *
    * @param effects
    *   the effects to run in parallel
    * @return
    *   an effect that yields a list of fiber handles
    */
  def forkBatch[E, A](effects: List[Eru[E, A]]): Eru[Nothing, List[Fiber[E, A]]] =
    Eru.traverse(effects)(fork(_, None))

  /** Awaits multiple fibers in batch and returns their exits.
    *
    * This is an optimization for parallel operations that need to await many fibers. Default
    * implementation uses traverse, but backends can override for better performance.
    *
    * @param fibers
    *   the fibers to await
    * @return
    *   an effect that yields a list of exits
    */
  def awaitAll[E, A](fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
    Eru.traverse(fibers)(_.await)

  /** Races two effects. Backends that implement actual racing should cancel the loser and return
    * the winner. The sequential fallback runs `fa` first and falls back to `fb` only when `fa`
    * fails (single-threaded, so no true concurrency is possible).
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]]

  /** Sleep for the given duration. May block or be non-blocking depending on backend. */
  def sleep(duration: Duration): Eru[Nothing, Unit]

  /** Time out the given effect after the duration. */
  def timeout[E, A](duration: Duration)(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A]

  /** Retry typed failures according to the provided policy. */
  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A]

  /** Handles async boundary registration with backend-specific semantics.
    *
    * This method enables backends to provide either synchronous or asynchronous callback handling
    * based on their capabilities. Synchronous backends (like sequential) must invoke the callback
    * immediately during registration. Asynchronous backends (like the Virtual Thread backend) can
    * enqueue the callback for later execution.
    *
    * @param register
    *   function that registers a callback with an async source, returning an Eru effect describing
    *   the registration process
    * @tparam E
    *   the error type of the suspended computation
    * @tparam A
    *   the success type of the suspended computation
    * @return
    *   an effect that yields the suspended result
    */
  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]]

  /** Test-only accessor for the backend's per-instance TimerService, if any.
    *
    * Exposed so correctness-invariant tests can prove that distinct EruRuntime instances carry
    * distinct timer services (see the "EruRuntime timer isolation" property test). Production code
    * MUST NOT use this — `Eru.at` / `Eru.after` resolve their timer through `TimerService.get` (a
    * thread-local installed by the runtime), not through this accessor.
    */
  private[eru] def timerForTests: Option[TimerService] = None

  /** Cleans up backend state.
    *
    * Enables backends to drain outstanding child fibers, execute remaining finalizers, and release
    * resources. Called manually (via `EruRuntime.cleanup()` or test suites); not called by
    * `unsafeRunSync`.
    */
  def cleanup(): Unit = ()

  /** Observable cleanup that interrupts and awaits all root fibers, emitting observer events.
    *
    * Unlike `cleanup()` which runs outside the observable program, this method returns an Eru
    * effect that can be composed with other effects and whose events are visible to observers. This
    * is essential for proper observability during graceful shutdown.
    *
    * The default implementation returns `(0, 0)`, indicating there are no tracked fibers to clean
    * up.
    *
    * @param observer
    *   optional observer to receive structured cleanup events
    * @return
    *   an effect that yields cleanup statistics (interrupted count, already completed count)
    */
  private[eru] def shutdownRootFibers(
    @scala.annotation.unused observer: Option[EruObserver] = None
  ): Eru[Nothing, (Int, Int)] =
    Eru.succeed((0, 0))
}

/** Shared synchronous backend for fallback scenarios. */
private[eru] object SharedSynchronousBackend extends ConcurrencyBackend {

  val capabilities: BackendCapabilities = new BackendCapabilities(
    virtualThreads = false,
    structuredScopes = false,
    timersNonBlocking = false
  )

  private def computeExit[E, A](fa: Eru[E, A]): Exit[E, A] =
    try Result.toExit(fa.attempt.unsafeRunSync())
    catch { case t: Throwable => Exit.Die(t) }

  private def completed[E, A](id: FiberId, exit: Exit[E, A], observerOpt: Option[EruObserver]): Fiber[E, A] = {
    observerOpt.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
    UnifiedFiber.completed[E, A](id, exit)
  }

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val id = FiberId.fresh()
      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
      val exit = computeExit(fa)
      completed(id, exit, observer)
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit: Exit[E, A] = Exit.Die(t)
        completed(id, exit, observer)
    }

  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    fa.attempt.flatMap {
      case Result.Success(a) => Eru.succeed(Left(a))
      case Result.Failure(_) =>
        fb.attempt.flatMap {
          case Result.Success(b) => Eru.succeed(Right(b))
          case Result.Failure(e2) => Eru.fail(e2)
        }
    }

  def sleep(duration: java.time.Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      net.ghoula.eru.time.SystemMonotonic.sleepAtLeast(math.max(0L, duration.toNanos))
    }

  /** Timeout on the sequential backend: the effect runs to completion (single-threaded execution
    * cannot be preempted), and the deadline is reported honestly — a success past the deadline
    * becomes `TimeoutException` instead of silently ignoring the timeout. Typed failures pass
    * through unchanged.
    */
  def timeout[E, A](duration: java.time.Duration)(
    fa: Eru[E, A]
  ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    if (duration.isNegative || duration.isZero) {
      Eru.fail(new TimeoutException(s"Operation timed out after $duration"))
    } else {
      Eru.effectTotal(System.nanoTime()).flatMap { startNanos =>
        fa.attempt.flatMap {
          case Result.Failure(e) =>
            Eru.fail(e)
          case Result.Success(a) =>
            Eru.effect {
              if (System.nanoTime() - startNanos >= duration.toNanos) {
                throw new TimeoutException(s"Operation timed out after $duration")
              } else {
                a
              }
            }
        }
      }
    }
  }

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
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

  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    Eru.effect {
      val cbBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
      val cb: Either[E, A] => Unit = ea => cbBox.set(Some(ea))

      val registrationExit = register(cb).attempt.unsafeRunSync()

      cbBox.get() match {
        case Some(result) => result
        case None =>
          registrationExit match {
            case Result.Success(_) =>
              throw new IllegalStateException(
                "Eru.suspend: asynchronous registration is not supported in the synchronous kernel; the register function must invoke the callback synchronously."
              )
            case Result.Failure(t) =>
              throw t
          }
      }
    }.attempt.flatMap {
      case Result.Success(result) => Eru.succeed(result)
      case Result.Failure(t) =>
        Eru.succeed(Left(t))
    }
}
