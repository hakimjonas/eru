package net.ghoula.eru.internal

import java.time.Duration

import net.ghoula.eru.*

/** JVM-only scaffold for a Virtual Threads backend (not yet activated).
  *
  * This implementation currently delegates to the sequential backend to preserve existing
  * semantics. It will be expanded in subsequent steps to launch effects on Java Virtual Threads and
  * provide non-blocking timers.
  */
private[eru] final class VTOnlyBackend extends ConcurrencyBackend {
  private val delegate: ConcurrencyBackend = DefaultBackends.sequential

  // Capabilities will be updated when VT execution and non-blocking timers are implemented
  val capabilities: BackendCapabilities = BackendCapabilities(
    virtualThreads = false,
    structuredScopes = false,
    timersNonBlocking = false
  )

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
    delegate.fork(fa, observer)

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
