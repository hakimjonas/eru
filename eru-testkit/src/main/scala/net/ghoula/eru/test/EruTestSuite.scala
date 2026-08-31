package net.ghoula.eru.test

import net.ghoula.eru.EruRuntime

/** Base test suite class that cleans up the shared EruRuntime between test suites.
  *
  * Each test suite should extend this class so fibers forked against the shared runtime are
  * released after the suite completes.
  */
abstract class EruTestSuite extends munit.FunSuite {

  /** Implicit runtime for tests. */
  given runtime: EruRuntime = EruRuntime.shared

  /** Cleanup runtime after all tests in this suite complete.
    *
    * This ensures that any leftover fibers created during testing are properly awaited and cleaned
    * up before the next test suite runs, preventing hanging issues when multiple test suites
    * execute together.
    *
    * Cleanup exceptions are ignored so they cannot mask underlying test failures.
    */
  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception =>
        ()
    }
    super.afterAll()
  }
}
