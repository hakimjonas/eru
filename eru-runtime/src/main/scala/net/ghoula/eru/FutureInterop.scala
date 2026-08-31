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

  /** Converts an `Eru[Throwable, A]` into an already-completed `Future[A]`.
    *
    * The effect is evaluated eagerly (not on a separate thread); the returned `Future` carries the
    * completed outcome. For asynchronous interop, evaluate the effect in a forked fiber first, then
    * convert the resulting exit.
    *
    * @param fa
    *   the effect to convert
    * @tparam A
    *   the success type
    * @return
    *   an effect yielding a completed Future of the effect's outcome
    */
  def toFuture[A](fa: Eru[Throwable, A]): Eru[Nothing, Future[A]] =
    fa.attempt.map {
      case Result.Success(value) => Future.successful(value)
      case Result.Failure(error) => Future.failed(error)
    }
}
