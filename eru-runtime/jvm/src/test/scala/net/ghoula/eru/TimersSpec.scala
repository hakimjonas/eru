package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for JVM timer functionality in the Eru runtime system.
  *
  * Validates sleep operations, timeout behavior, and other time-based primitives available on the
  * JVM platform. These tests ensure that timer operations provide accurate timing, proper
  * non-blocking semantics, and integrate correctly with the fiber scheduling system while
  * maintaining high performance under concurrent load.
  */
final class TimersSpec extends EruTestSuite {

  test("sleep completes after duration (non-blocking semantics)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val sleepDuration = Duration.ofMillis(50)

      // Fork the sleep operation
      val fiber = runtime.fork(runtime.sleep(sleepDuration)).unsafeRunSync()

      // Advance test clock past the sleep duration
      runtime.testClock.advance(Duration.ofMillis(60))

      // Sleep should complete successfully
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(()) => assert(true)
        case other => fail(s"Expected successful sleep completion, got: $other")
      }
    }
  }

  test("timeout yields TimeoutException when duration elapses first") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      // Create an operation that will never complete on its own
      val longOperation = runtime.suspend[String, String] { _ =>
        // Don't invoke callback - let it timeout naturally
        Eru.unit
      }
      val timeoutDuration = Duration.ofMillis(50)

      // Fork the timeout operation
      val fiber = runtime.fork(runtime.timeout(timeoutDuration)(longOperation).attempt).unsafeRunSync()

      // Advance time past timeout
      runtime.testClock.advance(Duration.ofMillis(60))

      // Should get a timeout exception
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(Result.Failure(_: java.util.concurrent.TimeoutException)) =>
          assert(true) // Expected timeout
        case other =>
          fail(s"Expected TimeoutException, got: $other")
      }
    }
  }

  test("timeout passes through success when effect completes before deadline") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      // Create an operation that completes quickly
      val quickOperation = runtime.sleep(Duration.ofMillis(10)).map(_ => 42)
      val timeoutDuration = Duration.ofMillis(100)

      // Fork the timeout operation
      val fiber = runtime.fork(runtime.timeout(timeoutDuration)(quickOperation)).unsafeRunSync()

      // Advance time enough for the quick operation to complete but within timeout
      runtime.testClock.advance(Duration.ofMillis(15))

      // Should get the successful result
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(42) => assert(true)
        case other => fail(s"Expected success with value 42, got: $other")
      }
    }
  }
}
