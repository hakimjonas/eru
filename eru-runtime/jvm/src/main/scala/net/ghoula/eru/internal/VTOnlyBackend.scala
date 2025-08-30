package net.ghoula.eru.internal

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.*

/** JVM-only Virtual Threads backend (H9.2 fork/await only).
  *
  * zipPar, race, sleep, and timeout delegate to the sequential backend for now.
  */
private[eru] final class VTOnlyBackend extends ConcurrencyBackend {
  private val delegate: ConcurrencyBackend = DefaultBackends.sequential

  val capabilities: BackendCapabilities = BackendCapabilities(
    virtualThreads = true,
    structuredScopes = false,
    timersNonBlocking = false
  )

  private def computeExit[E, A](fa: Eru[E, A]): Exit[E, A] =
    try Result.toExit(fa.attempt.unsafeRunSync())
    catch { case t: Throwable => Exit.Die(t) }

  private final class VTFiber[E, A](val id: FiberId, exitRef: AtomicReference[Exit[E, A]], latch: CountDownLatch)
      extends Fiber[E, A] {
    def await: Eru[Nothing, Exit[E, A]] =
      Eru.effect {
        latch.await()
        exitRef.get()
      }.attempt.map {
        case Result.Success(x) => x
        case Result.Failure(_) => exitRef.get()
      }

    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit
  }

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    Eru.effect {
      val id = FiberId.fresh()
      val exitAR = new AtomicReference[Exit[E, A]]()
      val latch = new CountDownLatch(1)
      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
      val runnable: Runnable = () => {
        val exit = computeExit(fa)
        exitAR.set(exit)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        latch.countDown()
      }
      java.lang.Thread.startVirtualThread(runnable)
      new VTFiber[E, A](id, exitAR, latch)
    }.attempt.map {
      case Result.Success(fiber) => fiber
      case Result.Failure(t) =>
        val id = FiberId.fresh()
        val exit = Exit.Die(t)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))
        new VTFiber[E, A](id, new AtomicReference[Exit[E, A]](exit), new CountDownLatch(0))
    }

  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
    delegate.zipPar(fa, fb)

  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    delegate.race(fa, fb)

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    delegate.sleep(duration)

  def timeout[E, A](duration: Duration)(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
    delegate.timeout(duration)(fa)

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] =
    delegate.retry(policy)(fa)
}
