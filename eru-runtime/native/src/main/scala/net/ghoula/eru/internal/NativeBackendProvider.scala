package net.ghoula.eru.internal

/** ServiceLoader provider for the Scala Native runtime backend.
  *
  * Exposes the Native-specific synchronous ConcurrencyBackend to the shared selector without
  * requiring reflection. When present on the classpath during Native compilation, the shared
  * PlatformBackend will discover this provider and use its backend.
  *
  * The Native backend is purely synchronous and designed to work within Scala Native's
  * single-threaded execution model and compilation constraints.
  */
private[eru] final class NativeBackendProvider extends BackendProvider {
  val backend: ConcurrencyBackend = NativeSynchronousBackend
}
