package net.ghoula.eru.test

import net.ghoula.eru.EruRuntime

/** Base test suite class that properly cleans up EruRuntime between test suites.
  *
  * This prevents test hanging issues caused by leftover fibers in the rootFibers queue when
  * multiple test suites run together. Each test suite should extend this class to ensure proper
  * resource cleanup.
  *
  * This is the shared version that works across JVM and Native platforms.
  */
abstract class EruTestSuite extends munit.FunSuite {

  /** Implicit runtime for tests. */
  given runtime: EruRuntime = EruRuntime.shared

  /** Cleanup runtime after all tests in this suite complete.
    *
    * This ensures that any leftover fibers created during testing are properly awaited and cleaned
    * up before the next test suite runs, preventing hanging issues when multiple test suites
    * execute together.
    */
  override def afterAll(): Unit = {
    try {
      // Clean up any leftover fibers from this test suite
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception =>
        // Ignore cleanup exceptions to avoid masking test failures
        ()
    }
    super.afterAll()
  }
}
