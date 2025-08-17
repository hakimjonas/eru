package net.ghoula.eru

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** JVM-only Future interoperability for seamless integration with existing Scala codebases.
  *
  * This object provides bidirectional conversion between `scala.concurrent.Future` and `Eru`
  * effects, enabling smooth migration and integration patterns.
  */
object FutureInterop {

  /** Creates an `Eru` effect that suspends and awaits the completion of a `Future`.
    *
    * The provided thunk is evaluated lazily when the `Eru` effect is run. On success, the
    * `Future`'s value is yielded. On failure, the `Throwable` is propagated as a typed failure.
    *
    * Cancellation behavior: If the `Eru` fiber is interrupted, this implementation will attempt
    * to cancel the `Future` if it supports cancellation (though most `Future` implementations
    * do not support true cancellation).
    *
    * @param thunk
    *   a by-name parameter that produces the `Future` to await
    * @tparam A
    *   the success type of the `Future`
    * @return
    *   an `Eru[Throwable, A]` that completes when the `Future` completes
    */
  def fromFuture[A](thunk: => Future[A]): Eru[Throwable, A] =
    Eru.suspend[Throwable, A] { resume =>
      Eru.effect {
        val future = thunk
        future.onComplete {
          case Success(value) => resume(Right(value))
          case Failure(throwable) => resume(Left(throwable))
        }(scala.concurrent.ExecutionContext.global)
      }
    }

  /** Converts an `Eru` effect into a `Future` by forking it on the Eru runtime.
    *
    * The resulting `Future` will be completed with the `Exit` value of the forked fiber:
    *   - `Exit.Success(a)` becomes a successful `Future` containing `a`
    *   - `Exit.Failure(e: Throwable)` becomes a failed `Future` with the `Throwable`
    *   - `Exit.Failure(e)` (non-Throwable) becomes a failed `Future` with `EruException(e)`
    *   - `Exit.Die(t)` becomes a failed `Future` with the `Throwable`
    *   - `Exit.Interrupt` becomes a failed `Future` with `InterruptedException`
    *
    * @param fa
    *   the `Eru` effect to convert
    * @tparam A
    *   the success type of the `Eru` effect (must be compatible with Throwable error channel)
    * @return
    *   an `Eru[Nothing, Future[A]]` that yields the `Future` immediately
    */
  def toFuture[A](fa: Eru[Throwable, A]): Eru[Nothing, Future[A]] =
    Eru.effect {
      val promise = Promise[A]()
      
      EruRuntime.fork(fa).flatMap(_.await).unsafeRunSync() match {
        case Exit.Success(value) => 
          promise.success(value)
        case Exit.Failure(throwable: Throwable) => 
          promise.failure(throwable)
        case Exit.Failure(error) => 
          promise.failure(EruException(error))
        case Exit.Die(throwable) => 
          promise.failure(throwable)
        case Exit.Interrupt(_, _) => 
          promise.failure(new InterruptedException("Eru fiber was interrupted"))
      }
      
      promise.future
    }.attempt.map {
      case Result.Success(future) => future
      case Result.Failure(throwable) => Future.failed(throwable)
    }
}