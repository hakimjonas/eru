package net.ghoula.eru

/** A permit-based coordination primitive that controls access to a limited number of resources.
  *
  * The current runtime provides non-blocking operations. Acquisition methods return a boolean flag
  * or an optional result rather than suspending the fiber. This API is forward-compatible with a
  * future, suspension-capable scheduler.
  */
trait Semaphore {

  /** The number of permits currently available. */
  def permitsAvailable: Eru[Nothing, Long]

  /** Attempts to acquire a single permit.
    * @return
    *   an effect that yields true if a permit was acquired, false otherwise
    */
  def tryAcquire: Eru[Nothing, Boolean]

  /** Attempts to acquire `n` permits.
    * @param n
    *   number of permits to acquire (non-negative)
    * @return
    *   an effect that yields true if all permits were acquired, false otherwise
    */
  def tryAcquireN(n: Long): Eru[Nothing, Boolean]

  /** Releases a single permit. */
  def release: Eru[Nothing, Unit]

  /** Releases `n` permits. */
  def releaseN(n: Long): Eru[Nothing, Unit]

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
  def make(n: Long): Eru[Nothing, Semaphore] =
    for {
      permitsRef <- Ref.make(if (n < 0) 0L else n)
    } yield new RuntimeSemaphore(permitsRef)

  private final class RuntimeSemaphore(permitsRef: Ref[Long]) extends Semaphore {

    def permitsAvailable: Eru[Nothing, Long] =
      permitsRef.get

    def tryAcquire: Eru[Nothing, Boolean] =
      permitsRef.modify { current =>
        if (current > 0) (current - 1, true)
        else (current, false)
      }

    def tryAcquireN(n: Long): Eru[Nothing, Boolean] =
      if (n <= 0) Eru.succeed(true)
      else
        permitsRef.modify { current =>
          if (current >= n) (current - n, true)
          else (current, false)
        }

    def release: Eru[Nothing, Unit] =
      permitsRef.update(_ + 1).map(_ => ())

    def releaseN(n: Long): Eru[Nothing, Unit] =
      if (n <= 0) Eru.unit
      else permitsRef.update(_ + n).map(_ => ())

    def withPermit[E, A](fa: => Eru[E, A]): Eru[E, Option[A]] =
      withPermits(1)(fa)

    def withPermits[E, A](n: Long)(fa: => Eru[E, A]): Eru[E, Option[A]] =
      tryAcquireN(n).flatMap { acquired =>
        if (acquired)
          fa.ensure(releaseN(n)).map(a => Some(a))
        else Eru.succeed(None)
      }
  }
}
