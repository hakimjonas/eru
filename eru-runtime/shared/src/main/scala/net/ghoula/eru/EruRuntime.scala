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
  * @param backend
  *   the concurrency backend to use
  */
final class EruRuntime(private val backend: internal.ConcurrencyBackend) {

  /** Launches an effect on a new fiber with structured concurrency tracking.
    *
    * Forked fibers are tracked by the runtime to ensure proper cleanup at program shutdown. This
    * provides structured concurrency guarantees: when your program exits, all tracked fibers are
    * automatically awaited to ensure resource cleanup completes.
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
    * val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
    * val observer = new EruObserver {
    *   def onEvent(event: EruObserver.EruEvent): Unit = events += event
    * }
    *
    * val fiber = EruRuntime.forkWithObserver(
    *   EruRuntime.sleep(Duration.ofMillis(10)).map(_ => 42),
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
    *   optional fiber tracker for automatic cleanup
    * @return
    *   an effect yielding a fiber handle
    */
  def forkTracked[E, A](
    fa: Eru[E, A],
    tracker: FiberTracker
  ): Eru[Nothing, Fiber[E, A]] =
    backend.forkWithTracking(fa, tracker.queue)

  /** Forks an effect as a daemon fiber without structured concurrency tracking.
    *
    * Daemon fibers are NOT tracked by the runtime for automatic cleanup at program shutdown. This
    * is ideal for long-running servers that fork thousands of short-lived handlers, avoiding memory
    * accumulation from tracking completed fibers. The fiber still manages its own resources via
    * finalizers - only the tracking is skipped.
    *
    * '''When to use:'''
    *   - Long-running servers forking handlers per request (HTTP, RPC, WebSocket)
    *   - Fire-and-forget tasks with self-contained cleanup via finalizers
    *   - Tasks where abrupt termination on program exit is acceptable
    *   - Scenarios where tracking overhead outweighs structured concurrency benefits
    *
    * '''When NOT to use:'''
    *   - Tasks requiring guaranteed completion before program exit
    *   - Database transactions or file writes that must finish
    *   - Tasks without proper finalizer-based cleanup
    *   - Short-lived programs where tracking overhead is negligible
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
    *   // Cleanup always runs, even if JVM exits
    *   Eru.effect(socket.close()).attempt.unit
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
    * This method intelligently optimizes execution based on the input effects:
    *   - If both effects are pure values (Succeed/Fail), combines them without creating fibers
    *   - If one effect is pure, only forks the other effect
    *   - If both effects are computations, forks both into new fibers for parallel execution
    *
    * Both effects execute concurrently (when forked) and run to completion to ensure all resources
    * are properly cleaned up. If either effect fails, the first error encountered is propagated
    * after both effects have finished executing, guaranteeing that all finalizers run correctly.
    *
    * This approach provides stronger structured concurrency guarantees than immediate cancellation
    * by ensuring resource cleanup always completes, even under failure conditions.
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
    * // Error handling with structured cleanup
    * val failing = Eru.effect(throw new RuntimeException("failed"))
    * val withFinalizer = Eru.succeed("value").ensure(Eru.effect(println("cleanup")))
    *
    * EruRuntime.zipPar(failing, withFinalizer).attempt.unsafeRunSync()
    * // Prints "cleanup" - finalizers always execute
    *   }}}
    */
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    // Fast path: if both are pure values, just combine them without forking
    if (Eru.isPureValue(fa) && Eru.isPureValue(fb)) {
      for {
        a <- fa
        b <- fb
      } yield (a, b)
    } else if (Eru.isPureValue(fa)) {
      // Only fb needs to be forked
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
      // Only fa needs to be forked
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
      // Both need to be forked - original implementation
      for {
        fiberA <- fork(fa)
        fiberB <- fork(fb)
        exitA <- fiberA.await
        exitB <- fiberB.await
        result <- (exitA, exitB) match {
          case (Exit.Success(a), Exit.Success(b)) => Eru.succeed((a, b))
          case (Exit.Failure(e1), Exit.Failure(_)) =>
            // Just return the first error for now - proper aggregation would need type changes
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
    * val validation1 = validateEmail(email)    // Eru[String, Email]
    * val validation2 = validatePassword(pass)  // Eru[String, Password]
    *
    * runtime.zipParAll(validation1, validation2) match {
    *   case Success((email, password)) => // Both succeeded
    *   case Failure(ParallelErrors(first, rest)) => // Multiple errors collected
    *   case Failure(singleError: String) => // Only one failed
    * }
    *   }}}
    */
  def zipParAll[E1, E2, A, B](
    fa: Eru[E1, A],
    fb: Eru[E2, B]
  ): Eru[E1 | E2 | ParallelErrors[E1 | E2] | Throwable, (A, B)] =
    // Fast path: if both are pure values, just combine them without forking
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
    * This method intelligently optimizes execution based on the input effects:
    *   - If the first effect is a pure value, it wins immediately without racing
    *   - If only the second effect is pure, it wins immediately
    *   - If both effects are computations, they race concurrently
    *
    * When actual racing occurs, both effects execute concurrently and the loser is signaled to
    * cancel with its finalizers guaranteed to run, ensuring proper resource cleanup.
    *
    * Race semantics are intentionally non-deterministic when both effects are computations - either
    * effect may win depending on execution timing, system load, and scheduling decisions. This
    * makes race suitable for timeout patterns and competitive computations.
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
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    // Fast path: if either or both are pure values, we can decide the winner immediately
    if (Eru.isPureValue(fa)) {
      // fa is pure, it wins immediately
      fa.map(Left.apply)
    } else if (Eru.isPureValue(fb)) {
      // fb is pure but fa is not, fb wins
      fb.map(Right.apply)
    } else {
      // Both are effectful, use the backend implementation
      backend.race(fa, fb)
    }

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
    * timer. If the effect completes first, its result is returned. If the timer completes first, a
    * TimeoutException is thrown. The timeout behavior delegates to the backend's race
    * implementation for cancellation semantics.
    *
    * '''Backend Delegation:''' Cancellation behavior when timeout occurs varies by backend
    * capability. Concurrent backends may attempt cooperative interruption of the timed-out effect,
    * while sequential backends avoid executing the effect entirely after the timeout.
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

