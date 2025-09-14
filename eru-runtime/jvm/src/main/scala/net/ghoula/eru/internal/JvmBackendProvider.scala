package net.ghoula.eru.internal

/** ServiceLoader provider for the JVM runtime backend.
  *
  * Exposes the JVM-specific ConcurrencyBackend (virtual threads) to the shared selector without
  * requiring reflection. When present on the classpath, the shared PlatformBackend will discover
  * this provider and use its backend.
  *
  * Implements BackendFactory to support creating fresh isolated backends for each runtime instance.
  */
private[eru] final class JvmBackendProvider extends BackendFactory {
  // Singleton backend for shared runtime
  lazy val backend: ConcurrencyBackend = RuntimeBackendAdapter.virtualThreads()

  /** Creates a fresh backend for isolated runtime instances. */
  def createFresh(): ConcurrencyBackend = RuntimeBackendAdapter.virtualThreads()
}
