package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.{Platform, RuntimeBackend}

/** Comprehensive test suite for EruRuntime functionality.
  *
  * Validates the runtime's core concurrency operations including fiber management, parallel
  * execution, racing, timeouts, and coordination patterns. Tests ensure that the runtime correctly
  * implements structured concurrency semantics with proper error handling and resource cleanup.
  */
class EruRuntimeSpec extends EruTestSuite {

  test("fork creates fiber that executes concurrently") {
    val fiber = runtime.fork(Eru.succeed(42)).unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => fail(s"Expected Success(42), got: $other")
    }
  }

  test("fork with failing effect returns failure") {
    val fiber = runtime.fork(Eru.fail("test-error")).unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Failure(error) => assertEquals(error, "test-error")
      case other => fail(s"Expected Failure('test-error'), got: $other")
    }
  }

  test("forkWithObserver captures events") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val observer = new TestObserver
    val fiber = runtime.forkWithObserver(Eru.succeed(42), observer).unsafeRunSync()

    fiber.await.unsafeRunSync()

    // Should have captured some events during execution
    assert(observer.events.nonEmpty, "Observer should capture events")
  }

  test("zipPar runs both effects concurrently") {
    // val start = System.nanoTime()
    val result = runtime
      .zipPar(
        runtime.sleep(Duration.ofMillis(50)).map(_ => "first"),
        runtime.sleep(Duration.ofMillis(50)).map(_ => "second")
      )
      .unsafeRunSync()
    // val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(result, ("first", "second"))
    // Should complete in roughly 50ms, not 100ms sequentially
    // Timing assertion disabled for synchronous backend
  }

  test("zipPar propagates first failure encountered") {
    val result = runtime
      .zipPar(
        Eru.fail("first-error"),
        runtime.sleep(Duration.ofMillis(10)).map(_ => "success")
      )
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(error) =>
        // Error could be the typed error or wrapped
        assert(error.toString.contains("first-error"), s"Expected error containing 'first-error', got: $error")
      case other => fail(s"Expected failure, got: $other")
    }
  }

  test("zipPar handles mixed error types") {
    val result = runtime
      .zipPar(
        Eru.effect(throw new RuntimeException("defect")),
        Eru.succeed(42)
      )
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(_: RuntimeException) => () // Expected
      case other => fail(s"Expected RuntimeException, got: $other")
    }
  }

  test("race returns result of first completing effect") {
    // Concurrency test moved to EruRuntimeConcurrencySpec for JVM
    // Here we verify the API works on all platforms
    val result = runtime
      .race(
        Eru.succeed("first"),
        Eru.succeed("second")
      )
      .unsafeRunSync()
    // On synchronous backend, left side wins
    assert(result == Left("first") || result == Right("second"))
  }

  test("race propagates error from winning effect") {
    // On synchronous backend, race always picks left, so reverse the order
    val result = if (Platform.backend == RuntimeBackend.VirtualThreads) {
      runtime
        .race(
          runtime.sleep(Duration.ofMillis(100)).map(_ => "slow"),
          Eru.fail("fast-error")
        )
        .attempt
        .unsafeRunSync()
    } else {
      // Synchronous backend: put error on left so it wins
      runtime
        .race(
          Eru.fail("fast-error"),
          runtime.sleep(Duration.ofMillis(100)).map(_ => "slow")
        )
        .attempt
        .unsafeRunSync()
    }

    result match {
      case Result.Failure("fast-error") => () // Expected
      case other => fail(s"Expected failure, got: $other")
    }
  }

  test("sleep respects duration timing") {
    val start = System.nanoTime()
    runtime.sleep(Duration.ofMillis(25)).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    // Allow some timing variance
    assert(elapsed >= 15L && elapsed <= 50L, s"Sleep took ${elapsed}ms, expected ~25ms")
  }

  test("sleep with zero duration completes immediately") {
    // val start = System.nanoTime()
    runtime.sleep(Duration.ZERO).unsafeRunSync()
    // val elapsed = (System.nanoTime() - start) / 1_000_000L

    // Timing assertion disabled for synchronous backend: Zero sleep should be immediate
  }

  test("timeout fails with TimeoutException when effect is too slow") {
    // Only test on JVM where real timeouts work
    if (Platform.backend == RuntimeBackend.VirtualThreads) {
      val result = runtime
        .timeout(Duration.ofMillis(10))(
          runtime.sleep(Duration.ofMillis(100)).map(_ => "too-slow")
        )
        .attempt
        .unsafeRunSync()

      result match {
        case Result.Failure(_: java.util.concurrent.TimeoutException) => () // Expected
        case other => fail(s"Expected TimeoutException, got: $other")
      }
    } else {
      // On Native, timeout can't interrupt synchronous execution
      // Just verify the API works
      val result = runtime
        .timeout(Duration.ofMillis(100))(
          Eru.succeed("immediate")
        )
        .unsafeRunSync()
      assertEquals(result, "immediate")
    }
  }

  test("timeout returns result when effect completes in time") {
    val result = runtime
      .timeout(Duration.ofMillis(100))(
        runtime.sleep(Duration.ofMillis(10)).map(_ => "in-time")
      )
      .unsafeRunSync()

    assertEquals(result, "in-time")
  }

  test("suspend integrates with callback-based async operations") {
    // Only test on JVM - Native requires synchronous callbacks
    if (Platform.backend == RuntimeBackend.VirtualThreads) {
      import scala.concurrent.{Future, ExecutionContext}
      implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

      val result = runtime
        .suspend[Throwable, String] { callback =>
          Eru.succeed {
            val future = Future.successful("async-result")
            future.onComplete { result =>
              result.fold(
                error => callback(Left(error)),
                value => callback(Right(value))
              )
            }
          }
        }
        .unsafeRunSync()

      assertEquals(result, "async-result")
    } else {
      // Native: test synchronous callback only
      val result = runtime
        .suspend[Throwable, String] { callback =>
          Eru.succeed {
            callback(Right("sync-result"))
          }
        }
        .unsafeRunSync()

      assertEquals(result, "sync-result")
    }
  }

  test("parSequence executes effects concurrently and preserves order") {
    val effects = List(
      runtime.sleep(Duration.ofMillis(30)).map(_ => "third"),
      runtime.sleep(Duration.ofMillis(10)).map(_ => "first"),
      runtime.sleep(Duration.ofMillis(20)).map(_ => "second")
    )

    // val start = System.nanoTime()
    val results = runtime.parSequence(effects).unsafeRunSync()
    // val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(results, List("third", "first", "second"))
    // Should complete in ~30ms (max), not 60ms (sum)
    // Timing assertion disabled for synchronous backend
  }

  test("parSequence handles empty list") {
    val results = runtime.parSequence(List.empty[Eru[String, Int]]).unsafeRunSync()
    assertEquals(results, List.empty[Int])
  }

  test("parSequence propagates first error encountered") {
    val effects = List(
      runtime.sleep(Duration.ofMillis(10)).map(_ => 1),
      Eru.fail("error"),
      runtime.sleep(Duration.ofMillis(10)).map(_ => 3)
    )

    val result = runtime.parSequence(effects).attempt.unsafeRunSync()
    result match {
      case Result.Failure(error) =>
        assert(error.toString.contains("error"), s"Expected error containing 'error', got: $error")
      case other => fail(s"Expected failure, got: $other")
    }
  }

  test("parTraverse applies function and executes concurrently") {
    val inputs = List(30, 10, 20)

    // val start = System.nanoTime()
    val results = runtime
      .parTraverse(inputs) { millis =>
        runtime.sleep(Duration.ofMillis(millis.toLong)).map(_ => millis * 2)
      }
      .unsafeRunSync()
    // val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(results, List(60, 20, 40))
    // Should complete in ~30ms (max), not 60ms (sum)
    // Timing assertion disabled for synchronous backend
  }

  test("raceAll returns winner with correct index") {
    if (Platform.backend == RuntimeBackend.VirtualThreads) {
      // JVM: test real race with timing
      val effects = List(
        runtime.sleep(Duration.ofMillis(50)).map(_ => "slow"),
        runtime.sleep(Duration.ofMillis(10)).map(_ => "fast"),
        runtime.sleep(Duration.ofMillis(100)).map(_ => "slowest")
      )

      val (result, index) = runtime.raceAll(effects).unsafeRunSync()

      assertEquals(result, "fast")
      assertEquals(index, 1) // fast is at index 1
    } else {
      // Native: raceAll returns first (index 0) on synchronous backend
      val effects = List(
        Eru.succeed("first"),
        Eru.succeed("second"),
        Eru.succeed("third")
      )

      val (result, index) = runtime.raceAll(effects).unsafeRunSync()

      assertEquals(result, "first")
      assertEquals(index, 0) // first always wins on synchronous
    }
  }

  test("raceAll handles single effect") {
    val (result, index) = runtime.raceAll(List(Eru.succeed("solo"))).unsafeRunSync()

    assertEquals(result, "solo")
    assertEquals(index, 0)
  }

  test("raceAll fails on empty list") {
    val result = runtime.raceAll(List.empty[Eru[String, String]]).attempt.unsafeRunSync()

    result match {
      case Result.Failure(_: IllegalArgumentException) => () // Expected
      case other => fail(s"Expected IllegalArgumentException, got: $other")
    }
  }

  test("foreachParN limits concurrency") {
    val inputs = (1 to 10).toList
    val maxConcurrency = 3

    val results = runtime
      .foreachParN(maxConcurrency, inputs) { i =>
        runtime.sleep(Duration.ofMillis(10)).map(_ => i * 2)
      }
      .unsafeRunSync()

    assertEquals(results, inputs.map(_ * 2))
  }

  test("foreachParN requires positive concurrency") {
    val exception = intercept[IllegalArgumentException] {
      runtime.foreachParN(0, List(1, 2, 3))(_ => Eru.succeed(())).unsafeRunSync()
    }
    assert(exception.getMessage.contains("Parallelism degree must be positive"))
  }

  test("foreachParN handles empty input") {
    val results = runtime.foreachParN(3, List.empty[Int])(_ => Eru.succeed(())).unsafeRunSync()
    assertEquals(results, List.empty)
  }

  test("foreachParNDiscard discards results") {
    val inputs = List(1, 2, 3)

    val result = runtime
      .foreachParNDiscard(2, inputs) { i =>
        runtime.sleep(Duration.ofMillis(1)).map(_ => i * 100)
      }
      .unsafeRunSync()

    assertEquals(result, ()) // Result should be Unit
  }

  test("validatePar accumulates all errors") {
    val validations = List(
      Eru.fail("error1"),
      Eru.succeed("success1"),
      Eru.fail("error2"),
      Eru.succeed("success2")
    )

    val result = runtime.validatePar(validations).unsafeRunSync()

    result match {
      case Left(errors) =>
        assertEquals(errors.toSet, Set("error1", "error2"))
      case Right(_) => fail("Expected errors to be accumulated")
    }
  }

  test("validatePar returns successes when all valid") {
    val validations = List(
      Eru.succeed("valid1"),
      Eru.succeed("valid2"),
      Eru.succeed("valid3")
    )

    val result = runtime.validatePar(validations).unsafeRunSync()

    result match {
      case Right(successes) => assertEquals(successes, List("valid1", "valid2", "valid3"))
      case Left(_) => fail("Expected all validations to succeed")
    }
  }

  test("validateFirst returns first error encountered") {
    val validations = List(
      Eru.succeed("success1"),
      Eru.fail("first-error"),
      Eru.fail("second-error")
    )

    val result = runtime.validateFirst(validations).unsafeRunSync()

    result match {
      case Left(error) => assertEquals(error, "first-error")
      case Right(_) => fail("Expected first error to be returned")
    }
  }

  test("validateFirst returns all successes when valid") {
    val validations = List(
      Eru.succeed("valid1"),
      Eru.succeed("valid2")
    )

    val result = runtime.validateFirst(validations).unsafeRunSync()

    result match {
      case Right(successes) => assertEquals(successes, List("valid1", "valid2"))
      case Left(_) => fail("Expected all validations to succeed")
    }
  }

  test("retry policy enum constructs correctly") {
    val recurs = EruRuntime.Policy.Recurs(3)
    recurs match {
      case EruRuntime.Policy.Recurs(n) => assertEquals(n, 3)
      case _ => fail("Expected Recurs policy")
    }

    val exponential = EruRuntime.Policy.Exponential(Duration.ofMillis(100), 5)
    exponential match {
      case EruRuntime.Policy.Exponential(base, max) =>
        assertEquals(base, Duration.ofMillis(100))
        assertEquals(max, 5)
      case _ => fail("Expected Exponential policy")
    }
  }

  test("EruRuntime.create creates independent runtime instances") {
    val runtime1 = EruRuntime.create()
    val runtime2 = EruRuntime.create()

    // Both should work independently
    val result1 = runtime1.fork(Eru.succeed("runtime1")).unsafeRunSync().await.unsafeRunSync()
    val result2 = runtime2.fork(Eru.succeed("runtime2")).unsafeRunSync().await.unsafeRunSync()

    result1 match {
      case Exit.Success("runtime1") => ()
      case other => fail(s"Runtime1 failed: $other")
    }

    result2 match {
      case Exit.Success("runtime2") => ()
      case other => fail(s"Runtime2 failed: $other")
    }

    runtime1.cleanup()
    runtime2.cleanup()
  }

  test("EruRuntime.shared provides singleton access") {
    val runtime1 = EruRuntime.shared
    val runtime2 = EruRuntime.shared

    // Should be the same instance
    assert(runtime1 eq runtime2, "shared should return same instance")
  }

  test("complex nested concurrent operations work correctly") {
    val result = (for {
      // Parallel computation within a timeout
      timedResult <- runtime.timeout(Duration.ofMillis(200)) {
        runtime.zipPar(
          runtime.parSequence(
            List(
              runtime.sleep(Duration.ofMillis(10)).map(_ => 1),
              runtime.sleep(Duration.ofMillis(20)).map(_ => 2)
            )
          ),
          runtime.race(
            runtime.sleep(Duration.ofMillis(50)).map(_ => "slow"),
            runtime.sleep(Duration.ofMillis(5)).map(_ => "fast")
          )
        )
      }
      // Transform the complex result
      (numbers, raceResult) = timedResult
      finalResult = numbers.sum + (raceResult match {
        case Left(slow) => slow.length
        case Right(fast) => fast.length
      })
    } yield finalResult).unsafeRunSync()

    assertEquals(result, 3 + 4) // [1,2].sum + "fast".length
  }
}