  /** Executes a collection of effects in parallel and returns their results in order.
    *
    * This operation forks all effects immediately and waits for all to complete before returning
    * the results. All effects run to completion regardless of individual failures - if any effect
    * fails, the operation still waits for all others to finish before returning the first error.
    * This design ensures proper finalizer execution and structured concurrency semantics.
    *
    * '''Backend Adaptation:''' Behavior adapts to the concurrency backend. Virtual Threads backends
    * use lightweight VT spawning for optimal performance. Sequential backends fall back to
    * sequential execution while maintaining the same API.
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
    *
    * // Run multiple independent effects in parallel
    * val effects = List(
    *   EruRuntime.sleep(Duration.ofMillis(100)).as("first"),
    *   EruRuntime.sleep(Duration.ofMillis(50)).as("second"),
    *   EruRuntime.sleep(Duration.ofMillis(150)).as("third")
    * )
    *
    * EruRuntime.parSequence(effects).flatMap { results =>
    *   Eru.effect(println(s"Results: $results")) // Results: ["first", "second", "third"]
    * }
    *   }}}
    */
  private def forkAll[E, A](effects: List[Eru[E, A]]): Eru[Nothing, List[Fiber[E, A]]] = {
    // Fork each effect in sequence, collecting the resulting fibers.
    // While this creates a chain of flatMap operations, it's correct and efficient:
    //   1. Fork is lightweight (just schedules the fiber, doesn't wait)
    //   2. Actual work runs asynchronously in parallel
    //   3. Eru's trampolined interpreter handles the chain stack-safely
    // This respects effect boundaries and maintains referential transparency.
    Eru.traverse(effects)(fork(_))
  }

