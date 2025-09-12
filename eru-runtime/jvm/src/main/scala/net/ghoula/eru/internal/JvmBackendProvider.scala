package net.ghoula.eru.internal

/** ServiceLoader provider for the JVM runtime backend.
  *
  * Exposes the JVM-specific ConcurrencyBackend (virtual threads) to the shared selector without
  * requiring reflection. When present on the classpath, the shared PlatformBackend will discover
  * this provider and use its backend.
  *
  * This provider creates a singleton backend with its own fiber tracking queue, ensuring production
  * code has proper auto-join cleanup support while maintaining isolation from tests.
  */
private[eru] final class JvmBackendProvider extends BackendProvider {
  val backend: ConcurrencyBackend = RuntimeBackendAdapter.virtualThreads()
}
