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
    Eru.succeed(new RuntimeSemaphore(if (n < 0) 0L else n))

  private final class RuntimeSemaphore(init: Long) extends Semaphore {
    private var permits: Long = init

    def permitsAvailable: Eru[Nothing, Long] = Eru.succeed(permits)

    def tryAcquire: Eru[Nothing, Boolean] =
      Eru.effect {
        if (permits > 0) { permits = permits - 1; true }
        else false
      }.attempt.map {
        case Result.Success(b) => b
        case Result.Failure(_) => permits > 0
      }

    def tryAcquireN(n: Long): Eru[Nothing, Boolean] =
      if (n <= 0) Eru.succeed(true)
      else
        Eru.effect {
          if (permits >= n) { permits = permits - n; true }
          else false
        }.attempt.map {
          case Result.Success(b) => b
          case Result.Failure(_) => permits >= n
        }

    def release: Eru[Nothing, Unit] =
      Eru.effect { permits = permits + 1 }.attempt.flatMap(_ => Eru.unit)

    def releaseN(n: Long): Eru[Nothing, Unit] =
      if (n <= 0) Eru.unit
      else Eru.effect { permits = permits + n }.attempt.flatMap(_ => Eru.unit)

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
