package net.ghoula.eru.internal

import java.time.Duration

import net.ghoula.eru.*

/** Adapter that wraps the new RuntimeBackend enum to implement the old ConcurrencyBackend
  * interface.
  *
  * This allows gradual migration from the old backend system to the new unified RuntimeBackend
  * without breaking existing code. Once all code is migrated, this adapter can be removed.
  */
private[eru] final class RuntimeBackendAdapter(backend: RuntimeBackend) extends ConcurrencyBackend {

  val capabilities: BackendCapabilities = backend match {
    case RuntimeBackend.Synchronous =>
      new BackendCapabilities(
        virtualThreads = false,
        structuredScopes = false,
        timersNonBlocking = false
      )
    case RuntimeBackend.VirtualThreads =>
      new BackendCapabilities(
        virtualThreads = true,
        structuredScopes = false,
        timersNonBlocking = true
      )
  }

  def computeExit[E, A](fa: Eru[E, A], fiberId: FiberId): Exit[E, A] = {
    val _ = fiberId // Ignore the fiberId parameter for now
    backend match {
      case RuntimeBackend.Synchronous =>
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        // Execute finalizers directly - they are already in FILO order from executeWithFinalizers
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => () // Swallow finalizer errors
        }
        exit

      case RuntimeBackend.VirtualThreads =>
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        // Execute finalizers directly - they are already in FILO order from executeWithFinalizers
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => () // Swallow finalizer errors
        }
        exit
    }
  }

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, observer)

  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    backend.race(fa, fb)

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    backend.sleep(duration)

  def timeout[E, A](duration: Duration)(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
    backend.timeout(duration)(fa)

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import EruRuntime.Policy.*
    def delay(i: Int): Option[java.time.Duration] = policy match {
      case Recurs(n) => if (i < n) Some(java.time.Duration.ZERO) else None
      case Exponential(base, maxRet) => if (i < maxRet) Some(base.multipliedBy(1L << i)) else None
    }
    def loop(i: Int): Eru[E, A] =
      fa.recoverWith {
        case t: Throwable => Eru.fail(t)
        case e =>
          delay(i) match {
            case Some(d) => sleep(d).flatMap(_ => loop(i + 1))
            case None => Eru.fail(e)
          }
      }
    loop(0)
  }

  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] = {
    // For now, delegate to VTOnlyBackend-style implementation
    backend match {
      case RuntimeBackend.Synchronous =>
        // Simple sync implementation - not supported
        Eru.succeed(Left(new UnsupportedOperationException("Suspend not supported in synchronous mode")))

      case RuntimeBackend.VirtualThreads =>
        Eru.blocking {
          val future = new java.util.concurrent.CompletableFuture[Either[E | Throwable, A]]()

          val cb: Either[E, A] => Unit = result => {
            if (!future.isDone) {
              future.complete(result)
            }
          }

          java.util.concurrent.CompletableFuture.supplyAsync(
            () => {
              try {
                val registrationResult = register(cb).attempt.unsafeRunSync()
                registrationResult match {
                  case Result.Success(_) => ()
                  case Result.Failure(t) =>
                    if (!future.isDone) {
                      future.complete(Left(t))
                    }
                }
              } catch {
                case t: Throwable =>
                  if (!future.isDone) {
                    future.complete(Left(t))
                  }
              }
            },
            java.util.concurrent.ForkJoinPool.commonPool()
          )

          try {
            future.get()
          } catch {
            case _: InterruptedException =>
              Left(new InterruptedException("Suspend operation interrupted"))
            case ex: java.util.concurrent.ExecutionException =>
              val cause = Option(ex.getCause).getOrElse(ex)
              Left(cause)
            case t: Throwable =>
              Left(t)
          }
        }.attempt.map {
          case Result.Success(result) => result
          case Result.Failure(t) => Left(t)
        }
    }
  }

  override def cleanup(): Unit = {
    backend.cleanup()
  }
}

/** Factory for creating RuntimeBackend adapters. */
private[eru] object RuntimeBackendAdapter {

  /** Creates an adapter for the Virtual Threads backend. */
  def virtualThreads(): ConcurrencyBackend =
    new RuntimeBackendAdapter(RuntimeBackend.VirtualThreads)

  /** Creates an adapter for the Synchronous backend. */
  def synchronous(): ConcurrencyBackend =
    new RuntimeBackendAdapter(RuntimeBackend.Synchronous)
}
