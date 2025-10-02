package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.TimeoutException

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** JVM-only runtime tests that require real concurrency. */
class EruRuntimeConcurrencySpec extends EruTestSuite {
  // Uses runtime from EruTestSuite

  test("zipPar runs both effects concurrently") {
    val start = System.nanoTime()
    val result = runtime
      .zipPar(
        runtime.sleep(Duration.ofMillis(50)).map(_ => "first"),
        runtime.sleep(Duration.ofMillis(50)).map(_ => "second")
      )
      .unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(result, ("first", "second"))
    // Should complete in roughly 50ms, not 100ms sequentially
    assert(elapsed < 90L, s"zipPar should be concurrent, took ${elapsed}ms")
  }

  test("race returns result of first completing effect") {
    val slow = runtime.sleep(Duration.ofMillis(100)).map(_ => "slow")
    val fast = runtime.sleep(Duration.ofMillis(10)).map(_ => "fast")

    val result = runtime.race(slow, fast).unsafeRunSync()

    result match {
      case Right("fast") => () // Expected - fast should win
      case other => fail(s"Expected Right('fast'), got: $other")
    }
  }

  test("race propagates error from winning effect") {
    val slow = runtime.sleep(Duration.ofMillis(100)).map(_ => "slow")
    val fastError = runtime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.fail("fast-error"))

    val result = runtime.race(slow, fastError).attempt.unsafeRunSync()

    result match {
      case Result.Failure("fast-error") => () // Expected
      case other => fail(s"Expected failure, got: $other")
    }
  }

  test("timeout fails with TimeoutException when effect is too slow") {
    val slowEffect = runtime.sleep(Duration.ofMillis(100)).map(_ => "too-slow")
    val timedOut = runtime.timeout(Duration.ofMillis(10))(slowEffect)

    val result = timedOut.attempt.unsafeRunSync()

    result match {
      case Result.Failure(_: TimeoutException) => () // Expected
      case other => fail(s"Expected TimeoutException, got: $other")
    }
  }

  test("suspend integrates with callback-based async operations") {
    var result = 0
    val async = runtime.suspend[Nothing, Int] { callback =>
      // Simulate async callback API
      val thread = new Thread(() => {
        Thread.sleep(10)
        callback(Right(42))
      })
      thread.start()
      Eru.unit // Return Eru[Nothing, Unit]
    }

    result = async.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("parSequence executes effects concurrently and preserves order") {
    val effects = List(
      runtime.sleep(Duration.ofMillis(30)).map(_ => 1),
      runtime.sleep(Duration.ofMillis(20)).map(_ => 2),
      runtime.sleep(Duration.ofMillis(10)).map(_ => 3)
    )

    val start = System.nanoTime()
    val result = runtime.parSequence(effects).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(result, List(1, 2, 3)) // Order preserved
    // Should complete in ~30ms (max), not 60ms (sum)
    assert(elapsed < 50L, s"parSequence should be concurrent, took ${elapsed}ms")
  }

  test("parTraverse applies function and executes concurrently") {
    val inputs = List(30, 20, 10)

    val start = System.nanoTime()
    val result = runtime
      .parTraverse(inputs) { ms =>
        runtime.sleep(Duration.ofMillis(ms)).map(_ => ms * 2)
      }
      .unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(result, List(60, 40, 20))
    // Should complete in ~30ms (max), not 60ms (sum)
    assert(elapsed < 50L, s"parTraverse should be concurrent, took ${elapsed}ms")
  }

  test("raceAll returns winner with correct index") {
    val effects = List(
      runtime.sleep(Duration.ofMillis(50)).map(_ => "slow"),
      runtime.sleep(Duration.ofMillis(10)).map(_ => "fast"),
      runtime.sleep(Duration.ofMillis(30)).map(_ => "medium")
    )

    val (result, index) = runtime.raceAll(effects).unsafeRunSync()

    assertEquals(result, "fast")
    assertEquals(index, 1) // fast is at index 1
  }
}
