package net.ghoula.eru

import net.ghoula.eru.internal.{BackendFactory, BackendProvider, ConcurrencyBackend, SharedSynchronousBackend}

/** Selects the platform-specific concurrency backend using ServiceLoader.
  *
  * `createFreshBackend` supplies fresh backends for isolated runtimes; providers that also
  * implement `BackendFactory` supply a fresh instance, while others fall back to their singleton
  * `backend`.
  */
private[eru] object PlatformBackend {

  /** Discovers and returns the platform-specific backend provider. */
  private def discoverProvider(): BackendProvider = {
    val loader = java.util.ServiceLoader.load(classOf[BackendProvider], this.getClass.getClassLoader)
    val it = loader.iterator()
    if (it.hasNext) it.next() else DefaultSynchronousProvider
  }

  /** Creates a fresh backend for isolated runtime instances.
    *
    * Providers implementing `BackendFactory` create a fresh instance; others return their
    * singleton.
    */
  def createFreshBackend(): ConcurrencyBackend = {
    val provider = discoverProvider()
    provider match {
      case factory: BackendFactory => factory.createFresh()
      case _ => provider.backend
    }
  }

  /** Test-only accessor for the discovered provider. Production code reaches backends exclusively
    * through `createFreshBackend` (itself reached only via `EruRuntime.create`).
    */
  private[eru] def discoveredProviderForTests: BackendProvider = discoverProvider()

  /** Default synchronous provider for platforms without specific implementations. */
  private object DefaultSynchronousProvider extends BackendProvider {
    def backend: ConcurrencyBackend = SharedSynchronousBackend
  }
}
