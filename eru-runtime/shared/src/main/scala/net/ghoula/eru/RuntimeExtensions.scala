package net.ghoula.eru

/** Runtime extensions and constructors available from the unified prelude.
  *
  * These enrich the Eru public API with concurrency, timeouts, retries, runner conveniences, and
  * constructors for runtime data types.
  */
object RuntimeExtensions {

  /** Extension methods that add concurrency, reliability, and runner operations to all `Eru[E, A]`
    * values.
    */
  extension [E, A](self: Eru[E, A]) {

    /** Forks this effect onto a new fiber.
      *
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
      */
    def fork: Eru[Nothing, Fiber[E, A]] = EruRuntime.fork(self)

    /** Forks this effect with the provided observer, emitting fiber lifecycle events.
      *
      * @param observer
      *   observer to receive fiber events (start/completion/interruption)
      * @return
      *   an effect that produces the forked fiber
      */
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
      EruRuntime.forkWithObserver(self, observer)

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
      EruRuntime.zipPar(self, that)

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
      EruRuntime.race(self, that)

    /** Adds a timeout to this effect, failing with TimeoutException if not completed in time.
      *
      * @param duration
      *   maximum duration to wait for completion
      * @return
      *   an effect that either succeeds normally or fails with TimeoutException
      */
    def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      EruRuntime.timeout(duration)(self)

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

    /** Retries this effect on typed failure according to the provided policy.
      *
      * Defects (Throwables) are propagated without retrying.
      *
      * @param policy
      *   retry policy to apply
      * @return
      *   an effect that may retry on failure
      */
    def retry(policy: EruRuntime.Policy): Eru[E, A] = EruRuntime.retry(policy)(self)

    /** Retries this effect up to `maxRetries` times without delay.
      *
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt)
      */
    def retryN(maxRetries: Int): Eru[E, A] = EruRuntime.retry(EruRuntime.Policy.Recurs(maxRetries))(self)

    /** Retries this effect with exponential backoff starting from `baseDuration`.
      *
      * @param baseDuration
      *   initial delay before the first retry
      * @param maxRetries
      *   maximum number of retries (not counting the initial attempt)
      */
    def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int): Eru[E, A] =
      EruRuntime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(self)

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
    def deferred[A]: Eru[Nothing, Deferred[A]] = Deferred.make[A]

    /** Creates a new Semaphore initialized with `n` permits.
      *
      * @param n
      *   number of permits (negative values are treated as 0)
      * @return
      *   an effect that produces the Semaphore
      */
    def semaphore(n: Long): Eru[Nothing, Semaphore] = Semaphore.make(n)
  }
}
