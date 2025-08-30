package net.ghoula.eru.internal

/** JVM backends factory.
  *
  * Provides constructors for JVM-specific concurrency backends. The default
  * implementation currently returns a sequential backend; a Virtual Threads
  * backend will be wired here in subsequent steps.
  */
private[eru] object JVMBackends {
  /** Returns the Virtual Threads backend (currently delegated to sequential semantics).
    * @return a ConcurrencyBackend implementation for JVM
    */
  def vtOnly: ConcurrencyBackend = DefaultBackends.sequential
}
