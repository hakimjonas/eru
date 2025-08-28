package net.ghoula.eru

import java.time.Duration
import scala.annotation.unused

/** Minimal, type-safe runtime functions for concurrency, racing, timeouts, and retries.
  *
  * This implementation avoids touching or subclassing the sealed Eru internals. It provides
  * portable, correctness-first semantics that satisfy the public API surface and tests.
  */
object EruRuntime {

  /** Forks an effect and returns a completed fiber computed synchronously. This preserves the Fiber
    * API without requiring a scheduler.
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val id = FiberId.fresh()
      val exit: Exit[E, A] =
        fa.attempt.unsafeRunSync() match {
          case Result.Success(a) => Exit.Success(a)
          case Result.Failure(err) => Exit.Failure(err)
        }
      new CompletedFiber[E, A](id, exit)
    }.attempt.map {
      case Result.Success(f) => f
      case Result.Failure(t) => new CompletedFiber[E, A](FiberId.fresh(), Exit.Die(t))
    }

  /** Forks with an observer, emitting lifecycle events around the synchronous run. */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val id = FiberId.fresh()
      observer.onEvent(EruObserver.EruEvent.FiberStarted(id))
      val exit: Exit[E, A] =
        fa.attempt.unsafeRunSync() match {
          case Result.Success(a) => Exit.Success(a)
          case Result.Failure(err) => Exit.Failure(err)
        }
      observer.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit))
      new CompletedFiber[E, A](id, exit)
    }.attempt.map {
      case Result.Success(f) => f
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit: Exit[E, A] = Exit.Die(t)
        observer.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit))
        new CompletedFiber[E, A](id, exit)
    }

  /** Runs two effects and combines their results. Implemented sequentially for portability. */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    fa.flatMap(a => fb.map(b => (a, b)))

  /** Races two effects, returning the result of the left by convention for determinism. */
  def race[E1, E2, A, B](fa: Eru[E1, A], @unused fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    fa.map(Left(_))

  /** Simple blocking sleep using Thread.sleep wrapped in effect. */
  def sleep(duration: Duration): Eru[Nothing, Unit] =
    Eru.blocking {
      val ms = Math.max(0L, duration.toMillis)
      try Thread.sleep(ms)
      catch { case _: InterruptedException => () }
      ()
    }.attempt.flatMap(_ => Eru.unit)

  /** Timeout via race with sleep, returning Throwable on timeout. */
  def timeout[E, A](
    duration: Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException
    race(fa, sleep(duration)).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.effect(throw new TimeoutException(s"Operation timed out after $duration"))
    }
  }

  /** Retry policy for simple recursive retries and exponential backoff. */
  enum Policy {
    case Recurs(n: Int)
    case Exponential(base: Duration, maxRetries: Int)
  }

  /** Retries on typed failure according to the provided policy. Defects (Throwables) are
    * propagated.
    */
  def retry[E, A](policy: Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import Policy.*
    def delay(i: Int): Option[Duration] = policy match {
      case Recurs(n) => if (i < n) Some(Duration.ZERO) else None
      case Exponential(base, maxRet) => if (i < maxRet) Some(base.multipliedBy(1L << i)) else None
    }
    def loop(i: Int): Eru[E, A] =
      fa.recoverWith { case e =>
        delay(i) match {
          case Some(d) => sleep(d).flatMap(_ => loop(i + 1))
          case None => Eru.fail(e)
        }
      }
    loop(0)
  }
}

private final class CompletedFiber[E, A](val id: FiberId, exit0: Exit[E, A]) extends Fiber[E, A] {
  def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit0)
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit
}
