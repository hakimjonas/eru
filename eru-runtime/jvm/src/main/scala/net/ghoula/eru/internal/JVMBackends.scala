package net.ghoula.eru.internal

/** JVM backends factory.
  *
  * Provides constructors for JVM-specific concurrency backends.
  *
  * Updated to use the new RuntimeBackend architecture via adapter for gradual migration.
  */
private[eru] object JVMBackends {

  /** Returns the Virtual Threads backend.
    * @return
    *   a ConcurrencyBackend implementation for JVM
    */
  def vtOnly: ConcurrencyBackend = RuntimeBackendAdapter.virtualThreads()

  /** Returns the legacy VTOnlyBackend - DEPRECATED.
    *
    * @deprecated
    *   This will be removed in a future version. The unified RuntimeBackend provides simpler,
    *   cleaner concurrency without the complex structured cleanup timing. Some edge case tests may
    *   fail during the migration period.
    */
  @deprecated("Use vtOnly (RuntimeBackend) instead", "next major version")
  def vtOnlyLegacy: ConcurrencyBackend = new VTOnlyBackend()
}
