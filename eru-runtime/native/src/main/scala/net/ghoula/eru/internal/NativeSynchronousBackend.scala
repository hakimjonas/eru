package net.ghoula.eru.internal

import net.ghoula.eru.*

/** Scala Native synchronous backend implementation.
  *
  * This backend provides synchronous-only execution semantics optimized for Scala Native's
  * single-threaded execution model. All operations execute immediately without creating threads or
  * using any concurrency primitives that might cause issues with Native's compilation model.
  *
  * Key characteristics:
  *   - Pure synchronous execution - no threads, no executors
  *   - Deterministic behavior - operations complete immediately
  *   - Zero reflection usage - Native-safe implementation
  *   - Compatible with Native's GC and memory model
  *   - Maintains Eru's semantic guarantees within synchronous constraints
  *
  * This backend is specifically designed to address Native compilation issues while providing a
  * complete Eru runtime experience for single-threaded Native applications.
  */
private[eru] object NativeSynchronousBackend extends ConcurrencyBackend {

  private val shared = SharedSynchronousBackend

  val capabilities: BackendCapabilities = new BackendCapabilities(
    virtualThreads = false,
    structuredScopes = false,
    timersNonBlocking = false
  )

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]] =
    shared.fork(fa, observer)

  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    shared.race(fa, fb)

  def sleep(duration: java.time.Duration): Eru[Nothing, Unit] =
    shared.sleep(duration)

  def timeout[E, A](duration: java.time.Duration)(
    fa: Eru[E, A]
  ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
    shared.timeout(duration)(fa)

  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] =
    shared.retry(policy)(fa)

  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    shared.handleSuspend(register)
}
