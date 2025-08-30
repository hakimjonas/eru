package net.ghoula.eru.internal

/** JVM backends factory.
  *
  * Provides constructors for JVM-specific concurrency backends.
  */
private[eru] object JVMBackends {

  /** Returns the Virtual Threads backend.
    * @return
    *   a ConcurrencyBackend implementation for JVM
    */
  def vtOnly: ConcurrencyBackend = new VTOnlyBackend()
}
