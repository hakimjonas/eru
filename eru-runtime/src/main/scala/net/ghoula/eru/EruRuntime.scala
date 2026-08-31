package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** Tracks forked fibers for automatic cleanup.
  *
  * Used with `EruRuntime.forkTracked` to enable incremental cleanup of completed fibers, preventing
  * unbounded memory growth in long-running servers.
  */
final class FiberTracker {
  private[eru] val queue: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] =
    new ConcurrentLinkedQueue()
}

object FiberTracker {
  def apply(): FiberTracker = new FiberTracker()
}

/** Runtime for executing concurrent operations.
  *
  * The primary construction path is `EruRuntime.create()` (fresh backend) or `EruRuntime.shared`
  * (singleton). A custom `ConcurrencyBackend` can be supplied via `EruRuntime.withBackend`.
  *
  * @param backend
  *   the concurrency backend to use
  */
final class EruRuntime(private val backend: internal.ConcurrencyBackend) {

  /** Test-only accessor; see `ConcurrencyBackend.timerForTests`. Do not use in production code. */
  private[eru] def timerForTests: Option[TimerService] = backend.timerForTests

  /** Launches an effect on a new fiber with structured concurrency tracking.
    *
    * Containment is absolute: a fiber forked inside a structured scope is a child of that scope,
    * and when the scope unwinds (the forking fiber completes) the child is interrupted with
    * `ParentTerminated` carrying the real parent identity and exit, then awaited. At the root there
    * is no parent scope: fibers are tracked in the runtime's root collection and released by
    * `cleanup()`/`shutdownRootFibers` (there is no automatic await at program exit).
    *
    * '''When to use:'''
    *   - Background tasks that should complete before program exit
    *   - Parallel computations you intend to `.await` later
    *   - Tasks requiring guaranteed completion (database transactions, file writes)
    *   - Short-lived programs or batch jobs
    *
    * '''When NOT to use:'''
    *   - Long-running servers forking thousands of handlers (use `forkDaemon` instead)
    *   - Fire-and-forget tasks with self-contained cleanup
    *   - Tasks where abrupt termination is acceptable
    *
    * For long-running servers, `fork` causes memory accumulation as completed fibers remain
    * tracked. Use `forkDaemon` for handlers that manage their own lifecycle via finalizers.
    *
    * @param fa
    *   the effect to execute
    * @return
    *   an effect yielding a fiber handle
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * // Background task that should complete before program exit
    * val fiber = runtime.fork {
    *   processData().ensure(cleanupResources())
    * }.unsafeRunSync()
    *
    * // Do other work...
    *
    * // Ensure completion before continuing
    * fiber.await.unsafeRunSync() match {
    *   case Exit.Success(value) => println(s"Done: $value")
    *   case Exit.Failure(error) => println(s"Failed: $error")
    * }
    *   }}}
    *
    * @see
    *   [[forkDaemon]] for fire-and-forget tasks without tracking
    */
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, None)

  /** Launches an effect with an observer for lifecycle events.
    *
    * @param fa
    *   the effect to execute
    * @param observer
    *   the observer to receive fiber events
    * @return
    *   an effect yielding a fiber handle
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
    * val observer = new EruObserver {
    *   def onEvent(event: EruObserver.EruEvent): Unit = events += event
    * }
    *
    * val fiber = runtime.forkWithObserver(
    *   runtime.sleep(Duration.ofMillis(10)).map(_ => 42),
    *   observer
    * ).unsafeRunSync()
    *
    * val result = fiber.await.unsafeRunSync()
    *   }}}
    */
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, Some(observer))

  /** Forks an effect with explicit fiber tracking for automatic cleanup.
    *
    * This enables incremental cleanup of completed fibers, preventing unbounded memory growth in
    * long-running servers that fork many concurrent tasks (e.g., one per HTTP connection).
    *
    * @param fa
    *   the effect to execute
    * @param tracker
    *   fiber tracker for automatic cleanup
    * @return
    *   an effect yielding a fiber handle
    */
  def forkTracked[E, A](
    fa: Eru[E, A],
    tracker: FiberTracker
  ): Eru[Nothing, Fiber[E, A]] =
    backend.forkWithTracking(fa, tracker.queue)

  /** Forks an effect as a daemon fiber without structured concurrency joining.
    *
    * Containment still applies: inside a structured scope, a daemon is a child of that scope and is
    * interrupted when the scope unwinds — the only difference from `fork` is that the scope does
    * NOT await the daemon (its finalizers run asynchronously). At the root, a daemon is untracked:
    * it lives until the JVM exits and is not touched by `cleanup()`.
    *
    * This is ideal for long-running servers that fork thousands of short-lived handlers, avoiding
    * memory accumulation from tracking completed fibers. The fiber still manages its own resources
    * via finalizers - only the joining is skipped.
    *
    * '''When to use:'''
    *   - Long-running servers forking handlers per request (HTTP, RPC, WebSocket)
    *   - Fire-and-forget tasks with self-contained cleanup via finalizers
    *   - Tasks where abrupt termination on program exit is acceptable
    *   - Scenarios where joining overhead outweighs structured concurrency benefits
    *
    * '''When NOT to use:'''
    *   - Tasks requiring guaranteed completion before program exit
    *   - Database transactions or file writes that must finish
    *   - Tasks without proper finalizer-based cleanup
    *   - Short-lived programs where joining overhead is negligible
    *
    * '''Important:''' If the JVM exits while daemon fibers are running, they will be abruptly
    * terminated. Ensure your fibers clean up resources via finalizers (`.ensure`), not by relying
    * on program exit hooks.
    *
    * @param fa
    *   the effect to execute
    * @return
    *   an effect yielding a fiber handle that can still be awaited or interrupted
    *
    * @example
    *   {{{
    * // HTTP server: fork connection handlers as daemon fibers
    * def acceptLoop: Eru[HttpError, Unit] = {
    *   val acceptAndHandle = for {
    *     clientSocket <- Eru.effect(serverSocket.accept())
    *     // Each handler manages its own lifecycle via finalizers
    *     _ <- handleClient(clientSocket)
    *       .ensure(Eru.effect(clientSocket.close()))  // Finalizer ensures cleanup
    *       .forkDaemon  // Don't track - prevents memory accumulation
    *   } yield ()
    *   Eru.forever(acceptAndHandle)
    * }
    *
    * // The handler is self-contained
    * def handleClient(socket: Socket): Eru[HttpError, Unit] = {
    *   for {
    *     request <- readRequest(socket)
    *     response <- processRequest(request)
    *     _ <- writeResponse(socket, response)
    *   } yield ()
    * }.ensure(
    *   // Cleanup runs on normal completion and interruption
    *   Eru.effect(socket.close()).attempt.map(_ => ())
    * )
    *   }}}
    *
    * @see
    *   [[fork]] for tasks requiring guaranteed completion
    * @see
    *   [[forkTracked]] for custom fiber tracking strategies
    */
  def forkDaemon[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
    backend.forkDaemon(fa, None)

  /** Executes two effects in parallel and combines their results into a pair.
    *
    * Execution adapts to the input effects:
    *   - If both effects are pure values (Succeed/Fail), combines them without creating fibers
    *   - If one effect is pure, only forks the other effect
    *   - If both effects are computations, forks both into new fibers for parallel execution
    *
    * Forked fibers are awaited before the operation completes, so their finalizers have run by the
    * time a result or error is produced. A pure failure short-circuits: the other side is not
    * executed.
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
    *   val runtime = EruRuntime.create()
    * // Parallel computation that should be faster than sequential
    * val computation1 = runtime.sleep(Duration.ofMillis(100)).map(_ => "first")
    * val computation2 = runtime.sleep(Duration.ofMillis(100)).map(_ => "second")
    *
    * val (result1, result2) = runtime.zipPar(computation1, computation2).unsafeRunSync()
    * // Completes in ~100ms instead of ~200ms sequentially
    *
    * // Error handling with structured cleanup
    * val failing = Eru.effect(throw new RuntimeException("failed"))
    * val withFinalizer = Eru.succeed("value").ensure(Eru.effect(println("cleanup")))
    *
    * runtime.zipPar(failing, withFinalizer).attempt.unsafeRunSync()
    * // Prints "cleanup" - finalizers always execute
    *   }}}
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    if (Eru.isPureValue(fa) && Eru.isPureValue(fb)) {
      for {
        a <- fa
        b <- fb
      } yield (a, b)
    } else if (Eru.isPureValue(fa)) {
      for {
        a <- fa
        fiberB <- fork(fb)
        exitB <- fiberB.await
        b <- exitB match {
          case Exit.Success(value) => Eru.succeed(value)
          case Exit.Failure(error) => Eru.fail(error)
          case Exit.Die(t) => Eru.effect(throw t)
          case Exit.Interrupt(_, _) =>
            Eru.interruptibleBlocking { throw new InterruptedException("ZipPar: fiber interrupted") }
        }
      } yield (a, b)
    } else if (Eru.isPureValue(fb)) {
      for {
        fiberA <- fork(fa)
        b <- fb
        exitA <- fiberA.await
        a <- exitA match {
          case Exit.Success(value) => Eru.succeed(value)
          case Exit.Failure(error) => Eru.fail(error)
          case Exit.Die(t) => Eru.effect(throw t)
          case Exit.Interrupt(_, _) =>
            Eru.interruptibleBlocking { throw new InterruptedException("ZipPar: fiber interrupted") }
        }
      } yield (a, b)
    } else {
      for {
        fiberA <- fork(fa)
        fiberB <- fork(fb)
        exitA <- fiberA.await
        exitB <- fiberB.await
        result <- (exitA, exitB) match {
          case (Exit.Success(a), Exit.Success(b)) => Eru.succeed((a, b))
          case (Exit.Failure(e1), Exit.Failure(_)) =>
            Eru.fail(e1)
          case (Exit.Failure(e), _) => Eru.fail(e)
          case (_, Exit.Failure(e)) => Eru.fail(e)
          case (Exit.Die(t), _) => Eru.effect(throw t)
          case (_, Exit.Die(t)) => Eru.effect(throw t)
          case (Exit.Interrupt(_, _), Exit.Interrupt(_, _)) =>
            Eru.interruptibleBlocking { throw new InterruptedException("ZipPar: both fibers interrupted") }
          case (Exit.Interrupt(_, _), Exit.Success(_)) =>
            Eru.interruptibleBlocking { throw new InterruptedException("ZipPar: fiber A interrupted") }
          case (Exit.Success(_), Exit.Interrupt(_, _)) =>
            Eru.interruptibleBlocking { throw new InterruptedException("ZipPar: fiber B interrupted") }
        }
      } yield result
    }

  /** Executes two effects in parallel, collecting all errors if both fail.
    *
    * Similar to `zipPar`, but when both computations fail, returns a `ParallelErrors` containing
    * both errors instead of just the first. This provides complete error information for debugging
    * and error reporting in scenarios where multiple failures are meaningful.
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
    *   an effect yielding a tuple of both results, or `ParallelErrors` if both fail
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * val validation1 = validateEmail(email)    // Eru[String, Email]
    * val validation2 = validatePassword(pass)  // Eru[String, Password]
    *
    * runtime.zipParAll(validation1, validation2).attempt.unsafeRunSync() match {
    *   case Result.Success((email, password)) => // Both succeeded
    *   case Result.Failure(ParallelErrors(first, rest)) => // Multiple errors collected
    *   case Result.Failure(singleError: String) => // Only one failed
    * }
    *   }}}
    */
  def zipParAll[E1, E2, A, B](
    fa: Eru[E1, A],
    fb: Eru[E2, B]
  ): Eru[E1 | E2 | ParallelErrors[E1 | E2] | Throwable, (A, B)] =
    if (Eru.isPureValue(fa) && Eru.isPureValue(fb)) {
      fa.attempt.flatMap { resultA =>
        fb.attempt.flatMap { resultB =>
          (resultA, resultB) match {
            case (Result.Success(a), Result.Success(b)) => Eru.succeed((a, b))
            case (Result.Failure(e1), Result.Failure(e2)) => Eru.fail(ParallelErrors(e1, List(e2)))
            case (Result.Failure(e), _) => Eru.fail(e)
            case (_, Result.Failure(e)) => Eru.fail(e)
          }
        }
      }
    } else {
      for {
        fiberA <- fork(fa)
        fiberB <- fork(fb)
        exitA <- fiberA.await
        exitB <- fiberB.await
        result <- (exitA, exitB) match {
          case (Exit.Success(a), Exit.Success(b)) =>
            Eru.succeed((a, b))
          case (Exit.Failure(e1), Exit.Failure(e2)) =>
            Eru.fail(ParallelErrors(e1, List(e2)))
          case (Exit.Failure(e), _) =>
            Eru.fail(e)
          case (_, Exit.Failure(e)) =>
            Eru.fail(e)
          case (Exit.Die(t), _) =>
            Eru.effect(throw t)
          case (_, Exit.Die(t)) =>
            Eru.effect(throw t)
          case (Exit.Interrupt(id1, _), Exit.Interrupt(id2, _)) =>
            Eru.interruptibleBlocking {
              throw new InterruptedException(s"ZipParAll: both fibers interrupted: $id1, $id2")
            }
          case (Exit.Interrupt(id, _), _) =>
            Eru.interruptibleBlocking {
              throw new InterruptedException(s"ZipParAll: fiber interrupted: $id")
            }
          case (_, Exit.Interrupt(id, _)) =>
            Eru.interruptibleBlocking {
              throw new InterruptedException(s"ZipParAll: fiber interrupted: $id")
            }
        }
      } yield result
    }

  /** Races two effects, returning the result of whichever completes first.
    *
    * Execution adapts to the input effects:
    *   - If the first effect is a pure value, it wins immediately without racing
    *   - If only the second effect is pure, it wins immediately
    *   - If both effects are computations, they race concurrently
    *
    * When actual racing occurs, the loser's thread receives an interrupt; the race does not wait
    * for the loser's finalizers to run.
    *
    * Race semantics are non-deterministic when both effects are computations - either effect may
    * win depending on execution timing, system load, and scheduling decisions. This makes race
    * suitable for timeout patterns and competitive computations.
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
    *   val runtime = EruRuntime.create()
    * // Race a computation against a timeout
    * val computation = runtime.sleep(Duration.ofMillis(100)).map(_ => "completed")
    * val timeout = runtime.sleep(Duration.ofMillis(50)).map(_ => "timeout")
    *
    * runtime.race(computation, timeout).unsafeRunSync() match {
    *   case Left(result) => println(s"Computation won: $result")
    *   case Right(result) => println(s"Timeout won: $result")
    * }
    *
    * // Race multiple data sources
    * val primaryDB = fetchFromPrimary()
    * val fallbackDB = fetchFromFallback()
    *
    * runtime.race(primaryDB, fallbackDB).map {
    *   case Left(primary) => s"Primary: $primary"
    *   case Right(fallback) => s"Fallback: $fallback"
    * }
    *   }}}
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    if (Eru.isPureValue(fa)) {
      fa.map(Left.apply)
    } else if (Eru.isPureValue(fb)) {
      fb.map(Right.apply)
    } else {
      backend.race(fa, fb)
    }

  /** Suspends execution for the specified duration.
    *
    * The sleep behavior adapts to the underlying concurrency backend. On the JVM with Virtual
    * Threads backend, sleep uses non-blocking timers via the hashed timer wheel, allowing the
    * Virtual Thread to park efficiently without blocking carrier threads. On sequential backends,
    * sleep uses Thread.sleep with interruption handling.
    *
    * '''Cancellation:''' Sleep operations respect interruption signals and complete immediately
    * when the executing fiber is cancelled, enabling responsive cancellation behavior.
    *
    * '''Non-Blocking (JVM):''' Virtual Thread implementations park the thread rather than blocking,
    * allowing other Virtual Threads to continue executing on available carrier threads.
    *
    * The required `Monotonic` capability is a witness: the backend sleep path is monotonic and
    * never reads wall time.
    *
    * @param duration
    *   the duration to sleep (negative durations complete immediately)
    * @return
    *   an effect that completes after the specified duration
    *
    * @example
    *   {{{
    * import java.time.Duration
    *   val runtime = EruRuntime.create()
    *
    * // Simple delay
    * runtime.sleep(Duration.ofSeconds(1)).flatMap { _ =>
    *   Eru.effect(println("One second later"))
    * }
    *
    * // Timing operations
    * val start = System.nanoTime()
    * runtime.sleep(Duration.ofMillis(100)).map { _ =>
    *   val elapsed = (System.nanoTime() - start) / 1000000L
    *   s"Slept for approximately ${elapsed}ms"
    * }
    *
    * // Periodic operations with sleep
    * def periodicTask(count: Int): Eru[Throwable, Unit] = {
    *   if (count <= 0) Eru.unit
    *   else
    *     Eru.effect(println(s"Task $count")).flatMap { _ =>
    *       runtime.sleep(Duration.ofMillis(500)).flatMap(_ => periodicTask(count - 1))
    *     }
    * }
    *   }}}
    */
  def sleep(duration: Duration)(using m: net.ghoula.eru.time.Monotonic): Eru[Nothing, Unit] = {
    val _ = m
    backend.sleep(duration)
  }

  /** Races an effect against a timer, failing with TimeoutException if the timer wins.
    *
    * This operation implements timeout semantics by racing the provided effect against an internal
    * timer. If the effect completes first, its result is returned. If the timer completes first,
    * the operation fails with a `TimeoutException` in the error channel. The timeout behavior
    * delegates to the backend's race implementation for cancellation semantics.
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
    *   val runtime = EruRuntime.create()
    *
    * // Timeout a potentially slow operation
    * val slowOperation = runtime.sleep(Duration.ofSeconds(10)).map(_ => "completed")
    *
    * runtime.timeout(Duration.ofSeconds(1))(slowOperation).attempt.unsafeRunSync() match {
    *   case Result.Success(value) => println(s"Completed: $value")
    *   case Result.Failure(_: TimeoutException) => println("Operation timed out")
    *   case Result.Failure(error) => println(s"Operation failed: $error")
    * }
    *
    * // Timeout pattern for external service calls
    * def fetchWithTimeout[A](operation: Eru[Throwable, A]): Eru[Throwable, A] = {
    *   runtime.timeout(Duration.ofSeconds(5))(operation)
    *     .tapError(err => Eru.succeed(logger.warn(s"Operation timeout: $err")))
    * }
    *
    * val result = fetchWithTimeout(callExternalService()).attempt.unsafeRunSync()
    *   }}}
    */
  def timeout[E, A](
    duration: Duration
  )(
    fa: Eru[E, A]
  )(using m: net.ghoula.eru.time.Monotonic): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
    val _ = m
    backend.timeout(duration)(fa)
  }

  private type Policy = EruRuntime.Policy

  /** Retries on typed failure according to the provided policy. Defects (Throwables) are propagated
    * without retrying. If the typed error channel E happens to include Throwable, failures that are
    * instances of Throwable will not be retried.
    */
  def retry[E, A](policy: Policy)(fa: Eru[E, A]): Eru[E, A] =
    backend.retry(policy)(fa)

  /** Creates an asynchronous, suspending effect with backend-aware async boundary support.
    *
    * This method provides true async boundary capabilities that adapt to the underlying concurrency
    * backend. On the sequential backend, the callback must be invoked synchronously during
    * registration. On the Virtual Thread backend, callbacks can be enqueued for later execution,
    * enabling true non-blocking resumption.
    *
    * The registration function receives a callback that must be invoked exactly once with the
    * result. The registration itself is described by an Eru effect to remain pure and enable proper
    * resource management and finalizer execution.
    *
    * '''Backend Behavior:'''
    *   - Sequential Backend: Requires synchronous callback invocation
    *   - Virtual Thread Backend: Supports both synchronous and asynchronous callback patterns,
    *     using Virtual Thread parking for efficient non-blocking resumption
    *
    * '''Registration Contract:'''
    *   - The register function must invoke the callback exactly once with either the result or the
    *     error; additional invocations are ignored
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
    *   val runtime = EruRuntime.create()
    *
    * // Suspend on a Scala Future
    * def fromFuture[A](future: Future[A]): Eru[Throwable, A] =
    *   runtime.suspend[Throwable, A] { callback =>
    *     Eru.effect {
    *       future.onComplete {
    *         case Success(value) => callback(Right(value))
    *         case Failure(error) => callback(Left(error))
    *       }
    *     }.attempt.map(_ => ())
    *   }
    *
    * // Suspend on a Java CompletableFuture
    * def fromCompletableFuture[A](future: java.util.concurrent.CompletableFuture[A]): Eru[Throwable, A] =
    *   runtime.suspend[Throwable, A] { callback =>
    *     Eru.effect {
    *       future.whenComplete { (value, throwable) =>
    *         if (throwable != null) callback(Left(throwable))
    *         else callback(Right(value))
    *       }
    *     }.attempt.map(_ => ())
    *   }
    *   }}}
    */
  def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A] =
    backend.handleSuspend(register).flatMap {
      case Right(value) => Eru.succeed(value)
      case Left(error) => Eru.fail(error)
    }

  /** Executes a collection of effects in parallel and returns their results in order.
    *
    * This operation forks all effects and waits for all to complete before returning. All effects
    * run to completion regardless of individual failures - if any effect fails, the operation still
    * waits for all others to finish before returning the first error. This ensures forked fibers'
    * finalizers have run by the time the result is produced.
    *
    * '''Order Preservation:''' Results are returned in the same order as the input effects,
    * regardless of completion order.
    *
    * @param effects
    *   the collection of effects to execute in parallel
    * @tparam E
    *   the typed error that effects may produce
    * @tparam A
    *   the success type that effects produce
    * @return
    *   an effect yielding the list of results in input order, or the first error encountered
    *
    * @example
    *   {{{
    * import java.time.Duration
    *   val runtime = EruRuntime.create()
    *
    * // Run multiple independent effects in parallel
    * val effects = List(
    *   runtime.sleep(Duration.ofMillis(100)).map(_ => "first"),
    *   runtime.sleep(Duration.ofMillis(50)).map(_ => "second"),
    *   runtime.sleep(Duration.ofMillis(150)).map(_ => "third")
    * )
    *
    * runtime.parSequence(effects).flatMap { results =>
    *   Eru.effect(println(s"Results: $results")) // Results: ["first", "second", "third"]
    * }
    *   }}}
    */
  /** Forks each effect in sequence, collecting the resulting fibers.
    *
    * Fork is lightweight (it only schedules the fiber, it does not wait), the actual work runs
    * asynchronously in parallel, and Eru's trampolined interpreter handles the `flatMap` chain
    * stack-safely — so the sequencing respects effect boundaries while remaining referentially
    * transparent.
    */
  private def forkAll[E, A](effects: List[Eru[E, A]]): Eru[Nothing, List[Fiber[E, A]]] =
    Eru.traverse(effects)(fork)

  def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] =
    effects match {
      case Nil => Eru.succeed(List.empty[A])
      case _ =>
        forkAll(effects).flatMap { fibers =>
          Eru.traverse(fibers)(_.await).flatMap { exits =>
            exits.collectFirst { case Exit.Interrupt(fiberId, cause) => (fiberId, cause) } match {
              case Some((fiberId, cause)) =>
                Eru.interruptibleBlocking {
                  throw new InterruptedException(s"ParSequence interrupted due to fiber $fiberId: $cause")
                }
              case None =>
                val errors = exits.collect { case Exit.Failure(error) => error }
                val dies = exits.collect { case Exit.Die(throwable) => throwable }

                if (dies.nonEmpty) {
                  Eru.effect(throw dies.head)
                } else if (errors.nonEmpty) {
                  Eru.fail(errors.head)
                } else {
                  val results = exits.collect { case Exit.Success(value) => value }
                  Eru.succeed(results)
                }
            }
          }
        }
    }

  /** Executes a list of effects in parallel, collecting all errors if multiple fail.
    *
    * Similar to `parSequence`, but instead of failing fast with the first error, this method
    * collects all errors that occur during parallel execution. When multiple effects fail, returns
    * a `ParallelErrors` containing all error information.
    *
    * This is particularly useful for validation scenarios where you want to report all validation
    * errors at once rather than stopping at the first error.
    *
    * @param effects
    *   the list of effects to execute in parallel
    * @tparam E
    *   the typed error that effects may produce
    * @tparam A
    *   the success type that effects produce
    * @return
    *   an effect that succeeds with all results or fails with collected errors
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * val validations = List(
    *   validateAge(age),
    *   validateEmail(email),
    *   validatePhone(phone)
    * )
    *
    * runtime.parSequenceAll(validations).attempt.unsafeRunSync() match {
    *   case Result.Success(results) => // All validations passed
    *   case Result.Failure(ParallelErrors(first, rest)) =>
    *     // Multiple validation failures
    *     println(s"Found ${rest.size + 1} validation errors")
    *   case Result.Failure(singleError) =>
    *     // Only one validation failed
    * }
    *   }}}
    */
  def parSequenceAll[E, A](effects: List[Eru[E, A]]): Eru[E | ParallelErrors[E] | Throwable, List[A]] =
    effects match {
      case Nil => Eru.succeed(List.empty[A])
      case _ =>
        forkAll(effects).flatMap { fibers =>
          Eru.traverse(fibers)(_.await)
        }.flatMap { exits =>
          exits.collectFirst { case Exit.Interrupt(fiberId, cause) => (fiberId, cause) } match {
            case Some((fiberId, cause)) =>
              Eru.interruptibleBlocking {
                throw new InterruptedException(s"ParSequenceAll interrupted due to fiber $fiberId: $cause")
              }
            case None =>
              val errors = exits.collect { case Exit.Failure(error) => error }
              val dies = exits.collect { case Exit.Die(throwable) => throwable }

              if (dies.nonEmpty) {
                Eru.effect(throw dies.head)
              } else if (errors.nonEmpty) {
                if (errors.size == 1) {
                  Eru.fail(errors.head)
                } else {
                  Eru.fail(ParallelErrors(errors.head, errors.tail))
                }
              } else {
                val results = exits.collect { case Exit.Success(value) => value }
                if (results.size == exits.size) {
                  Eru.succeed(results)
                } else {
                  Eru.effect(throw new IllegalStateException("ParSequenceAll: Unexpected exit combination"))
                }
              }
          }
        }
    }

  /** Executes effects derived from a collection of inputs in parallel.
    *
    * Applies a function to each input to create an effect, then executes all effects in parallel.
    * Equivalent to `parSequence(inputs.map(f))`. Results are returned in input order.
    *
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @tparam A
    *   the type of input elements
    * @tparam E
    *   the typed error that effects may produce
    * @tparam B
    *   the success type that effects produce
    * @return
    *   an effect yielding the list of results in input order
    *
    * @example
    *   {{{
    * import java.time.Duration
    *   val runtime = EruRuntime.create()
    *
    * // Process a list of URLs in parallel
    * val urls = List("api/users", "api/posts", "api/comments")
    *
    * runtime.parTraverse(urls) { url =>
    *   runtime.sleep(Duration.ofMillis(10))
    *     .flatMap(_ => Eru.effect(s"Response from $url"))
    * }.flatMap { responses =>
    *   Eru.effect(println(s"All responses: $responses"))
    * }
    *   }}}
    */
  def parTraverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]] =
    parSequence(inputs.map(f))

  /** Races multiple effects and returns the result of the first one to complete, along with its
    * original index.
    *
    * This operation races all effects concurrently and returns both the winning result and the
    * index of the effect that completed first. If any effect is a pure value, the first pure value
    * in input order wins without racing. Otherwise any effect may win depending on execution timing
    * and system conditions.
    *
    * @param effects
    *   the list of effects to race (must be non-empty)
    * @tparam E
    *   the typed error that effects may produce
    * @tparam A
    *   the success type that effects produce
    * @return
    *   an effect yielding a tuple of (winning result, index of winning effect)
    *
    * @example
    *   {{{
    * import java.time.Duration
    *   val runtime = EruRuntime.create()
    *
    * // Race multiple service calls with different latencies
    * val services = List(
    *   runtime.sleep(Duration.ofMillis(100)).map(_ => "service-1"),
    *   runtime.sleep(Duration.ofMillis(50)).map(_ => "service-2"),  // This will win
    *   runtime.sleep(Duration.ofMillis(200)).map(_ => "service-3")
    * )
    *
    * runtime.raceAll(services).flatMap { case (result, index) =>
    *   Eru.effect(println(s"Winner: $result from index $index")) // Winner: service-2 from index 1
    * }
    *   }}}
    */
  def raceAll[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, (A, Int)] =
    effects match {
      case Nil =>
        Eru.effect(throw new IllegalArgumentException("raceAll: empty list of effects"))
      case single :: Nil =>
        single.map(a => (a, 0))
      case _ =>
        effects.zipWithIndex.find { case (effect, _) => Eru.isPureValue(effect) } match {
          case Some((pureEffect, index)) =>
            pureEffect.map(a => (a, index))
          case None =>
            def raceWithIndex(remaining: List[Eru[E, A]], currentIndex: Int): Eru[E | Throwable, (A, Int)] =
              remaining match {
                case Nil => Eru.effect(throw new IllegalStateException("raceAll: unexpected empty list"))
                case single :: Nil => single.map(a => (a, currentIndex))
                case current :: rest =>
                  race(current, raceWithIndex(rest, currentIndex + 1)).flatMap {
                    case Left(value) => Eru.succeed((value, currentIndex))
                    case Right((value, index)) => Eru.succeed((value, index))
                  }
              }
            raceWithIndex(effects, 0)
        }
    }

  /** Executes effects derived from a collection of inputs in parallel with bounded concurrency.
    *
    * This operation processes inputs in batches, limiting the number of concurrent fibers to the
    * specified degree. This provides resource control for large datasets while still gaining
    * parallel execution benefits.
    *
    * Each batch of up to `n` effects executes in parallel, and batches are processed sequentially
    * to maintain the concurrency bound. Results are collected in input order.
    *
    * @param n
    *   maximum number of concurrent fibers (must be positive)
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @tparam A
    *   the type of input elements
    * @tparam E
    *   the typed error that effects may produce
    * @tparam B
    *   the success type that effects produce
    * @return
    *   an effect yielding the list of results in input order
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * // Process URLs with max 3 concurrent requests
    * val urls = (1 to 100).map(i => s"api/item/$i").toList
    *
    * val results = runtime.foreachParN(3, urls) { url =>
    *   fetchFromApi(url) // Only 3 concurrent requests at a time
    * }
    *   }}}
    */
  def foreachParN[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]] = {
    require(n > 0, s"Parallelism degree must be positive, got: $n")

    val inputList = inputs.toList
    if (inputList.isEmpty) {
      Eru.succeed(Nil)
    } else {

      def processBatch(batch: List[A]): Eru[E | Throwable, List[B]] = {
        val effects = batch.map(f)
        parSequence(effects)
      }

      def processAllBatches(batches: List[List[A]]): Eru[E | Throwable, List[B]] = {
        batches match {
          case Nil => Eru.succeed(Nil)
          case head :: tail =>
            for {
              headResults <- processBatch(head)
              tailResults <- processAllBatches(tail)
            } yield headResults ++ tailResults
        }
      }

      val batches = inputList.grouped(n).toList
      processAllBatches(batches)
    }
  }

  /** Executes effects derived from a collection of inputs in parallel with bounded concurrency,
    * discarding results.
    *
    * This operation processes inputs in batches, limiting the number of concurrent fibers to the
    * specified degree. This provides resource control for large datasets while still gaining
    * parallel execution benefits. All results are discarded.
    *
    * Each batch of up to `n` effects executes in parallel, and batches are processed sequentially
    * to maintain the concurrency bound.
    *
    * @param n
    *   maximum number of concurrent fibers (must be positive)
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @tparam A
    *   the type of input elements
    * @tparam E
    *   the typed error that effects may produce
    * @tparam B
    *   the success type that effects produce (discarded)
    * @return
    *   an effect that succeeds with Unit when all operations complete
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * // Send notifications with max 5 concurrent sends
    * val userIds = (1 to 1000).toList
    *
    * runtime.foreachParNDiscard(5, userIds) { userId =>
    *   sendNotification(userId) // Only 5 concurrent sends at a time
    * }
    *   }}}
    */
  def foreachParNDiscard[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, Unit] =
    foreachParN(n, inputs)(f).map(_ => ())

  /** Validates multiple effects in parallel, accumulating all errors if any occur.
    *
    * This operation executes all effects concurrently and collects results. If all effects succeed,
    * returns the list of success values. If any effects fail, returns all accumulated errors. This
    * is particularly useful for domain validation where you want to report all validation failures
    * at once rather than stopping at the first error.
    *
    * @param effects
    *   the effects to validate in parallel
    * @tparam E
    *   the error type for validation failures
    * @tparam A
    *   the success type for valid results
    * @return
    *   either all accumulated errors or all success values
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * // Validate user input fields in parallel
    * val validations = List(
    *   validateEmail(user.email),
    *   validateAge(user.age),
    *   validatePassword(user.password)
    * )
    *
    * runtime.validatePar(validations).flatMap {
    *   case Left(errors) =>
    *     // Report all validation errors at once
    *     Eru.effect(throw new IllegalArgumentException(s"Validation failed: $errors"))
    *   case Right(validatedFields) =>
    *     // All fields valid, create user
    *     Eru.succeed(User(validatedFields))
    * }
    *   }}}
    */
  def validatePar[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[List[E], List[A]]] =
    effects match {
      case Nil => Eru.succeed(Right(List.empty[A]))
      case _ =>
        def forkAll(remaining: List[Eru[E, A]], acc: List[Fiber[E, A]]): Eru[Nothing, List[Fiber[E, A]]] =
          remaining match {
            case Nil => Eru.succeed(acc.reverse)
            case head :: tail =>
              fork(head).flatMap(fiber => forkAll(tail, fiber :: acc))
          }

        def awaitAll(fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
          fibers match {
            case Nil => Eru.succeed(Nil)
            case head :: tail =>
              for {
                exit <- head.await
                rest <- awaitAll(tail)
              } yield exit :: rest
          }

        def processExits(exits: List[Exit[E, A]]): Eru[Throwable, Either[List[E], List[A]]] = {
          exits.collectFirst { case Exit.Interrupt(fiberId, cause) => (fiberId, cause) } match {
            case Some((fiberId, cause)) =>
              Eru.interruptibleBlocking {
                throw new InterruptedException(s"ValidatePar interrupted due to fiber $fiberId: $cause")
              }
            case None =>
              val errors = exits.collect { case Exit.Failure(error) => error }
              val defects = exits.collect { case Exit.Die(throwable) => throwable }

              defects.headOption match {
                case Some(throwable) => Eru.effect(throw throwable)
                case None =>
                  if (errors.nonEmpty) {
                    Eru.succeed(Left(errors))
                  } else {
                    val results = exits.collect { case Exit.Success(value) => value }
                    Eru.succeed(Right(results))
                  }
              }
          }
        }

        for {
          fibers <- forkAll(effects, Nil)
          exits <- awaitAll(fibers)
          result <- processExits(exits)
        } yield result
    }

  /** Validates effects in parallel and returns either the first error encountered or all successes.
    *
    * This operation executes all effects concurrently and waits for all of them, then returns the
    * first error in input order or all success values. Effects are not cancelled when one fails.
    *
    * @param effects
    *   the effects to validate in parallel
    * @tparam E
    *   the error type for validation failures
    * @tparam A
    *   the success type for valid results
    * @return
    *   either the first error or all success values
    *
    * @example
    *   {{{
    *   val runtime = EruRuntime.create()
    * // Validate dependencies in parallel, fail fast on any error
    * val dependencyChecks = List(
    *   checkDatabaseConnection(),
    *   checkRedisConnection(),
    *   checkExternalApiHealth()
    * )
    *
    * runtime.validateFirst(dependencyChecks).flatMap {
    *   case Left(error) =>
    *     // First dependency failure
    *     Eru.effect(throw new RuntimeException(s"Service unavailable: $error"))
    *   case Right(healthChecks) =>
    *     // All dependencies healthy
    *     Eru.succeed(HealthStatus.AllGood)
    * }
    *   }}}
    */
  def validateFirst[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[E | Throwable, List[A]]] =
    parSequence(effects).attempt.map {
      case Result.Success(results) => Right(results)
      case Result.Failure(error) => Left(error)
    }

  /** Cleans up this runtime instance.
    *
    * Interrupts and awaits the tracked root fibers, then releases backend resources. Call when the
    * runtime is no longer needed.
    */
  def cleanup(): Unit = backend.cleanup()

  /** Observable shutdown that interrupts and awaits all root fibers with proper event emission.
    *
    * Unlike `cleanup()` which runs outside the observable program, this method returns an Eru
    * effect that can be composed with other effects. The cleanup events (StructuredCleanupStarted,
    * ChildInterruptionRequested, StructuredCleanupCompleted) are visible to observers, making
    * shutdown fully observable.
    *
    * Use this when you need accurate observability metrics at program end:
    *
    * {{{
    * val program = for {
    *   _ <- startChannelFibers(40000)
    *   _ <- runSimulation(duration)
    *   _ <- printPreCleanupSummary()
    *   (interrupted, completed) <- runtime.shutdownRootFibers(Some(diagnosticsObserver))
    *   _ <- printFinalSummary(interrupted, completed)
    * } yield ()
    * }}}
    *
    * @param observer
    *   optional observer to receive structured cleanup events (StructuredCleanupStarted,
    *   ChildInterruptionRequested, StructuredCleanupCompleted)
    * @return
    *   an effect yielding (interrupted count, already completed count)
    */
  def shutdownRootFibers(observer: Option[EruObserver] = None): Eru[Nothing, (Int, Int)] =
    backend.shutdownRootFibers(observer)
}

