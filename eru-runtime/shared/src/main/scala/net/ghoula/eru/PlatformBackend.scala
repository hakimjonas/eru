package net.ghoula.eru

import net.ghoula.eru.internal.{BackendProvider, ConcurrencyBackend, DefaultBackends}

private[eru] object PlatformBackend {
  private def discover(): ConcurrencyBackend = {
    val loader = java.util.ServiceLoader.load(classOf[BackendProvider], this.getClass.getClassLoader)
    val it = loader.iterator()
    if (it.hasNext) it.next().backend else DefaultBackends.sequential
  }
  val backend: ConcurrencyBackend = discover()
}
