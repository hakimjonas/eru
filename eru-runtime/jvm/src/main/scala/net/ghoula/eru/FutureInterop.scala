package net.ghoula.eru

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/** JVM-only interoperability with scala.concurrent.Future.
  *
  * Provides safe, public-API-only conversions between Eru and Future without relying on internal
  * runtime details. Blocking is used when awaiting a Future, which is acceptable on JVM interop
  * boundaries and clearly marked via Eru.blocking.
  */
object FutureInterop {

  /** Creates an effect that evaluates the given Future-producing thunk and waits for its result.
    *
    * The Future is awaited in a blocking region. Failures are captured in the Throwable channel by
    * the Eru.effect semantics.
    *
    * @param thunk
    *   lazily produces the Future to await
    * @tparam A
    *   the success type
    * @return
    *   Eru[Throwable, A] that completes when the Future completes
    */
  def fromFuture[A](thunk: => Future[A]): Eru[Throwable, A] =
    Eru.blocking {
      val fut = thunk
      Await.result(fut, Duration.Inf)
    }

  /** Converts an Eru[Throwable, A] into a Future[A] by running the effect on the current thread
    * inside a Future. No internal runtime APIs are used.
    *
    * @param fa
    *   the effect to convert
    * @tparam A
    *   the success type
    * @return
    *   Eru[Nothing, Future[A]] which yields a Future that evaluates the effect
    */
  def toFuture[A](fa: Eru[Throwable, A]): Eru[Nothing, Future[A]] =
    Eru.effect {
      import scala.concurrent.ExecutionContext.Implicits.global
      Future(fa.unsafeRunSync())
    }.attempt.map {
      case Result.Success(fut) => fut
      case Result.Failure(t) => Future.failed(t)
    }
}
