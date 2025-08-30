package net.ghoula.eru.internal

/** Service-Loader-based provider for a platform-specific concurrency backend. Implementations can
  * be supplied per platform (e.g., JVM) and discovered at runtime without reflection or type
  * casting on the selector side.
  */
private[eru] trait BackendProvider {
  def backend: ConcurrencyBackend
}
