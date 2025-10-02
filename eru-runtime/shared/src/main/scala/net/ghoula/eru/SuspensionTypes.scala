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
  *
  * @example
  *   {{{
  * import net.ghoula.eru.prelude.*
  * import java.time.Duration
  *
  * val queue: Queue[String] = Eru.queue[String](10).unsafeRunSync()
  *
  * // Suspending operations compose naturally
  * val program: Suspending[Nothing, String] = for {
  *   item <- queue.take
  *   processed = item.toUpperCase
  * } yield processed
  *
  * // Must use fork or timeout to run safely
  * val result = program.timeout(Duration.ofSeconds(1)).unsafeRunSync()
  *   }}}
  */
final class Suspending[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Transforms the success value using the given function.
    *
    * @param f
    *   pure function to transform the success value
    * @return
    *   a new Suspending effect with the transformed value
    *
    * @example
    *   {{{
    * val queue: Queue[Int] = Eru.queue[Int](10).unsafeRunSync()
    * val doubled: Suspending[Nothing, Int] = queue.take.map(_ * 2)
    *   }}}
    */
  def map[B](f: A => B): Suspending[E, B] =
    new Suspending(eru.map(f))

  /** Chains this suspending computation with another effect-returning function.
    *
    * @param f
    *   function that returns an effect to chain
    * @return
    *   a new Suspending effect with the chained computation
    *
    * @example
    *   {{{
    * val queue1: Queue[String] = Eru.queue[String](10).unsafeRunSync()
    * val queue2: Queue[String] = Eru.queue[String](10).unsafeRunSync()
    *
    * val transfer: Suspending[Nothing, Unit] = queue1.take.flatMap(item => queue2.put(item))
    *   }}}
    */
  def flatMap[E2, B](f: A => Eru[E2, B]): Suspending[E | E2, B] =
    new Suspending(eru.flatMap(f))

  /** Combines this with another suspending computation into a tuple.
    *
    * @param that
    *   the other suspending computation to combine with
    * @return
    *   a Suspending effect yielding a tuple of both results
    */
  def zip[E2, B](that: Suspending[E2, B]): Suspending[E | E2, (A, B)] =
    new Suspending(eru.zip(that.eru))

  /** Provides a fallback computation if this one fails.
    *
    * @param that
    *   the fallback suspending computation
    * @return
    *   a Suspending effect that uses the fallback on failure
    */
  def orElse[E2, A1 >: A](that: => Suspending[E2, A1]): Suspending[E | E2, A1] =
    new Suspending(eru.orElse(that.eru))

  /** Recovers from typed errors using a partial function.
    *
    * @param pf
    *   partial function to handle errors
    * @return
    *   a Suspending effect with error recovery
    */
  def recover[A1 >: A](pf: PartialFunction[E, A1]): Suspending[E, A1] =
    new Suspending(eru.recover(pf))

  /** Recovers from typed errors using an effect-returning partial function.
    *
    * @param pf
    *   partial function returning a recovery effect
    * @return
    *   a Suspending effect with effect-based error recovery
    */
  def recoverWith[E2, A1 >: A](pf: PartialFunction[E, Eru[E2, A1]]): Suspending[E | E2, A1] =
    new Suspending(eru.recoverWith(pf))

  /** Converts errors and successes into a Result, eliminating the error channel.
    *
    * @return
    *   a Suspending effect that cannot fail at the type level
    */
  def attempt: Suspending[Nothing, Result[E, A]] =
    new Suspending(eru.attempt)

  /** Fork this suspending computation onto a new fiber.
    *
    * @param runtime
    *   the runtime to use for forking
    * @return
    *   an effect that yields a fiber handle
    */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork(using runtime)

  /** Race this against another suspending computation.
    *
    * @param that
    *   the other computation to race
    * @param runtime
    *   the runtime to use for racing
    * @return
    *   a Suspending effect yielding Either with the winner's result
    */
  def race[E2, B](that: Suspending[E2, B])(using runtime: EruRuntime): Suspending[E | E2 | Throwable, Either[A, B]] =
    new Suspending(runtime.race(eru, that.eru))

  /** Race with a timeout.
    *
    * @param duration
    *   the timeout duration
    * @param runtime
    *   the runtime to use for timeout
    * @return
    *   an Immediate effect that completes with the result or timeout error
    */
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
  *
  * @example
  *   {{{
  * import net.ghoula.eru.prelude.*
  *
  * val queue: Queue[Int] = Eru.queue[Int](10).unsafeRunSync()
  *
  * // Immediate operations compose naturally
  * val program: Immediate[Nothing, Boolean] = for {
  *   success <- queue.tryPut(42)
  *   _ = println(s"Put succeeded: $success")
  * } yield success
  *
  * // Can run safely without timeout
  * val result: Boolean = program.unsafeRunSync()
  *   }}}
  */
final class Immediate[+E, +A](val eru: Eru[E, A]) extends AnyVal {

  /** Transforms the success value using the given function.
    *
    * @param f
    *   pure function to transform the success value
    * @return
    *   a new Immediate effect with the transformed value
    *
    * @example
    *   {{{
    * val ref: Ref[Int] = Eru.ref(10).unsafeRunSync()
    * val doubled: Immediate[Nothing, Int] = ref.get.map(_ * 2)
    * val result: Int = doubled.unsafeRunSync()
    *   }}}
    */
  def map[B](f: A => B): Immediate[E, B] =
    new Immediate(eru.map(f))

  /** Chains this immediate computation with another effect-returning function.
    *
    * @param f
    *   function that returns an effect to chain
    * @return
    *   a new Immediate effect with the chained computation
    *
    * @example
    *   {{{
    * val ref: Ref[Int] = Eru.ref(0).unsafeRunSync()
    * val program: Immediate[Nothing, Int] = ref.get.flatMap(n => ref.set(n + 1).map(_ => n))
    * val result: Int = program.unsafeRunSync()
    *   }}}
    */
  def flatMap[E2, B](f: A => Eru[E2, B]): Immediate[E | E2, B] =
    new Immediate(eru.flatMap(f))

  /** Combines this with another immediate computation into a tuple.
    *
    * @param that
    *   the other immediate computation to combine with
    * @return
    *   an Immediate effect yielding a tuple of both results
    */
  def zip[E2, B](that: Immediate[E2, B]): Immediate[E | E2, (A, B)] =
    new Immediate(eru.zip(that.eru))

  /** Provides a fallback computation if this one fails.
    *
    * @param that
    *   the fallback immediate computation
    * @return
    *   an Immediate effect that uses the fallback on failure
    */
  def orElse[E2, A1 >: A](that: => Immediate[E2, A1]): Immediate[E | E2, A1] =
    new Immediate(eru.orElse(that.eru))

  /** Recovers from typed errors using a partial function.
    *
    * @param pf
    *   partial function to handle errors
    * @return
    *   an Immediate effect with error recovery
    */
  def recover[A1 >: A](pf: PartialFunction[E, A1]): Immediate[E, A1] =
    new Immediate(eru.recover(pf))

  /** Recovers from typed errors using an effect-returning partial function.
    *
    * @param pf
    *   partial function returning a recovery effect
    * @return
    *   an Immediate effect with effect-based error recovery
    */
  def recoverWith[E2, A1 >: A](pf: PartialFunction[E, Eru[E2, A1]]): Immediate[E | E2, A1] =
    new Immediate(eru.recoverWith(pf))

  /** Converts errors and successes into a Result, eliminating the error channel.
    *
    * @return
    *   an Immediate effect that cannot fail at the type level
    */
  def attempt: Immediate[Nothing, Result[E, A]] =
    new Immediate(eru.attempt)

  /** Safely run this non-suspending computation synchronously.
    *
    * @return
    *   the success value
    * @throws EruException
    *   if the computation fails
    */
  def unsafeRunSync(): A = eru.unsafeRunSync()

  /** Fork this computation onto a new fiber.
    *
    * @param runtime
    *   the runtime to use for forking
    * @return
    *   an effect that yields a fiber handle
    */
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]] =
    eru.fork(using runtime)

  /** Convert to a suspending computation (always safe to widen).
    *
    * @return
    *   a Suspending wrapper around this computation
    */
  def suspending: Suspending[E, A] = new Suspending(eru)
}

case class TimeoutError(message: String) extends Exception(message)
