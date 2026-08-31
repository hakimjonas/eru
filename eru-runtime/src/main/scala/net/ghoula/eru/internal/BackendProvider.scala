package net.ghoula.eru.internal

/** Service-Loader-based provider for a concurrency backend. Implementations can be supplied per
  * environment and discovered at runtime via `java.util.ServiceLoader`.
  */
trait BackendProvider {
  def backend: ConcurrencyBackend
}

/** Extended provider interface that supports creating fresh backend instances for isolation. */
trait BackendFactory extends BackendProvider {

  /** Creates a fresh backend instance for runtime isolation. */
  def createFresh(): ConcurrencyBackend
}
