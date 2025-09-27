package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** A computation that may suspend indefinitely.
  *
  * CRITICAL: This type has NO unsafeRunSync method to prevent deadlocks. It can only be run safely
  * via fork or race operations.
  *
  * This is a value class with ZERO runtime overhead - the wrapper is completely erased by the
  * compiler.
  */
final class Suspending[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Fork this suspending computation onto a new fiber. */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork(using runtime)

  /** Race this against another suspending computation. */
  def race[E2, B](that: Suspending[E2, B])(using runtime: EruRuntime): Suspending[E | E2 | Throwable, Either[A, B]] =
    new Suspending(runtime.race(eru, that.eru))

  /** Race with a timeout. */
  def timeout(duration: Duration)(using runtime: EruRuntime): Immediate[E | Throwable, A] = {
    val timeoutEru =
      runtime.sleep(duration).flatMap(_ => Eru.fail(TimeoutError(s"Operation timed out after $duration")))
    new Immediate(runtime.race(eru, timeoutEru).map(_.merge))
  }

}

/** A computation that completes immediately without suspension.
  *
  * This type CAN be safely run synchronously via unsafeRunSync.
  *
  * This is a value class with ZERO runtime overhead - the wrapper is completely erased by the
  * compiler.
  */
final class Immediate[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Safely run this non-suspending computation synchronously. */
  def unsafeRunSync(): A = eru.unsafeRunSync()

  /** Fork this computation onto a new fiber. */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork(using runtime)

  /** Convert to a suspending computation (always safe to widen). */
  def suspending: Suspending[E, A] = new Suspending(eru)
}

case class TimeoutError(message: String) extends Exception(message)
