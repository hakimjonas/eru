package net.ghoula.eru

import net.ghoula.eru.internal.{BackendFactory, BackendProvider, ConcurrencyBackend, SharedSynchronousBackend}

/** Selects the platform-specific concurrency backend using ServiceLoader.
  *
  * Provides both singleton backend (for shared runtime) and fresh backend creation (for isolated
  * runtimes). This keeps the public API preview-free and avoids reflection or type casting.
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
    * This delegates to platform-specific factory methods to avoid casting.
    */
  def createFreshBackend(): ConcurrencyBackend = {
    val provider = discoverProvider()
    provider match {
      case factory: BackendFactory => factory.createFresh()
      case _ => provider.backend // Fallback for providers without factory support
    }
  }

  /** Singleton backend for shared runtime - use sparingly! */
  lazy val backend: ConcurrencyBackend = discoverProvider().backend

  /** Default synchronous provider for platforms without specific implementations. */
  private object DefaultSynchronousProvider extends BackendProvider {
    def backend: ConcurrencyBackend = SharedSynchronousBackend
  }
}
