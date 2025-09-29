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
private[eru] final class BackendCapabilities(
  val virtualThreads: Boolean,
  val structuredScopes: Boolean,
  val timersNonBlocking: Boolean
)

/** Internal SPI for providing concurrency semantics to EruRuntime.
  *
  * A backend implements fork, parallel composition, race, sleep/timeout, and retry. Implementations
  * must preserve Eru’s public semantics; differences in capabilities are surfaced via
  * [[BackendCapabilities]].
  */
private[eru] trait ConcurrencyBackend {
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

  /** Launches multiple effects in batch and returns fiber handles.
    *
    * This is an optimization for parallel operations that need to fork many effects.
    * Default implementation uses traverse, but backends can override for better performance.
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
    * This is an optimization for parallel operations that need to await many fibers.
    * Default implementation uses traverse, but backends can override for better performance.
    *
    * @param fibers
    *   the fibers to await
    * @return
    *   an effect that yields a list of exits
    */
  def awaitAll[E, A](fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
    Eru.traverse(fibers)(_.await)

  /** Race semantics. Backends must cancel the loser and return the winner. */
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
    * immediately during registration. Asynchronous backends (like VTOnlyBackend) can enqueue the
    * callback for later execution on their executor.
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

  /** Cleanup method called at the end of unsafeRunSync to finalize backend state.
    *
    * This enables backends to perform necessary cleanup operations such as draining outstanding
    * child fibers, executing remaining finalizers, and releasing resources. The method is called
    * after all primary computation has completed but before returning the final result to the user.
    */
  def cleanup(): Unit = ()
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
    fa.map(Left(_))

  def sleep(duration: java.time.Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      val ms = Math.max(0L, duration.toMillis)
      Thread.sleep(ms)
    }

  def timeout[E, A](duration: java.time.Duration)(
    fa: Eru[E, A]
  ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    race(fa, sleep(duration)).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.effect(throw new TimeoutException(s"Operation timed out after $duration"))
    }
  }

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import EruRuntime.Policy.*
    def delay(i: Int): Option[java.time.Duration] = policy match {
      case Recurs(n) => if (i < n) Some(java.time.Duration.ZERO) else None
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
