package net.ghoula.eru

object RuntimeExtensions {
  
  // Export runner conveniences
  export net.ghoula.eru.api.RuntimePreludeApi.*
  
  // Extension methods for Eru instances
  extension [E, A](eru: Eru[E, A]) {
    // Concurrency extensions
    def fork: Eru[Nothing, Fiber[E, A]] = EruRuntime.fork(eru)
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] = EruRuntime.forkWithObserver(eru, observer)
    def zipPar[E1 >: E, B](other: Eru[E1, B]): Eru[E1 | Throwable, (A, B)] = EruRuntime.zipPar(eru, other)
    def race[E1 >: E, B](other: Eru[E1, B]): Eru[E1 | Throwable, Either[A, B]] = EruRuntime.race(eru, other)
    
    // Timeout extensions
    def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] = 
      EruRuntime.timeout(duration)(eru)
    def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1): Eru[E | Throwable, A1] = 
      EruRuntime.timeout(duration)(eru).recover { case _: java.util.concurrent.TimeoutException => fallback }
    
    // Retry extensions
    def retry(policy: EruRuntime.Policy): Eru[E, A] = EruRuntime.retry(policy)(eru)
    def retryN(maxRetries: Int): Eru[E, A] = EruRuntime.retry(EruRuntime.Policy.Recurs(maxRetries))(eru)
    def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int): Eru[E, A] = 
      EruRuntime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(eru)

    // Caching extensions (moved from core due to impurity)
    /** Caches the result of this effect, computing it only once and reusing the result.
      *
      * This method provides a simple caching mechanism where the effect is executed at most once,
      * and subsequent accesses return the cached result. The implementation uses a thread-safe
      * approach suitable for the current single-threaded runtime.
      *
      * Note: This is a simple in-memory cache. For more sophisticated caching needs with TTL,
      * eviction policies, or external cache stores, consider using dedicated caching libraries from
      * the runtime module.
      *
      * @return
      *   an effect that caches its result after the first successful execution
      */
    def cached: Eru[E, A] = {
      // Thread-safe lazy computation using AtomicReference
      val resultRef = new java.util.concurrent.atomic.AtomicReference[Option[Result[E, A]]](None)

      Eru.effect {
        resultRef.get() match {
          case Some(cachedResult) => cachedResult
          case None =>
            val result = eru.attempt.unsafeRunSync()
            resultRef.compareAndSet(None, Some(result))
            result
        }
      }.attempt.flatMap {
        case Result.Success(result) => Result.toEru(result)
        case Result.Failure(_) =>
          // Fallback in case of any issues - recompute
          eru
      }
    }

    // Resource sharing extensions (moved from core due to impurity)
    /** Provides a resource that can be shared across multiple concurrent operations safely.
      *
      * This method ensures that the resource cleanup only happens once all concurrent operations
      * using the resource have completed. This is useful for expensive resources that should be
      * shared across multiple fibers.
      *
      * @note
      *   This simplified implementation provides basic sharing semantics suitable for the current
      *   single-threaded runtime. For true concurrent reference counting, use the runtime module's
      *   advanced resource management features.
      *
      * @param cleanup
      *   function to clean up the shared resource
      * @tparam F
      *   the error type of the cleanup operation
      * @return
      *   an effect representing the shared resource
      */
    def shareResource[F](cleanup: A => Eru[F, Unit]): Eru[E, A] = {
      val refCount = new java.util.concurrent.atomic.AtomicInteger(1)

      eru.flatMap { resource =>
        Eru.succeed(resource).ensure {
          Eru.effect {
            if (refCount.decrementAndGet() <= 0) {
              cleanup(resource).attempt.unsafeRunSync()
            }
          }
        }
      }
    }

    // Advanced retry extensions (moved from core due to impurity)
    /** Retries this effect using a sophisticated retry policy with conditions. */
    def retryWith(policy: patterns.ErrorHandling.RetryPolicy): Eru[E | Throwable, A] = {
      import DomainTypes.*
      import patterns.ErrorHandling.*

      /** Resource-safe delay implementation using blocking sleep. */
      def sleep(duration: java.time.Duration): Eru[Throwable, Unit] = {
        if (duration.isZero || duration.isNegative) {
          Eru.succeed(())
        } else {
          Eru.effect {
            Thread.sleep(duration.toMillis)
          }.map(_ => ())
        }
      }

      def loop(attempt: AttemptCount, context: RetryContext): Eru[E | Throwable, A] = {
        eru.attempt.flatMap {
          case Result.Success(value) =>
            Eru.succeed(value)

          case Result.Failure(error) =>
            val updatedContext = context.withError(error)
            if (policy.shouldRetry(error, attempt, updatedContext)) {
              val delay = policy.delayFor(attempt)
              sleep(delay).flatMap(_ => loop(attempt.increment, updatedContext))
            } else {
              Eru.fail(error)
            }
        }
      }

      val initialContext = RetryContext(java.time.Instant.now(), 0)
      loop(AttemptCount(0), initialContext)
    }
  }

  // Extension methods for Eru companion object to centralize effect creation
  extension (eru: Eru.type) {
    
    /** Creates a new `Ref[A]` initialized with the provided value.
      * @param initial
      *   the initial value
      * @tparam A
      *   the value type
      * @return
      *   an effect that yields the created reference
      */
    def ref[A](initial: A): Eru[Nothing, Ref[A]] = Ref.make(initial)

    /** Creates a new, empty `Deferred[A]`.
      * @tparam A
      *   the value type
      * @return
      *   an effect that yields the created deferred
      */
    def deferred[A]: Eru[Nothing, Deferred[A]] = Deferred.make[A]

    /** Creates a new semaphore initialized with `n` permits.
      * @param n
      *   the number of initial permits (negative values are treated as 0)
      * @return
      *   an effect that yields the created semaphore
      */
    def semaphore(n: Long): Eru[Nothing, Semaphore] = Semaphore.make(n)
  }
}