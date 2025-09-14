package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.test.TestBackends

/** Base trait for tests that need an EruRuntime instance.
  *
  * This trait provides an implicit EruRuntime for tests, ensuring proper isolation by creating a
  * fresh runtime with isolated backend for each individual test to prevent coordination primitive
  * interference and shared state issues that can cause test hangs and race conditions.
  */
trait TestWithRuntime extends FunSuite {
  private var _runtime: Option[EruRuntime] = None

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    // Create a fresh backend per test to avoid shared state
    val freshBackend = TestBackends.createFresh()
    _runtime = Some(EruRuntime.withBackend(freshBackend))
  }

  override def afterEach(context: AfterEach): Unit = {
    _runtime.foreach(_.cleanup())
    _runtime = None
    super.afterEach(context)
  }

  implicit def runtime: EruRuntime = {
    _runtime.getOrElse {
      throw new IllegalStateException("Runtime not initialized - test framework issue")
    }
  }
}
