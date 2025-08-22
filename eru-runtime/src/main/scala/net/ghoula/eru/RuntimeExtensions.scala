package net.ghoula.eru

object RuntimeExtensions {
  
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