  def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] =
    effects match {
      case Nil => Eru.succeed(List.empty[A])
      case _ =>
        // Fork all fibers in a single batch to minimize sequential overhead
        // While we can't avoid sequencing entirely, we can minimize the cost
        forkAll(effects).flatMap { fibers =>
          // Await all fibers - traverse is unavoidable here but fibers complete quickly
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
                  // If any died, throw the first one
                  Eru.effect(throw dies.head)
                } else if (errors.nonEmpty) {
                  // Return first error - proper aggregation would need type changes
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
    * val validations = List(
    *   validateAge(age),
    *   validateEmail(email),
    *   validatePhone(phone)
    * )
    *
    * runtime.parSequenceAll(validations) match {
    *   case Success(results) => // All validations passed
    *   case Failure(ParallelErrors(first, rest)) =>
    *     // Multiple validation failures
    *     println(s"Found ${rest.size + 1} validation errors")
    *   case Failure(singleError) =>
    *     // Only one validation failed
    * }
    *   }}}
    */
  def parSequenceAll[E, A](effects: List[Eru[E, A]]): Eru[E | ParallelErrors[E] | Throwable, List[A]] =
    effects match {
      case Nil => Eru.succeed(List.empty[A])
      case _ =>
        // Fork all fibers in a single batch to minimize sequential overhead
        // While we can't avoid sequencing entirely, we can minimize the cost
        forkAll(effects).flatMap { fibers =>
          // Await all fibers - traverse is unavoidable here but fibers complete quickly
          Eru.traverse(fibers)(_.await)
        }.flatMap { exits =>
          // Process exits to collect errors or return results
          exits.collectFirst { case Exit.Interrupt(fiberId, cause) => (fiberId, cause) } match {
            case Some((fiberId, cause)) =>
              Eru.interruptibleBlocking {
                throw new InterruptedException(s"ParSequenceAll interrupted due to fiber $fiberId: $cause")
              }
            case None =>
              val errors = exits.collect { case Exit.Failure(error) => error }
              val dies = exits.collect { case Exit.Die(throwable) => throwable }

              if (dies.nonEmpty) {
                // If any died, throw the first one
                Eru.effect(throw dies.head)
              } else if (errors.nonEmpty) {
                // Collect all errors
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
    * This is the high-performance bulk operation that applies a function to each input to create an
    * effect, then executes all effects in parallel. This is more efficient than manually mapping
    * and then calling parSequence, as it can optimize the entire operation as a unit.
    *
    * '''Performance:''' On Virtual Threads backends, this operation is highly optimized for bulk
    * parallel execution, avoiding individual fiber overhead and using efficient synchronization
    * primitives.
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
    *
    * // Process a list of URLs in parallel
    * val urls = List("api/users", "api/posts", "api/comments")
    *
    * EruRuntime.parTraverse(urls) { url =>
    *   EruRuntime.sleep(Duration.ofMillis(10))
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
    * index of the effect that completed first. Race semantics are intentionally non-deterministic -
    * any effect may win depending on execution timing and system conditions.
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
    *
    * // Race multiple service calls with different latencies
    * val services = List(
    *   EruRuntime.sleep(Duration.ofMillis(100)).as("service-1"),
    *   EruRuntime.sleep(Duration.ofMillis(50)).as("service-2"),  // This will win
    *   EruRuntime.sleep(Duration.ofMillis(200)).as("service-3")
    * )
    *
    * EruRuntime.raceAll(services).flatMap { case (result, index) =>
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
        // Fast path: find the first pure value and return it immediately
        effects.zipWithIndex.find { case (effect, _) => Eru.isPureValue(effect) } match {
          case Some((pureEffect, index)) =>
            // Found a pure value, it wins immediately
            pureEffect.map(a => (a, index))
          case None =>
            // All are effectful, use the recursive race
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
    *     Eru.fail(ValidationErrors(errors))
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
    * This operation executes all effects concurrently but follows fail-fast semantics. If any
    * effect fails, the first error is returned. If all effects succeed, all success values are
    * returned. This is useful when you need parallel execution for performance but want to stop
    * processing on the first validation failure.
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
    * // Validate dependencies in parallel, fail fast on any error
    * val dependencyChecks = List(
    *   checkDatabaseConnection(),
    *   checkRedisConnection(),
    *   checkExternalApiHealth()
    * )
    *
    * runtime.validateFirst(dependencyChecks).flatMap {
    *   case Left(error) =>
    *     // First dependency failure, stop immediately
    *     Eru.fail(ServiceUnavailable(error))
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
    * This should be called when the runtime is no longer needed to ensure all resources are
    * properly released and any pending fibers are awaited.
    */
  def cleanup(): Unit = backend.cleanup()
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
  lazy val shared: EruRuntime = create()

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
    case Recurs(n: Int)

    /** Retries at most `maxRetries` times with exponential backoff delays `base * 2^i`. */
    case Exponential(base: Duration, maxRetries: Int)
  }
}
