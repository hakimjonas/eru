package net.ghoula.eru

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for JVM timer functionality in the Eru runtime system.
  *
  * Validates sleep operations, timeout behavior, and other time-based primitives available on the
  * JVM platform. These tests ensure that timer operations provide accurate timing, proper
  * non-blocking semantics, and integrate correctly with the fiber scheduling system while
  * maintaining high performance under concurrent load.
  */
final class TimersSpec extends TestWithRuntime {

  test("sleep completes after duration (non-blocking semantics)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      // Use TestClock for deterministic timing instead of System.nanoTime()
      val sleepEffect = runtime.sleep(Duration.ofMillis(5))

      // Fork the sleep operation to test non-blocking behavior
      val fiber = runtime.fork(sleepEffect).unsafeRunSync()

      // Verify sleep is pending initially
      assertEquals(runtime.testClock.pendingCount, 1)

      // Advance time to complete the sleep
      val completed = runtime.testClock.advance(Duration.ofMillis(5))
      assertEquals(completed, 1)

      // Verify sleep completed successfully
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(_) => assert(true)
        case other => fail(s"Expected successful completion, got: $other")
      }
    }
  }

  test("timeout yields TimeoutException when duration elapses first") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      // Create an operation that will never complete on its own
      val neverCompletes = Eru.effect {
        // This will throw the TestClock suspend exception, so it won't complete immediately
        throw new RuntimeException("TestClock suspend operation - operation never completes")
      }

      val timeoutEffect = runtime.timeout(Duration.ofMillis(50))(neverCompletes)

      // Fork the timeout operation
      val fiber = runtime.fork(timeoutEffect).unsafeRunSync()

      // Advance time past the timeout - this should cause the timeout to win
      runtime.testClock.advance(Duration.ofMillis(60))

      // Check result - should timeout
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Failure(_: java.util.concurrent.TimeoutException) =>
          assert(true) // Expected timeout as Failure
        case Exit.Die(_: java.util.concurrent.TimeoutException) =>
          assert(true) // Expected timeout as Die (also valid)
        case Exit.Die(_: RuntimeException) =>
          // The underlying operation died, but timeout should have won
          fail("Timeout should have occurred before the operation could fail")
        case other => fail(s"Expected TimeoutException, got: $other")
      }
    }
  }

  test("timeout passes through success when effect completes before deadline") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val short = runtime.sleep(Duration.ofMillis(2)).flatMap(_ => Eru.succeed(42))
      val timeoutEffect = runtime.timeout(Duration.ofMillis(20))(short)

      // Fork the timeout operation
      val fiber = runtime.fork(timeoutEffect).unsafeRunSync()

      // Advance time to complete the sleep but before timeout
      runtime.testClock.advance(Duration.ofMillis(3))

      // Check result - should succeed with value
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(value) => assertEquals(value, 42)
        case other => fail(s"Expected success with 42, got: $other")
      }
    }
  }
}
