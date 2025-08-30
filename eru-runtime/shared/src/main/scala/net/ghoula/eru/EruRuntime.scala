package net.ghoula.eru

import java.time.Duration
import scala.annotation.unused

/** Minimal, type-safe runtime functions for concurrency, racing, timeouts, and retries.
  *
  * This implementation avoids touching or subclassing the sealed Eru internals. It provides
  * portable, correctness-first semantics that satisfy the public API surface and tests.
  */
object EruRuntime {

  // Backend delegation layer (H9.2). Select per-platform backend via ServiceLoader.
  private val backend = PlatformBackend.backend

  /** Forks an effect and returns a completed fiber computed synchronously. This preserves the Fiber
    * API without requiring a scheduler.
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, None)

  /** Forks with an observer, emitting lifecycle events around the synchronous run. */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, Some(observer))

  /** Runs two effects and combines their results. Implemented sequentially for portability. */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    backend.zipPar(fa, fb)

  /** Races two effects, returning the result of the left by convention for determinism. */
  def race[E1, E2, A, B](fa: Eru[E1, A], @unused fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    backend.race(fa, fb)

  /** Simple blocking sleep using Thread.sleep wrapped in effect. */
  def sleep(duration: Duration): Eru[Nothing, Unit] =
    backend.sleep(duration)

  /** Timeout via race with sleep, returning Throwable on timeout. */
  def timeout[E, A](
    duration: Duration
  )(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
    backend.timeout(duration)(fa)

  /** Retry policy for bounded retries with optional exponential backoff.
    *
    * Policies are deterministic and specify only the number of retries and, for backoff, the base
    * delay used to compute per-attempt delays. Time computations are precise and derived from the
    * attempt index `i` starting at 0 for the first retry.
    *
    * @example
    *   {{@ import java.time.Duration // Retry up to 5 times with no delay between attempts val p1 =
    *   Policy.Recurs(5)
    *
    * // Retry up to 3 times with exponential backoff starting at 10ms (10ms, 20ms, 40ms) val p2 =
    * Policy.Exponential(Duration.ofMillis(10), 3)
    * @}}
    */
  enum Policy {

    /** Retries at most `n` times with no delay between retries.
      * @param n
      *   maximum number of retries (not counting the initial attempt). Negative values are treated
      *   as 0.
      */
    case Recurs(n: Int)

    /** Retries at most `maxRetries` times with exponential backoff delays `base * 2^i`.
      * @param base
      *   initial delay used for the first retry; subsequent retries double the delay
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt). Negative values are treated
      *   as 0.
      */
    case Exponential(base: Duration, maxRetries: Int)
  }

  /** Retries on typed failure according to the provided policy. Defects (Throwables) are propagated
    * without retrying. If the typed error channel E happens to include Throwable, failures that are
    * instances of Throwable will not be retried.
    */
  def retry[E, A](policy: Policy)(fa: Eru[E, A]): Eru[E, A] =
    backend.retry(policy)(fa)
}

private final class CompletedFiber[E, A](val id: FiberId, exit0: Exit[E, A]) extends Fiber[E, A] {
  def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit0)
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit
}
