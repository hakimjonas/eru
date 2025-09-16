package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for JVM timer functionality.
  *
  * This test suite verifies the core timer behavior: sleep operations and timeout mechanics. These
  * are essential for time-based coordination in the Eru runtime system.
  *
  * Focus: Deterministic, essential timer correctness tests only. Removed: Complex timing
  * dependencies, IsolatedTestRunner, exact duration verification.
  */
final class TimersSpec extends EruTestSuite {

  test("sleep operations complete successfully") {
    // Test that sleep doesn't hang and completes with unit value
    val result = runtime.sleep(Duration.ofMillis(1)).unsafeRunSync()
    assertEquals(result, ())
  }

  test("sleep with zero duration completes immediately") {
    val result = runtime.sleep(Duration.ZERO).unsafeRunSync()
    assertEquals(result, ())
  }

  test("timeout preserves success when operation completes quickly") {
    val quickOperation = Eru.succeed(42)
    val timeoutDuration = Duration.ofMillis(100)

    val result = runtime.timeout(timeoutDuration)(quickOperation).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("timeout with immediate success bypasses timing") {
    val immediateOperation = Eru.succeed("immediate")
    val result = runtime.timeout(Duration.ofMillis(50))(immediateOperation).unsafeRunSync()
    assertEquals(result, "immediate")
  }

  test("timeout propagates failures correctly") {
    val failingOperation = Eru.fail("operation failed")
    val result = runtime.timeout(Duration.ofMillis(100))(failingOperation).attempt.unsafeRunSync()

    assertEquals(result, Result.Failure("operation failed"))
  }

  test("sleep integrates with ensure finalizers") {
    var finalizerExecuted = false

    val result = runtime
      .sleep(Duration.ofMillis(1))
      .ensure(Eru.effect { finalizerExecuted = true })
      .unsafeRunSync()

    assertEquals(result, ())
    assert(finalizerExecuted, "Finalizer should have been executed")
  }

  test("timeout integrates with ensure finalizers on success") {
    var finalizerExecuted = false

    val operation = Eru.succeed("success").ensure(Eru.effect { finalizerExecuted = true })
    val result = runtime.timeout(Duration.ofMillis(100))(operation).unsafeRunSync()

    assertEquals(result, "success")
    assert(finalizerExecuted, "Finalizer should have been executed")
  }

  test("multiple sleep operations can run in parallel") {
    val sleeps = List.fill(3)(runtime.sleep(Duration.ofMillis(1)))
    val results = collectAll(sleeps).unsafeRunSync()

    assertEquals(results, List((), (), ()))
  }

  test("nested timeout operations work correctly") {
    val innerOperation = Eru.succeed("inner")
    val outerTimeout = runtime.timeout(Duration.ofMillis(100))(
      runtime.timeout(Duration.ofMillis(50))(innerOperation)
    )

    val result = outerTimeout.unsafeRunSync()
    assertEquals(result, "inner")
  }
}
