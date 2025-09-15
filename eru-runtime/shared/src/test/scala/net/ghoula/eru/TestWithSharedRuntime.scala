package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Base trait for tests that use the shared EruRuntime.
  *
  * This trait provides access to EruRuntime.shared for tests, avoiding resource contention and race
  * conditions that occur when each test creates its own isolated runtime with separate thread pools
  * and backends.
  *
  * Benefits over TestWithRuntime:
  *   - No resource exhaustion from multiple virtual thread executors
  *   - Consistent runtime behavior across all tests
  *   - Better performance with shared thread pool
  *   - Eliminates race conditions between isolated backends
  *
  * The shared runtime is safe to use across tests because:
  *   - It's immutable and thread-safe
  *   - No mutable global state that could interfere between tests
  *   - Coordination primitives (Ref, Promise, etc.) are properly isolated
  */
trait TestWithSharedRuntime extends FunSuite {

  /** Shared runtime instance available to all tests.
    *
    * Uses EruRuntime.shared which provides a single, well-managed runtime with proper resource
    * management and thread pool coordination.
    */
  implicit def runtime: EruRuntime = EruRuntime.shared
}
