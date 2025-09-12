package net.ghoula.eru

import munit.FunSuite

/** Base trait for tests that need an EruRuntime instance.
  *
  * This trait provides an implicit EruRuntime for tests, ensuring proper isolation by creating a
  * fresh runtime for each test suite.
  */
trait TestWithRuntime extends FunSuite {
  given runtime: EruRuntime = EruRuntime.create()

  override def afterAll(): Unit = {
    runtime.cleanup()
    super.afterAll()
  }
}
