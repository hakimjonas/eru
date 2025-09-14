package net.ghoula.eru.test

import net.ghoula.eru.internal.{ConcurrencyBackend, RuntimeBackendAdapter}

/** JVM-specific test backend creation for test isolation. */
private[eru] object TestBackends {

  /** Creates a fresh backend instance for test isolation.
    *
    * On JVM, this creates a new RuntimeBackendAdapter with its own thread pools and state,
    * preventing shared state issues between tests that caused hangs and race conditions.
    */
  def createFresh(): ConcurrencyBackend = RuntimeBackendAdapter.virtualThreads()
}
