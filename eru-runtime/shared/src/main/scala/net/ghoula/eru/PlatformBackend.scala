package net.ghoula.eru

import net.ghoula.eru.internal.{BackendProvider, ConcurrencyBackend, DefaultBackends}

/** Selects the platform-specific concurrency backend using ServiceLoader.
  *
  * If a provider is found on the classpath (e.g., the JVM provider), its backend is used. Otherwise
  * the sequential, portability-first backend is selected. This keeps the public API preview-free
  * and avoids reflection or type casting.
  */
private[eru] object PlatformBackend {
  private def discover(): ConcurrencyBackend = {
    val loader = java.util.ServiceLoader.load(classOf[BackendProvider], this.getClass.getClassLoader)
    val it = loader.iterator()
    if (it.hasNext) it.next().backend else DefaultBackends.sequential
  }
  val backend: ConcurrencyBackend = discover()
}
