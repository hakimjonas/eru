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

    /** Forks this effect onto a new fiber.
      *
      * @return
      *   an effect that produces a Fiber which can be awaited or interrupted
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
}
