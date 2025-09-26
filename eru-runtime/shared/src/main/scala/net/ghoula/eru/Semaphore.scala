package net.ghoula.eru

/** A permit-based coordination primitive that controls access to a limited number of resources.
  *
  * This implementation provides both blocking and non-blocking operations with compile-time safety
  * through the suspension type system. Blocking operations return Suspending types that cannot be
  * called with unsafeRunSync, preventing deadlocks.
  */
trait Semaphore {

  /** Acquires a single permit, suspending until one is available.
    * @return
    *   a suspending effect that completes when a permit is acquired
    */
  def acquire: Suspending[Nothing, Unit]

  /** Acquires n permits, suspending until all are available.
    * @param n
    *   number of permits to acquire (must be positive)
    * @return
    *   a suspending effect that completes when all permits are acquired
    */
  def acquireN(n: Long): Suspending[Nothing, Unit]

  /** The number of permits currently available. */
  def permitsAvailable: Immediate[Nothing, Long]

  /** Alias for permitsAvailable. */
  def available: Immediate[Nothing, Long] = permitsAvailable

  /** Attempts to acquire a single permit.
    * @return
    *   an effect that yields true if a permit was acquired, false otherwise
    */
  def tryAcquire: Immediate[Nothing, Boolean]

  /** Attempts to acquire `n` permits.
    * @param n
    *   number of permits to acquire (non-negative)
    * @return
    *   an effect that yields true if all permits were acquired, false otherwise
    */
  def tryAcquireN(n: Long): Immediate[Nothing, Boolean]

  /** Releases a single permit. */
  def release: Immediate[Nothing, Unit]

  /** Releases `n` permits. */
  def releaseN(n: Long): Immediate[Nothing, Unit]

  /** Runs `fa` if a permit can be acquired, ensuring the permit is released afterward.
    * @param fa
    *   the effect to run under a single permit
    * @return
    *   an effect that yields Some(result) if acquired, or None if acquisition failed
    */
  def withPermit[E, A](fa: => Eru[E, A]): Eru[E, Option[A]]

  /** Runs `fa` if `n` permits can be acquired, ensuring they are released afterward.
    * @param n
    *   number of permits required
    * @param fa
    *   the effect to run
    * @return
    *   an effect that yields Some(result) if acquired, or None if acquisition failed
    */
  def withPermits[E, A](n: Long)(fa: => Eru[E, A]): Eru[E, Option[A]]
}

object Semaphore {

  /** Creates a new semaphore initialized with `n` permits. */
  def make(n: Long)(using runtime: EruRuntime): Eru[Nothing, Semaphore] =
    for {
      permitsRef <- Ref.make(if (n < 0) 0L else n)
    } yield new RuntimeSemaphore(permitsRef, runtime)

  private final class RuntimeSemaphore(permitsRef: Ref[Long], runtime: EruRuntime) extends Semaphore {

    def acquire: Suspending[Nothing, Unit] = new Suspending({
      tryAcquire.eru.flatMap { acquired =>
        if (acquired) Eru.unit
        else {
          // Need to suspend and wait for a permit
          for {
            promise <- Promise.make[Nothing, Unit](using runtime)
            registered <- permitsRef.modify { current =>
              if (current > 0) {
                // Permit became available during registration
                (current - 1, Right(()))
              } else {
                // Register to wait - simplified for now, proper impl would track waiters
                (current, Left(promise))
              }
            }
            result <- registered match {
              case Right(()) => Eru.unit
              case Left(p) => p.await.eru
            }
          } yield result
        }
      }
    })

    def acquireN(n: Long): Suspending[Nothing, Unit] = new Suspending({
      tryAcquireN(n).eru.flatMap { acquired =>
        if (acquired) Eru.unit
        else acquire.eru.flatMap(_ => acquireN(n - 1).eru) // Simplified recursive implementation
      }
    })

    def permitsAvailable: Immediate[Nothing, Long] = new Immediate(permitsRef.get)

    def tryAcquire: Immediate[Nothing, Boolean] = new Immediate(permitsRef.modify { current =>
      if (current > 0) (current - 1, true)
      else (current, false)
    })

    def tryAcquireN(n: Long): Immediate[Nothing, Boolean] = new Immediate(
      if (n <= 0) Eru.succeed(true)
      else
        permitsRef.modify { current =>
          if (current >= n) (current - n, true)
          else (current, false)
        }
    )

    def release: Immediate[Nothing, Unit] = new Immediate(permitsRef.update(_ + 1).map(_ => ()))

    def releaseN(n: Long): Immediate[Nothing, Unit] = new Immediate(
      if (n <= 0) Eru.unit
      else permitsRef.update(_ + n).map(_ => ())
    )

    def withPermit[E, A](fa: => Eru[E, A]): Eru[E, Option[A]] =
      withPermits(1)(fa)

    def withPermits[E, A](n: Long)(fa: => Eru[E, A]): Eru[E, Option[A]] =
      tryAcquireN(n).eru.flatMap { acquired =>
        if (acquired)
          fa.ensure(releaseN(n).eru).map(a => Some(a))
        else Eru.succeed(None)
      }
  }
}