/** Companion object providing factory methods for creating EruRuntime instances. */
object EruRuntime {

  /** Creates a new EruRuntime instance with a fresh backend.
    *
    * Each call creates a separate runtime with its own thread pools and execution resources.
    * However, individual Eru effects remain isolated regardless of which runtime executes them, so
    * most applications should use `EruRuntime.shared` for optimal performance.
    *
    * Only create multiple runtime instances if you have specific architectural requirements like:
    *   - Different thread pool configurations for different subsystems
    *   - Explicit resource partitioning between application components
    *   - Testing scenarios that require complete runtime isolation
    *
    * Timer-based effects (`Eru.at`, `Eru.after`, wheel-backed `runtime.sleep`) are isolated per
    * runtime: each `EruRuntime.create()` gets its own `HashedTimerWheel`, and fork / race /
    * handleSuspend entry points push that runtime's wheel onto a thread-local so forked fibers
    * resolve `Eru.at` through the runtime that spawned them. Bare `Eru.at(...).unsafeRunSync()`
    * from a thread that never touched a runtime falls back to `EruRuntime.shared`'s wheel via a
    * write-once default-provider.
    */
  def create(): EruRuntime = {
    val freshBackend = createFreshBackend()
    new EruRuntime(freshBackend)
  }

  /** Creates a fresh backend instance for the current platform.
    *
    * This ensures each EruRuntime.create() call gets its own isolated backend, preventing shared
    * state issues with coordination primitives.
    */
  private def createFreshBackend(): internal.ConcurrencyBackend = {
    PlatformBackend.createFreshBackend()
  }

