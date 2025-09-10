package net.ghoula.eru

import munit.FunSuite

/** Base trait for tests that need an EruRuntime instance.
  *
  * This trait provides an implicit EruRuntime for tests, ensuring proper isolation by creating a
  * fresh runtime for each test suite.
  */
trait TestWithRuntime extends FunSuite {
  // Create a fresh runtime for this test suite
  given runtime: EruRuntime = EruRuntime.create()

  // Clean up after all tests
  override def afterAll(): Unit = {
    runtime.cleanup()
    super.afterAll()
  }
}
