package net.ghoula.eru

/** Runtime extensions and constructors available from the unified prelude.
  *
  * These enrich the Eru public API with concurrency, timeouts, retries, runner conveniences, and
  * constructors for runtime data types.
  */
object RuntimeExtensions {

  extension [E, A](self: Eru[E, A]) {
    // Concurrency
    def fork: Eru[Nothing, Fiber[E, A]] = EruRuntime.fork(self)
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] =
      EruRuntime.forkWithObserver(self, observer)

    def zipPar[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, (A, B)] =
      EruRuntime.zipPar(self, that)

    def race[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, Either[A, B]] =
      EruRuntime.race(self, that)

    // Timeouts
    def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      EruRuntime.timeout(duration)(self)

    def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1): Eru[E | Throwable, A1] =
      timeout(duration).recover { case _: java.util.concurrent.TimeoutException => fallback }

    // Retries
    def retry(policy: EruRuntime.Policy): Eru[E, A] = EruRuntime.retry(policy)(self)
    def retryN(maxRetries: Int): Eru[E, A] = EruRuntime.retry(EruRuntime.Policy.Recurs(maxRetries))(self)
    def retryWithBackoff(baseDuration: java.time.Duration, maxRetries: Int): Eru[E, A] =
      EruRuntime.retry(EruRuntime.Policy.Exponential(baseDuration, maxRetries))(self)

    // Runner conveniences
    def runExit(): Exit[E, A] = self.attempt.map(Result.toExit).unsafeRunSync()
    def runWith(observer: EruObserver): A = self.unsafeRunSyncWith(observer)
  }

  // Constructors for runtime data types
  extension (eru: Eru.type) {
    def ref[A](initial: A): Eru[Nothing, Ref[A]] = Ref.make(initial)
    def deferred[A]: Eru[Nothing, Deferred[A]] = Deferred.make[A]
    def semaphore(n: Long): Eru[Nothing, Semaphore] = Semaphore.make(n)
  }
}
