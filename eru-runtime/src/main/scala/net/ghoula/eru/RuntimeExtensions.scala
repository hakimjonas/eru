package net.ghoula.eru

/** Runtime extensions and constructors available from the unified prelude.
  *
  * These enrich the Eru public API with concurrency, timeouts, retries, runner conveniences, and
  * constructors for runtime data types. The concurrency extensions require a `given EruRuntime` to
  * ensure proper isolation and no global shared state; the runner extensions (`runExit`, `runWith`)
  * and `Eru.ref` do not.
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
      * Inside a structured scope the forked fiber is a child of that scope: when the scope unwinds,
      * the child is interrupted with `ParentTerminated` (real parent identity and exit) and
      * awaited. At the root, the fiber is tracked in the runtime's root collection and released by
      * `runtime.cleanup()`/`shutdownRootFibers` — there is no automatic await at program exit. For
      * long-running servers that fork thousands of short-lived fibers (like HTTP handlers),
      * consider `forkDaemon` instead.
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
      *   observer to receive fiber events (start/completion)
      * @return
      *   an effect that produces the forked fiber
      */
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
      runtime.forkWithObserver(self, observer)

    /** Forks this effect with explicit fiber tracking for custom cleanup strategies.
      *
      * The forked fiber is added to the tracker's queue when it is not inside a structured scope.
      * The runtime removes completed fibers from the queue incrementally, keeping memory bounded in
      * long-running servers. The queue is internal to the runtime; it is not a public handle.
      *
      * @param tracker
      *   the fiber tracker to use for this fork
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
      */
    def forkTracked(tracker: FiberTracker): Eru[Nothing, Fiber[E, A]] =
      runtime.forkTracked(self, tracker)

    /** Forks this effect as a daemon fiber without structured concurrency joining.
      *
      * Containment still applies: inside a structured scope the daemon is interrupted when the
      * scope unwinds, but the scope does not await it (finalizers run asynchronously). At the root,
      * daemons are untracked and live until the JVM exits. The fiber still manages resources via
      * finalizers - only the joining is skipped. Ideal for HTTP/RPC servers forking thousands of
      * handlers.
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
      *       .forkDaemon  // Don't join - prevents unbounded tracking
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
      * Both effects are forked and awaited before the operation completes, so forked fibers'
      * finalizers have run by the time a result or error is produced. A pure failure
      * short-circuits: the other side is not executed.
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
      * The losing effect's thread receives an interrupt; the race does not wait for the loser's
      * finalizers to run.
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
    def timeout(
      duration: java.time.Duration
    )(using m: net.ghoula.eru.time.Monotonic): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = {
      val _ = m
      runtime.timeout(duration)(self)
    }

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
    def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1)(using
      m: net.ghoula.eru.time.Monotonic
    ): Eru[E | Throwable, A1] = {
      val _ = m
      timeout(duration).recover { case _: java.util.concurrent.TimeoutException => fallback }
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
    def retryN(maxRetries: Int): Eru[E, A] = runtime.retry(EruRuntime.Policy.NoDelay(maxRetries))(self)

    /** Retries this effect with exponential backoff starting from `baseDuration`.
      *
      * @param baseDuration
      *   initial delay before the first retry
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt)
      */
    def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int)(using
      m: net.ghoula.eru.time.Monotonic
    ): Eru[E, A] = {
      val _ = m
      runtime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(self)
    }
  }

  /** Extension methods for running Eru effects (no runtime required). */
  extension [E, A](self: Eru[E, A]) {

    /** Executes this effect and returns a structured Exit value instead of throwing.
      *
      * @return
      *   the Exit representing success, typed failure, or defect; interruption is not captured in
      *   the Exit and propagates as a thrown exception
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
    * The `Monotonic` given is required as a capability witness; the runtime backend's sleep
    * implementation is already monotonic.
    *
    * @param duration
    *   the duration to sleep
    * @return
    *   an effect that completes after the duration
    */
  def sleep(
    duration: java.time.Duration
  )(using runtime: EruRuntime, m: net.ghoula.eru.time.Monotonic): Eru[Nothing, Unit] = {
    val _ = m
    runtime.sleep(duration)
  }

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
    * concurrent fibers to the specified degree. This is useful for external resources (databases,
    * APIs, file systems) where unbounded parallelism could cause resource exhaustion.
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
    * val profiles = foreachParN(10, userIds) { id =>
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
    * foreachParNDiscard(5, recipients) { email =>
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
    *     Eru.effect(throw new IllegalArgumentException(s"Validation failed: $errors"))
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
    * This operation executes all effects concurrently and waits for all of them, then returns the
    * first error in input order or all success values. Effects are not cancelled when one fails.
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
    *     // First dependency failure
    *     Eru.effect(throw new RuntimeException(s"Service unavailable: $error"))
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
