package net.ghoula.eru

/** Runtime extensions and constructors available from the unified prelude.
  *
  * These enrich the Eru public API with concurrency, timeouts, retries, runner conveniences, and
  * constructors for runtime data types. These extensions require an implicit EruRuntime instance to
  * ensure proper isolation and no global shared state.
  *
  * @example
  *   {{{
  * import net.ghoula.eru.prelude.*
  * import java.time.Duration
  *
  * given runtime: EruRuntime = EruRuntime.create()
  *
  * val a = Eru.succeed(1)
  * val b = Eru.succeed(2)
  *
  * val ab: Eru[Throwable, (Int, Int)] = a.zipPar(b)
  *   }}}
  */
object RuntimeExtensions {

  /** Extension methods that add concurrency, reliability, and runner operations to all `Eru[E, A]`
    * values. These require an implicit EruRuntime instance.
    */
  extension [E, A](self: Eru[E, A])(using runtime: EruRuntime) {

    /** Forks this effect onto a new fiber with structured concurrency tracking.
      *
      * The forked fiber is tracked by the runtime to ensure proper cleanup at program shutdown,
      * providing structured concurrency guarantees. For long-running servers that fork thousands of
      * short-lived fibers (like HTTP handlers), consider using `forkDaemon` instead to avoid
      * accumulating completed fibers in memory.
      *
      * '''Use `fork` when:''' Tasks should complete before program exit, parallel computations
      * you'll `.await`, tasks requiring guaranteed completion (DB transactions, file writes).
      *
      * '''Use `forkDaemon` when:''' Long-running servers, fire-and-forget with finalizer cleanup,
      * abrupt termination is acceptable.
      *
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
      *
      * @see
      *   [[EruRuntime.fork]] for detailed documentation
      * @see
      *   [[forkDaemon]] for untracked daemon fibers
      */
    def fork: Eru[Nothing, Fiber[E, A]] = runtime.fork(self)

    /** Forks this effect with the provided observer, emitting fiber lifecycle events.
      *
      * @param observer
      *   observer to receive fiber events (start/completion/interruption)
      * @return
      *   an effect that produces the forked fiber
      */
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
      runtime.forkWithObserver(self, observer)

    /** Forks this effect with explicit fiber tracking for custom cleanup strategies.
      *
      * This enables applications to implement their own fiber lifecycle management, such as
      * periodic cleanup or different tracking strategies. The provided tracker receives all forked
      * fibers for manual management.
      *
      * @param tracker
      *   the fiber tracker to use for this fork
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
      */
    def forkTracked(tracker: FiberTracker): Eru[Nothing, Fiber[E, A]] =
      runtime.forkTracked(self, tracker)

    /** Forks this effect as a daemon fiber without structured concurrency tracking.
      *
      * Daemon fibers are NOT tracked for automatic cleanup at program shutdown, preventing memory
      * accumulation in long-running servers. The fiber still manages resources via finalizers -
      * only tracking is skipped. Ideal for HTTP/RPC servers forking thousands of handlers.
      *
      * '''Use when:''' Long-running servers, fire-and-forget with finalizer cleanup, abrupt
      * termination acceptable.
      *
      * '''Avoid when:''' Tasks needing guaranteed completion, DB transactions, file writes, no
      * finalizer cleanup.
      *
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
      *
      * @example
      *   {{{
      * import net.ghoula.eru.prelude.*
      *
      * given runtime: EruRuntime = EruRuntime.create()
      *
      * // HTTP server: fork connection handlers as daemon fibers
      * def acceptLoop: Eru[HttpError, Unit] = {
      *   val acceptAndHandle = for {
      *     clientSocket <- Eru.effect(serverSocket.accept())
      *     _ <- handleClient(clientSocket)
      *       .ensure(Eru.effect(clientSocket.close()))  // Finalizer cleanup
      *       .forkDaemon  // Don't track - prevents memory accumulation
      *   } yield ()
      *   Eru.forever(acceptAndHandle)
      * }
      *   }}}
      *
      * @see
      *   [[EruRuntime.forkDaemon]] for detailed documentation
      * @see
      *   [[fork]] for tracked fibers with structured concurrency
      */
    def forkDaemon: Eru[Nothing, Fiber[E, A]] = runtime.forkDaemon(self)

