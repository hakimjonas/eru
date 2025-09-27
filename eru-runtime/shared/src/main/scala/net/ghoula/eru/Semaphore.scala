package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A counting semaphore that controls access to a finite number of permits.
  *
  * Operations are properly typed with Suspending/Immediate to ensure blocking operations cannot be
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

  /** Attempts to acquire n permits.
    * @param n
    *   number of permits to acquire
    * @return
    *   an effect that yields true if all permits were acquired, false otherwise
    */
  def tryAcquireN(n: Long): Immediate[Nothing, Boolean]

  /** Releases a single permit. */
  def release: Immediate[Nothing, Unit]

  /** Releases n permits. */
  def releaseN(n: Long): Immediate[Nothing, Unit]

  /** Acquires a permit, runs `fa`, and releases the permit afterward. This operation suspends until
    * a permit is available.
    * @param fa
    *   the effect to run under a single permit
    * @return
    *   a suspending effect that yields the result of fa
    */
  def withPermit[E, A](fa: => Eru[E, A]): Suspending[E, A]

  /** Acquires `n` permits, runs `fa`, and releases the permits afterward. This operation suspends
    * until all permits are available.
    * @param n
    *   number of permits required
    * @param fa
    *   the effect to run
    * @return
    *   a suspending effect that yields the result of fa
    */
  def withPermits[E, A](n: Long)(fa: => Eru[E, A]): Suspending[E, A]

  /** Tries to acquire a permit and run `fa` if successful, releasing afterward. This operation
    * never blocks.
    * @param fa
    *   the effect to run if a permit is acquired
    * @return
    *   an immediate effect yielding Some(result) if acquired, None otherwise
    */
  def withPermitTry[E, A](fa: => Eru[E, A]): Immediate[E, Option[A]]
}

object Semaphore {

  /** Creates a new semaphore initialized with `n` permits. */
  def make(n: Long)(using runtime: EruRuntime): Eru[Nothing, Semaphore] =
    for {
      stateRef <- Ref.make(State(if (n < 0) 0L else n, List.empty))
    } yield new RuntimeSemaphore(stateRef, runtime)

  private case class State(permits: Long, waiters: List[Promise[Nothing, Unit]])

  private final class RuntimeSemaphore(stateRef: Ref[State], runtime: EruRuntime) extends Semaphore {

    def acquire: Suspending[Nothing, Unit] = new Suspending({
      // First try to acquire without creating a promise
      stateRef.modify { state =>
        if (state.permits > 0) {
          // Permit available, acquire it immediately
          (state.copy(permits = state.permits - 1), true)
        } else {
          // No permits available, need to wait
          (state, false)
        }
      }.flatMap { acquired =>
        if (acquired) {
          // Got permit immediately
          Eru.unit
        } else {
          // Need to wait - create promise and register it
          for {
            promise <- Promise.make[Nothing, Unit](using runtime)
            registered <- stateRef.modify { state =>
              if (state.permits > 0) {
                // Permit became available while creating promise
                (state.copy(permits = state.permits - 1), false)
              } else {
                // Still need to wait, add to waiters
                (state.copy(waiters = state.waiters :+ promise), true)
              }
            }
            _ <-
              if (registered) {
                promise.await.eru
              } else {
                // Got permit during registration
                Eru.unit
              }
          } yield ()
        }
      }
    })

    def acquireN(n: Long): Suspending[Nothing, Unit] = new Suspending(
      if (n <= 0) Eru.unit
      else if (n == 1) acquire.eru
      else {
        // For simplicity, acquire permits one by one
        // A more efficient implementation would acquire all at once
        Eru.foreach(1L to n)(_ => acquire.eru).map(_ => ())
      }
    )

    def permitsAvailable: Immediate[Nothing, Long] = new Immediate(
      stateRef.get.map(_.permits)
    )

    def tryAcquire: Immediate[Nothing, Boolean] = new Immediate(
      stateRef.modify { state =>
        if (state.permits > 0) {
          (state.copy(permits = state.permits - 1), true)
        } else {
          (state, false)
        }
      }
    )

    def tryAcquireN(n: Long): Immediate[Nothing, Boolean] = new Immediate(
      if (n <= 0) Eru.succeed(true)
      else {
        stateRef.modify { state =>
          if (state.permits >= n) {
            (state.copy(permits = state.permits - n), true)
          } else {
            (state, false)
          }
        }
      }
    )

    def release: Immediate[Nothing, Unit] = new Immediate(
      stateRef.modify { state =>
        state.waiters match {
          case waiter :: rest =>
            // Wake up first waiter
            (state.copy(waiters = rest), Some(waiter))
          case Nil =>
            // No waiters, increase permit count
            (state.copy(permits = state.permits + 1), None)
        }
      }.flatMap {
        case Some(waiter) =>
          // Complete the waiting promise
          waiter.succeed(()).eru.map(_ => ())
        case None =>
          Eru.unit
      }
    )

    def releaseN(n: Long): Immediate[Nothing, Unit] = new Immediate(
      if (n <= 0) Eru.unit
      else {
        stateRef.modify { state =>
          val toWake = math.min(n, state.waiters.length).toInt
          val (wakingUp, stillWaiting) = state.waiters.splitAt(toWake)
          val extraPermits = n - toWake
          (state.copy(permits = state.permits + extraPermits, waiters = stillWaiting), wakingUp)
        }.flatMap { waitersToWake =>
          // Wake up all the waiters
          Eru.foreach(waitersToWake)(_.succeed(()).eru).map(_ => ())
        }
      }
    )

    def withPermit[E, A](fa: => Eru[E, A]): Suspending[E, A] =
      withPermits(1)(fa)

    def withPermits[E, A](n: Long)(fa: => Eru[E, A]): Suspending[E, A] = new Suspending(
      acquireN(n).eru.bracket(_ => releaseN(n).eru)(_ => fa)
    )

    def withPermitTry[E, A](fa: => Eru[E, A]): Immediate[E, Option[A]] = new Immediate(
      tryAcquire.eru.flatMap { acquired =>
        if (acquired) {
          fa.map(Some(_)).ensure(release.eru)
        } else {
          Eru.succeed(None)
        }
      }
    )
  }
}