  /** Singleton runtime instance for convenient access across application components.
    *
    * This provides a single, reusable runtime instance to avoid unnecessary object creation across
    * your application. The runtime itself is just an execution engine - individual Eru effects
    * remain completely isolated from each other regardless of which runtime executes them.
    *
    * **Important**: "Shared" refers only to reusing the execution engine, NOT sharing state between
    * effects. Each `Eru.queue()`, `Eru.countDownLatch()`, etc. creates independent instances with
    * their own state, ensuring complete isolation even when using the shared runtime.
    *
    * Use `EruRuntime.create()` only if you need multiple runtime instances for specific
    * architectural reasons, otherwise this singleton provides optimal performance.
    */
  /** Singleton runtime instance for convenient access across application components.
    *
    * This provides a single, reusable runtime instance to avoid unnecessary object creation across
    * your application. The runtime itself is just an execution engine - individual Eru effects
    * remain completely isolated from each other regardless of which runtime executes them.
    *
    * **Important**: "Shared" refers only to reusing the execution engine, NOT sharing state between
    * effects. Each `Eru.queue()`, `Eru.countDownLatch()`, etc. creates independent instances with
    * their own state, ensuring complete isolation even when using the shared runtime.
    *
    * Use `EruRuntime.create()` only if you need multiple runtime instances for specific
    * architectural reasons, otherwise this singleton provides optimal performance.
    *
    * Initialization installs `shared`'s wheel as the `TimerService` default provider (write-once
    * via CAS), so `Eru.at(...).unsafeRunSync()` on a thread that has never touched runtime plumbing
    * still resolves a real scheduler via `TimerService.get`'s second lookup. Later
    * `EruRuntime.create()` calls do not touch the provider slot — multi-runtime setups rely on the
    * thread-local push from fork / race / handleSuspend for isolation, not on the default provider.
    */
  lazy val shared: EruRuntime = {
    val r = create()
    TimerService.installDefaultProvider(() => r.timerForTests)
    r
  }

  /** Creates a new EruRuntime with a specific backend.
    *
    * This is primarily for testing or when you need explicit control over the backend
    * implementation.
    */
  def withBackend(backend: internal.ConcurrencyBackend): EruRuntime = {
    new EruRuntime(backend)
  }

  /** Retry policy for bounded retries with optional exponential backoff. */
  enum Policy {

    /** Retries at most `n` times with no delay between retries. */
    case NoDelay(n: Int)

    /** Retries at most `maxRetries` times with exponential backoff delays `base * 2^i`. */
    case Exponential(base: Duration, maxRetries: Int)
  }
}