    /** Runs this effect and another in parallel, combining results.
      *
      * If either side fails or dies, the other is interrupted and finalizers are awaited.
      *
      * @param that
      *   the other effect to run in parallel
      * @return
      *   an effect that yields a tuple of both results on success
      */
    def zipPar[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, (A, B)] =
      runtime.zipPar(self, that)

    /** Races this effect against another, returning the first result to complete.
      *
      * The losing effect is interrupted and awaited to ensure resource safety.
      *
      * @param that
      *   the other effect to race against
      * @return
      *   either Left(thisValue) or Right(thatValue) depending on the winner
      */
    def race[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, Either[A, B]] =
      runtime.race(self, that)

    /** Adds a timeout to this effect, failing with TimeoutException if not completed in time.
      *
      * @param duration
      *   maximum duration to wait for completion
      * @return
      *   an effect that either succeeds normally or fails with TimeoutException
      */
    def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      runtime.timeout(duration)(self)

    /** Adds a timeout with a fallback value instead of failing on timeout.
      *
      * @param duration
      *   maximum duration to wait for completion
      * @param fallback
      *   value to return if a timeout occurs
      * @tparam A1
      *   widened success type for the fallback
      * @return
      *   an effect that succeeds with the original value or the fallback on timeout
      */
    def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1): Eru[E | Throwable, A1] =
      timeout(duration).recover { case _: java.util.concurrent.TimeoutException => fallback }

    /** Times out with a specific domain error instead of a generic TimeoutException.
      *
      * This is useful when you want timeout failures to stay in your typed error channel rather
      * than introducing java.util.concurrent.TimeoutException.
      *
      * @param duration
      *   maximum duration to wait for completion
      * @param timeoutError
      *   the typed error to fail with on timeout
      * @return
      *   an effect that either succeeds normally or fails with timeoutError
      */
    def failAfter[E1 >: E](duration: java.time.Duration, timeoutError: E1): Eru[E1 | Throwable, A] =
      timeout(duration).recoverWith { case _: java.util.concurrent.TimeoutException =>
        Eru.fail(timeoutError)
      }

    /** Retries this effect on typed failure according to the provided policy.
      *
      * Defects (Throwables) are propagated without retrying.
      *
      * @param policy
      *   retry policy to apply
      * @return
      *   an effect that may retry on failure
      */
    def retry(policy: EruRuntime.Policy): Eru[E, A] = runtime.retry(policy)(self)

    /** Retries this effect up to `maxRetries` times without delay.
      *
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt)
      */
    def retryN(maxRetries: Int): Eru[E, A] = runtime.retry(EruRuntime.Policy.Recurs(maxRetries))(self)

