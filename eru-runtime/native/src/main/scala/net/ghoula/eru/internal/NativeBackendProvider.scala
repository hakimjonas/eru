package net.ghoula.eru.internal

/** ServiceLoader provider for the Scala Native runtime backend.
  *
  * Exposes the Native-specific synchronous ConcurrencyBackend to the shared selector without
  * requiring reflection. When present on the classpath during Native compilation, the shared
  * PlatformBackend will discover this provider and use its backend.
  *
  * The Native backend is purely synchronous and designed to work within Scala Native's
  * single-threaded execution model. Since it's stateless, the same instance can be safely reused
  * across multiple runtimes.
  */
private[eru] final class NativeBackendProvider extends BackendFactory {
  // Native backend is stateless, so sharing is safe
  val backend: ConcurrencyBackend = NativeSynchronousBackend

  /** Returns the same stateless backend for Native platform. */
  def createFresh(): ConcurrencyBackend = NativeSynchronousBackend
}
