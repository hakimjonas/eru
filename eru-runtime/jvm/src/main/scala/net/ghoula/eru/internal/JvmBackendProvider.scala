package net.ghoula.eru.internal

private[eru] final class JvmBackendProvider extends BackendProvider {
  val backend: ConcurrencyBackend = JVMBackends.vtOnly
}