    /** Retries this effect with exponential backoff starting from `baseDuration`.
      *
      * @param baseDuration
      *   initial delay before the first retry
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt)
      */
    def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int): Eru[E, A] =
      runtime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(self)
  }

  /** Extension methods for running Eru effects (no runtime required). */
  extension [E, A](self: Eru[E, A]) {

    /** Executes this effect and returns a structured Exit value instead of throwing.
      *
      * @return
      *   the Exit representing success, typed failure, defect, or interruption
      */
    def runExit(): Exit[E, A] = self.attempt.map(Result.toExit).unsafeRunSync()

    /** Executes this effect with the provided observer for instrumentation.
      *
      * @param observer
      *   the observer to receive execution events
      * @return
      *   the effect's result
      */
    def runWith(observer: EruObserver): A = self.unsafeRunSyncWith(observer)
  }

  /** Extension methods on the Eru companion providing constructors for runtime types. */
  extension (eru: Eru.type) {

    /** Creates a new Ref initialized with the given value.
      *
      * @param initial
      *   initial value
      * @tparam A
      *   value type
      * @return
      *   an effect that produces the Ref
      */
    def ref[A](initial: A): Eru[Nothing, Ref[A]] = Ref.make(initial)

    /** Creates a new empty Deferred.
      *
      * @tparam A
      *   value type
      * @return
      *   an effect that produces the Deferred
      */
    def deferred[A](using runtime: EruRuntime): Eru[Nothing, Deferred[A]] = Deferred.make[A]

    /** Creates a new Semaphore initialized with `n` permits.
      *
      * @param n
      *   number of permits (negative values are treated as 0)
      * @return
      *   an effect that produces the Semaphore
      */
    def semaphore(n: Long)(using runtime: EruRuntime): Eru[Nothing, Semaphore] = Semaphore.make(n)

    /** Creates a bounded queue with the specified capacity.
      *
      * @param capacity
      *   the maximum number of elements the queue can hold
      * @tparam A
      *   the element type
      * @return
      *   an effect that yields a new bounded queue
      */
    def queue[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
      Queue.bounded[A](capacity)

    /** Creates an unbounded queue.
      *
      * @tparam A
      *   the element type
      * @return
      *   an effect that yields a new unbounded queue
      */
    def unboundedQueue[A](using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
      Queue.unbounded[A]

    /** Creates a bounded hub with the specified capacity per subscriber.
      *
      * @param capacity
      *   the maximum number of messages each subscriber queue can hold
      * @tparam A
      *   the message type
      * @return
      *   an effect that yields a new bounded hub
      */
    def hub[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Hub[A]] =
      Hub.bounded[A](capacity)

    /** Creates an unbounded hub.
      *
      * @tparam A
      *   the message type
      * @return
      *   an effect that yields a new unbounded hub
      */
    def unboundedHub[A](using runtime: EruRuntime): Eru[Nothing, Hub[A]] =
      Hub.unbounded[A]

    /** Creates a new promise that can be completed with either success or failure.
      *
      * @tparam E
      *   the error type
      * @tparam A
      *   the success value type
      * @return
      *   an effect that yields a new promise
      */
    def promise[E, A](using runtime: EruRuntime): Eru[Nothing, Promise[E, A]] =
      Promise.make[E, A]

    /** Creates a new countdown latch initialized with the given count.
      *
      * @param count
      *   the initial count value, must be non-negative
      * @return
      *   an effect that yields a new countdown latch
      */
    def countDownLatch(count: Int)(using runtime: EruRuntime): Eru[Nothing, CountDownLatch] =
      CountDownLatch.make(count)

    /** Creates a new cyclic barrier for the given number of parties.
      *
      * @param parties
      *   the number of parties required to trip the barrier, must be positive
      * @return
      *   an effect that yields a new cyclic barrier
      */
    def cyclicBarrier(parties: Int)(using runtime: EruRuntime): Eru[Nothing, CyclicBarrier] =
      CyclicBarrier.make(parties)
  }

  /** Static utility methods from EruRuntime exposed for direct access. */

  /** Suspends execution for the specified duration.
    *
    * @param duration
    *   the duration to sleep
    * @return
    *   an effect that completes after the duration
    */
  def sleep(duration: java.time.Duration)(using runtime: EruRuntime): Eru[Nothing, Unit] =
    runtime.sleep(duration)

  /** Executes a collection of effects in parallel, returning results in order.
    *
    * @param effects
    *   the effects to execute
    * @return
    *   an effect that yields all results in the same order as input
    */
  def parSequence[E, A](effects: List[Eru[E, A]])(using runtime: EruRuntime): Eru[E | Throwable, List[A]] =
    runtime.parSequence(effects)

  /** Executes effects derived from inputs in parallel, returning results in order.
    *
    * @param inputs
    *   the input values to process
    * @param f
    *   function to convert each input to an effect
    * @return
    *   an effect that yields all results in the same order as inputs
    */
  def parTraverse[A, E, B](inputs: List[A])(f: A => Eru[E, B])(using runtime: EruRuntime): Eru[E | Throwable, List[B]] =
    runtime.parTraverse(inputs)(f)

  /** Races multiple effects, returning the result of whichever completes first.
    *
    * @param effects
    *   the effects to race
    * @return
    *   an effect that yields the winning result and its index
    */
  def raceAll[E, A](effects: List[Eru[E, A]])(using runtime: EruRuntime): Eru[E | Throwable, (A, Int)] =
    runtime.raceAll(effects)

  /** Executes effects derived from a collection of inputs in parallel with bounded concurrency.
    *
    * This operation provides resource-controlled parallel execution by limiting the number of
    * concurrent fibers to the specified degree. This is essential for scenarios involving external
    * resources (databases, APIs, file systems) where unbounded parallelism could cause resource
    * exhaustion.
    *
    * @param n
    *   maximum number of concurrent fibers (must be positive)
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @return
    *   an effect yielding the list of results in input order
    *
    * @example
    *   {{{
    * import net.ghoula.eru.prelude.*
    *
    * given runtime: EruRuntime = EruRuntime.create()
    *
    * // Process API calls with bounded concurrency
    * val userIds = (1 to 1000).toList
    * val profiles = Eru.foreachParN(10, userIds) { id =>
    *   fetchUserProfile(id) // Max 10 concurrent API calls
    * }
    *   }}}
    */
  def foreachParN[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B])(using
    runtime: EruRuntime
  ): Eru[E | Throwable, List[B]] =
    runtime.foreachParN(n, inputs)(f)

  /** Executes effects derived from a collection of inputs in parallel with bounded concurrency,
    * discarding results.
    *
    * This operation provides resource-controlled parallel execution by limiting the number of
    * concurrent fibers to the specified degree. All results are discarded, making this optimal for
    * side-effecting operations where only completion matters.
    *
    * @param n
    *   maximum number of concurrent fibers (must be positive)
    * @param inputs
    *   the collection of inputs to process
    * @param f
    *   function to transform each input into an effect
    * @return
    *   an effect that succeeds with Unit when all operations complete
    *
    * @example
    *   {{{
    * import net.ghoula.eru.prelude.*
    *
    * given runtime: EruRuntime = EruRuntime.create()
    *
    * // Send notifications with bounded concurrency
    * val recipients = getEmailList()
    * Eru.foreachParNDiscard(5, recipients) { email =>
    *   sendNotification(email) // Max 5 concurrent sends
    * }
    *   }}}
    */
  def foreachParNDiscard[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B])(using
    runtime: EruRuntime
  ): Eru[E | Throwable, Unit] =
    runtime.foreachParNDiscard(n, inputs)(f)

  /** Validates multiple effects in parallel, accumulating all errors if any occur.
    *
    * This operation executes all effects concurrently and collects results. If all effects succeed,
    * returns the list of success values. If any effects fail, returns all accumulated errors. This
    * is particularly useful for domain validation where you want to report all validation failures
    * at once rather than stopping at the first error.
    *
    * @param effects
    *   the effects to validate in parallel
    * @return
    *   either all accumulated errors or all success values
    *
    * @example
    *   {{{
    * import net.ghoula.eru.prelude.*
    *
    * given runtime: EruRuntime = EruRuntime.create()
    *
    * // Validate user input fields in parallel
    * val validations = List(
    *   validateEmail(user.email),
    *   validateAge(user.age),
    *   validatePassword(user.password)
    * )
    *
    * validatePar(validations).flatMap {
    *   case Left(errors) =>
    *     // Report all validation errors at once
    *     Eru.fail(ValidationErrors(errors))
    *   case Right(validatedFields) =>
    *     // All fields valid, create user
    *     Eru.succeed(User(validatedFields))
    * }
    *   }}}
    */
  def validatePar[E, A](effects: List[Eru[E, A]])(using runtime: EruRuntime): Eru[Throwable, Either[List[E], List[A]]] =
    runtime.validatePar(effects)

  /** Validates effects in parallel and returns either the first error encountered or all successes.
    *
    * This operation executes all effects concurrently but follows fail-fast semantics. If any
    * effect fails, the first error is returned. If all effects succeed, all success values are
    * returned. This is useful when you need parallel execution for performance but want to stop
    * processing on the first validation failure.
    *
    * @param effects
    *   the effects to validate in parallel
    * @return
    *   either the first error or all success values
    *
    * @example
    *   {{{
    * import net.ghoula.eru.prelude.*
    *
    * given runtime: EruRuntime = EruRuntime.create()
    *
    * // Validate dependencies in parallel, fail fast on any error
    * val dependencyChecks = List(
    *   checkDatabaseConnection(),
    *   checkRedisConnection(),
    *   checkExternalApiHealth()
    * )
    *
    * validateFirst(dependencyChecks).flatMap {
    *   case Left(error) =>
    *     // First dependency failure, stop immediately
    *     Eru.fail(ServiceUnavailable(error))
    *   case Right(healthChecks) =>
    *     // All dependencies healthy
    *     Eru.succeed(HealthStatus.AllGood)
    * }
    *   }}}
    */
  def validateFirst[E, A](effects: List[Eru[E, A]])(using
    runtime: EruRuntime
  ): Eru[Throwable, Either[E | Throwable, List[A]]] =
    runtime.validateFirst(effects)
}
