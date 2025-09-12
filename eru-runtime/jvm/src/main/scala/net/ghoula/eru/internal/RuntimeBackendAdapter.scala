package net.ghoula.eru.internal

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Internal adapter providing legacy ConcurrencyBackend interface compatibility.
  *
  * This adapter bridges the unified RuntimeBackend implementation with the legacy interface,
  * maintaining backward compatibility while providing modern structured concurrency features.
  *
  * Each adapter instance maintains its own root fiber collection for proper test isolation.
  */
private[eru] final class RuntimeBackendAdapter(backend: RuntimeBackend) extends ConcurrencyBackend {

  private val rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] = new ConcurrentLinkedQueue()

  // Lazy instance-local executor to avoid shared thread pool contention between multiple Eru applications
  private lazy val privateExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

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
    val _ = fiberId
    backend match {
      case RuntimeBackend.Synchronous =>
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => ()
        }
        exit

      case RuntimeBackend.VirtualThreads =>
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        finalizers.foreach { finalizer =>
          try finalizer().unsafeRunSync()
          catch case _: Exception => ()
        }
        exit
    }
  }

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    backend.fork(fa, observer, Some(rootFibers))

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
    backend match {
      case RuntimeBackend.Synchronous =>
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
            privateExecutor
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
    backend.cleanup(Some(rootFibers))
    // Note: Don't eagerly close privateExecutor as it may still have pending tasks
    // The lazy executor will be cleaned up by GC when the adapter is collected
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
