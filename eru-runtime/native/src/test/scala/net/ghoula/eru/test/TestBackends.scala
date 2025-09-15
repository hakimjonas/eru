package net.ghoula.eru.test

import net.ghoula.eru.internal.{ConcurrencyBackend, SharedSynchronousBackend}

/** Native-specific test backend creation for test isolation. */
private[eru] object TestBackends {

  /** Creates a fresh backend instance for test isolation.
    *
    * On Native, since execution is synchronous and SharedSynchronousBackend is stateless, we can
    * safely reuse the shared instance as there are no concurrency concerns.
    */
  def createFresh(): ConcurrencyBackend = SharedSynchronousBackend
}
