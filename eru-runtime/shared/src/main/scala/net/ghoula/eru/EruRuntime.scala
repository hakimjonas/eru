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

  /** Launches an effect on a separate execution context and returns a fiber handle.
    *
    * The effect executes asynchronously while the current execution continues. On the JVM with
    * Virtual Threads backend, the effect runs on its own Virtual Thread. On sequential backends,
    * the effect executes synchronously and the fiber is immediately completed.
    *
    * The returned fiber provides await and interrupt capabilities, enabling structured concurrency
    * patterns where parent effects can control and coordinate child computations.
    *
    * @param fa
    *   the effect to execute asynchronously
    * @tparam E
    *   the error type of the forked computation
    * @tparam A
    *   the success type of the forked computation
    * @return
    *   an effect yielding a fiber handle for the launched computation
    *
    * @example
    *   {{{
    * // Fork a long-running computation
    * val fiber = EruRuntime.fork {
    *   EruRuntime.sleep(Duration.ofSeconds(1)).map(_ => "completed")
    * }.unsafeRunSync()
    *
    * // Continue with other work, then await the result
    * val result = fiber.await.unsafeRunSync() match {
    *   case Exit.Success(value) => s"Got: $value"
    *   case Exit.Failure(error) => s"Failed: $error"
    *   case other => s"Terminated: $other"
    * }
    *   }}}
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, None)

  /** Launches an effect with observer integration for fiber lifecycle tracking.
    *
    * This variant of fork includes an observer that receives structured events during the fiber's
    * execution lifecycle. Events include FiberStarted when the fiber begins execution and
    * FiberCompleted with the final exit state when the fiber terminates.
    *
    * The observer integration enables comprehensive monitoring, debugging, and tracing of
    * concurrent operations without affecting the computation semantics or performance
    * characteristics.
    *
    * @param fa
    *   the effect to execute asynchronously
    * @param observer
    *   the observer to receive fiber lifecycle events
    * @tparam E
    *   the error type of the forked computation
    * @tparam A
    *   the success type of the forked computation
    * @return
    *   an effect yielding a fiber handle for the launched computation
    *
    * @example
    *   {{{
    * // Create an observer to track fiber events
    * val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
    * val observer = new EruObserver {
    *   def onEvent(event: EruObserver.EruEvent): Unit = events += event
    * }
    *
    * // Fork with observation
    * val fiber = EruRuntime.forkWithObserver(
    *   EruRuntime.sleep(Duration.ofMillis(10)).map(_ => 42),
    *   observer
    * ).unsafeRunSync()
    *
    * val result = fiber.await.unsafeRunSync()
    * // events now contains FiberStarted and FiberCompleted events
    *   }}}
    */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, Some(observer))

  /** Executes two effects in parallel and combines their results into a pair.
    *
    * Both effects execute concurrently on separate execution contexts. On the JVM with Virtual
    * Threads backend, each effect runs on its own Virtual Thread. The operation completes when both
    * effects have finished successfully.
    *
    * '''Error Handling:''' If either effect fails, dies, or is interrupted, the other effect is
    * cancelled immediately to prevent resource leaks and ensure structured concurrency. The first
    * error encountered is propagated.
    *
    * '''Resource Safety:''' All finalizers execute correctly in FILO order even under concurrent
    * failure scenarios, maintaining Eru's resource safety guarantees.
    *
    * @param fa
    *   the first effect to execute
    * @param fb
    *   the second effect to execute
    * @tparam E1
    *   the error type of the first effect
    * @tparam E2
    *   the error type of the second effect
    * @tparam A
    *   the success type of the first effect
    * @tparam B
    *   the success type of the second effect
    * @return
    *   an effect yielding a pair of both results, or the first error encountered
    *
    * @example
    *   {{{
    * // Parallel computation that should be faster than sequential
    * val computation1 = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => "first")
    * val computation2 = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => "second")
    *
    * val (result1, result2) = EruRuntime.zipPar(computation1, computation2).unsafeRunSync()
    * // Completes in ~100ms instead of ~200ms sequentially
    *
    * // Error handling with automatic cancellation
    * val failing = Eru.effect(throw new RuntimeException("failed"))
    * val slow = EruRuntime.sleep(Duration.ofSeconds(10)).map(_ => "slow")
    *
    * EruRuntime.zipPar(failing, slow).attempt.unsafeRunSync()
    * // Fails immediately, slow computation is cancelled
    *   }}}
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    backend.zipPar(fa, fb)

  /** Races two effects, returning the result of whichever completes first.
    *
    * Both effects execute concurrently on separate execution contexts. The first effect to complete
    * (successfully or with failure) wins the race, and the losing effect is cancelled immediately
    * to prevent resource leaks and ensure structured concurrency.
    *
    * '''Non-Deterministic Behavior:''' Race semantics are intentionally non-deterministic - either
    * effect may win depending on execution timing, system load, and scheduling decisions. This
    * makes race suitable for timeout patterns and competitive computations.
    *
    * '''Cancellation:''' The losing effect is interrupted cooperatively, allowing finalizers to
    * execute properly before termination. This maintains resource safety guarantees.
    *
    * @param fa
    *   the first effect to race
    * @param fb
    *   the second effect to race
    * @tparam E1
    *   the error type of the first effect
    * @tparam E2
    *   the error type of the second effect
    * @tparam A
    *   the success type of the first effect
    * @tparam B
    *   the success type of the second effect
    * @return
    *   an effect yielding Either[A, B] with the winner's result
    *
    * @example
    *   {{{
    * // Race a computation against a timeout
    * val computation = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => "completed")
    * val timeout = EruRuntime.sleep(Duration.ofMillis(50)).map(_ => "timeout")
    *
    * EruRuntime.race(computation, timeout).unsafeRunSync() match {
    *   case Left(result) => println(s"Computation won: $result")
    *   case Right(result) => println(s"Timeout won: $result")
    * }
    *
    * // Race multiple data sources
    * val primaryDB = fetchFromPrimary()
    * val fallbackDB = fetchFromFallback()
    *
    * EruRuntime.race(primaryDB, fallbackDB).map {
    *   case Left(primary) => s"Primary: $primary"
    *   case Right(fallback) => s"Fallback: $fallback"
    * }
    *   }}}
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], @unused fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    backend.race(fa, fb)

  /** Suspends execution for the specified duration.
    *
    * The sleep behavior adapts to the underlying concurrency backend. On the JVM with Virtual
    * Threads backend, sleep uses non-blocking timers via ScheduledExecutorService, allowing the
    * Virtual Thread to park efficiently without blocking carrier threads. On sequential backends,
    * sleep uses Thread.sleep with interruption handling.
    *
    * '''Cancellation:''' Sleep operations respect interruption signals and complete immediately
    * when the executing fiber is cancelled, enabling responsive cancellation behavior.
    *
    * '''Non-Blocking (JVM):''' Virtual Thread implementations park the thread rather than blocking,
    * allowing other Virtual Threads to continue executing on available carrier threads.
    *
    * @param duration
    *   the duration to sleep (negative durations complete immediately)
    * @return
    *   an effect that completes after the specified duration
    *
    * @example
    *   {{{
    * import java.time.Duration
    *
    * // Simple delay
    * EruRuntime.sleep(Duration.ofSeconds(1)).flatMap { _ =>
    *   Eru.effect(println("One second later"))
    * }
    *
    * // Timing operations
    * val start = System.nanoTime()
    * EruRuntime.sleep(Duration.ofMillis(100)).map { _ =>
    *   val elapsed = (System.nanoTime() - start) / 1000000L
    *   s"Slept for approximately ${elapsed}ms"
    * }
    *
    * // Periodic operations with sleep
    * def periodicTask(count: Int): Eru[Nothing, Unit] = {
    *   if (count <= 0) Eru.unit
    *   else Eru.effect(println(s"Task $count")) *>
    *     EruRuntime.sleep(Duration.ofMillis(500)) *>
    *     periodicTask(count - 1)
    * }
    *   }}}
    */
  def sleep(duration: Duration): Eru[Nothing, Unit] =
    backend.sleep(duration)

  /** Races an effect against a timer, failing with TimeoutException if the timer wins.
    *
    * This operation implements timeout semantics by racing the provided effect against an internal
    * timer. If the effect completes first, its result is returned. If the timer completes first,
    * the effect is cancelled and a TimeoutException is thrown.
    *
    * '''Cancellation:''' When the timeout occurs, the target effect is interrupted cooperatively,
    * allowing finalizers to execute properly before termination. This maintains resource safety
    * even under timeout conditions.
    *
    * '''Non-Blocking Implementation:''' On JVM Virtual Threads backends, both the effect and timer
    * execute efficiently without blocking carrier threads, enabling high concurrency.
    *
    * @param duration
    *   the maximum duration to wait for the effect to complete
    * @param fa
    *   the effect to execute with timeout protection
    * @tparam E
    *   the error type of the target effect
    * @tparam A
    *   the success type of the target effect
    * @return
    *   an effect that yields the result or fails with TimeoutException
    *
    * @example
    *   {{{
    * import java.time.Duration
    * import java.util.concurrent.TimeoutException
    *
    * // Timeout a potentially slow operation
    * val slowOperation = EruRuntime.sleep(Duration.ofSeconds(10)).map(_ => "completed")
    *
    * EruRuntime.timeout(Duration.ofSeconds(1))(slowOperation).attempt.unsafeRunSync() match {
    *   case Result.Success(value) => println(s"Completed: $value")
    *   case Result.Failure(_: TimeoutException) => println("Operation timed out")
    *   case Result.Failure(error) => println(s"Operation failed: $error")
    * }
    *
    * // Timeout pattern for external service calls
    * def fetchWithTimeout[A](operation: Eru[Throwable, A]): Eru[Throwable, A] = {
    *   EruRuntime.timeout(Duration.ofSeconds(5))(operation)
    *     .tapError(err => Eru.effect(logger.warn(s"Operation timeout: $err")))
    * }
    *
    * val result = fetchWithTimeout(callExternalService()).attempt.unsafeRunSync()
    *   }}}
    */
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

  /** Creates an asynchronous, suspending effect with backend-aware async boundary support.
    *
    * This method provides true async boundary capabilities that adapt to the underlying concurrency
    * backend. On synchronous backends (like sequential/Native), the callback must be invoked
    * synchronously during registration. On asynchronous backends (like VTOnlyBackend), callbacks
    * can be enqueued for later execution, enabling true non-blocking resumption.
    *
    * The registration function receives a callback that must be invoked exactly once with the
    * result. The registration itself is described by an Eru effect to remain pure and enable proper
    * resource management and finalizer execution.
    *
    * '''Backend Behavior:'''
    *   - Sequential Backend: Requires synchronous callback invocation, throws IllegalStateException
    *     for async registration patterns
    *   - VTOnlyBackend: Supports both synchronous and asynchronous callback patterns, using Virtual
    *     Thread parking for efficient non-blocking resumption
    *
    * '''Correctness Guarantees:'''
    *   - Callbacks are invoked exactly once
    *   - Finalizers execute in FILO order across suspend/resume boundaries
    *   - Resource safety is maintained under all termination conditions
    *   - Observer integration works correctly with async boundaries
    *
    * @param register
    *   function that receives a callback and returns an effect describing how to register that
    *   callback with the asynchronous source
    * @tparam E
    *   the error type of the asynchronous computation
    * @tparam A
    *   the success type of the asynchronous computation
    * @return
    *   an effect that suspends until the callback is invoked
    *
    * @example
    *   {{{
    * import scala.concurrent.Future
    * import scala.util.{Success, Failure}
    *
    * // Suspend on a Scala Future
    * def fromFuture[A](future: Future[A]): Eru[Throwable, A] =
    *   EruRuntime.suspend[Throwable, A] { callback =>
    *     Eru.effect {
    *       future.onComplete {
    *         case Success(value) => callback(Right(value))
    *         case Failure(error) => callback(Left(error))
    *       }
    *     }
    *   }
    *
    * // Suspend on a Java CompletableFuture
    * def fromCompletableFuture[A](future: java.util.concurrent.CompletableFuture[A]): Eru[Throwable, A] =
    *   EruRuntime.suspend[Throwable, A] { callback =>
    *     Eru.effect {
    *       future.whenComplete { (value, throwable) =>
    *         if (throwable != null) callback(Left(throwable))
    *         else callback(Right(value))
    *       }
    *     }
    *   }
    *   }}}
    */
  def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A] =
    backend.handleSuspend(register).flatMap {
      case Right(value) => Eru.succeed(value)
      case Left(error) => Eru.fail(error)
    }
}

private final class CompletedFiber[E, A](val id: FiberId, exit0: Exit[E, A]) extends Fiber[E, A] {
  def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit0)
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit
}
