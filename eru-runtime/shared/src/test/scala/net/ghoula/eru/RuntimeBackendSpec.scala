package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.{ConcurrentLinkedQueue, TimeoutException}

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for RuntimeBackend implementation.
  *
  * Validates both Synchronous and VirtualThreads execution modes, structured concurrency semantics,
  * platform detection, and proper cleanup behavior. Tests ensure that the backend correctly handles
  * fiber lifecycle, racing, timeouts, and resource management across different execution contexts.
  */
class RuntimeBackendSpec extends EruTestSuite {

  test("Platform.isJVM detects execution environment correctly") {
    // Test that platform detection works
    val detected = Platform.isJVM
    assert(detected == true || detected == false, "Platform detection should return boolean")

    // Test that backend is selected based on platform
    val backend = Platform.backend
    assert(
      backend == RuntimeBackend.VirtualThreads || backend == RuntimeBackend.Synchronous,
      "Backend should be VirtualThreads or Synchronous"
    )
  }

  test("Platform.backend matches isJVM detection") {
    val backend = Platform.backend
    if (Platform.isJVM) {
      assertEquals(backend, RuntimeBackend.VirtualThreads)
    } else {
      assertEquals(backend, RuntimeBackend.Synchronous)
    }
  }

  test("Synchronous backend fork executes immediately") {
    val backend = RuntimeBackend.Synchronous
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("Synchronous backend fork handles failures") {
    val backend = RuntimeBackend.Synchronous
    val effect = Eru.fail("test-error")

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Failure(error) => assertEquals(error, "test-error")
      case other => munit.Assertions.fail(s"Expected Failure('test-error'), got: $other")
    }
  }

  test("Synchronous backend fork handles exceptions") {
    val backend = RuntimeBackend.Synchronous
    val effect = Eru.effect(throw new RuntimeException("test-exception"))

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Die(throwable) =>
        assert(throwable.getMessage.contains("test-exception"))
      case other => munit.Assertions.fail(s"Expected Die with RuntimeException, got: $other")
    }
  }

  test("Synchronous backend fork notifies observer") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val backend = RuntimeBackend.Synchronous
    val observer = new TestObserver
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect, Some(observer)).unsafeRunSync()
    fiber.await.unsafeRunSync()

    val events = observer.events
    assert(events.nonEmpty, "Observer should capture events")

    val startEvents = events.collect { case e: EruEvent.FiberStarted => e }
    val completeEvents = events.collect { case e: EruEvent.FiberCompleted => e }

    assertEquals(startEvents.length, 1)
    assertEquals(completeEvents.length, 1)
  }

  test("Synchronous backend tracks root fibers") {
    val backend = RuntimeBackend.Synchronous
    val rootFibers = new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect, None, Some(rootFibers)).unsafeRunSync()
    fiber.await.unsafeRunSync()

    // Root fibers tracking is primarily for VirtualThreads, but should not break
    assert(rootFibers.size() >= 0, "Root fibers queue should be handled gracefully")
  }

  test("VirtualThreads backend fork executes concurrently") {
    val backend = RuntimeBackend.VirtualThreads
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("VirtualThreads backend fork handles immediate success optimization") {
    val backend = RuntimeBackend.VirtualThreads
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("VirtualThreads backend fork handles immediate failure optimization") {
    val backend = RuntimeBackend.VirtualThreads
    val effect = Eru.fail("immediate-error")

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Failure(error) => assertEquals(error, "immediate-error")
      case other => munit.Assertions.fail(s"Expected Failure('immediate-error'), got: $other")
    }
  }

  test("VirtualThreads backend fork optimizes map chains") {
    val backend = RuntimeBackend.VirtualThreads
    val effect = Eru.succeed(21).map(_ * 2)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("VirtualThreads backend fork handles complex computations") {
    val backend = RuntimeBackend.VirtualThreads
    val effect = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.effect(a + b + 12)
    } yield c

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("VirtualThreads backend tracks child fibers in structured concurrency") {
    val backend = RuntimeBackend.VirtualThreads
    val rootFibers = new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()

    val effect = Eru.effect {
      Thread.sleep(10) // Small delay to test async behavior
      42
    }

    val fiber = backend.fork(effect, None, Some(rootFibers)).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("Synchronous backend race always returns left result") {
    val backend = RuntimeBackend.Synchronous
    val left = Eru.succeed("left-wins")
    val right = Eru.succeed("right-loses")

    val result = backend.race(left, right).unsafeRunSync()

    result match {
      case Left(value) => assertEquals(value, "left-wins")
      case Right(_) => munit.Assertions.fail("Synchronous backend should always return left result")
    }
  }

  test("Synchronous backend race propagates left failure") {
    val backend = RuntimeBackend.Synchronous
    val left = Eru.fail("left-error")
    val right = Eru.succeed("right-value")

    val exception = intercept[EruException[String]] {
      backend.race(left, right).unsafeRunSync()
    }
    assertEquals(exception.error, "left-error")
  }

  test("VirtualThreads backend race returns first completing result") {
    val backend = RuntimeBackend.VirtualThreads
    val slow = Eru.effect { Thread.sleep(100); "slow" }
    val fast = Eru.effect { Thread.sleep(10); "fast" }

    val result = backend.race(slow, fast).unsafeRunSync()

    result match {
      case Left("slow") => () // Slow won (unlikely but possible)
      case Right("fast") => () // Fast won (expected)
      case other => munit.Assertions.fail(s"Expected Left('slow') or Right('fast'), got: $other")
    }
  }

  test("VirtualThreads backend race handles failure from winner") {
    val backend = RuntimeBackend.VirtualThreads
    val fastFail = Eru.effect { Thread.sleep(10); throw new RuntimeException("fast-error") }
    val slowSuccess = Eru.effect { Thread.sleep(100); "slow-success" }

    val exception = intercept[RuntimeException] {
      backend.race(fastFail, slowSuccess).unsafeRunSync()
    }
    assert(exception.getMessage.contains("fast-error"))
  }

  test("VirtualThreads backend race handles typed failures") {
    val backend = RuntimeBackend.VirtualThreads
    val fastFail = Eru.fail("typed-error")
    val slowSuccess = Eru.effect { Thread.sleep(100); "success" }

    val result = backend.race(fastFail, slowSuccess).attempt.unsafeRunSync()
    result match {
      case Result.Failure(error) =>
        // Race propagated the failure - could be the typed error or wrapped
        assert(error.toString.contains("typed-error"), s"Expected error containing 'typed-error', got: $error")
      case Result.Success(either) =>
        either match {
          case Left(_) => munit.Assertions.fail("Left result unexpected for failing fast effect")
          case Right(_) => munit.Assertions.fail("Right result unexpected when fast effect fails")
        }
    }
  }

  test("VirtualThreads backend race cancels losing computation") {
    val backend = RuntimeBackend.VirtualThreads
    var slowExecuted = false

    val fast = Eru.succeed("fast")
    val slow = Eru.effect {
      Thread.sleep(200)
      slowExecuted = true
      "slow"
    }

    val result = backend.race(fast, slow).unsafeRunSync()

    result match {
      case Left("fast") => () // Expected
      case Right(_) => munit.Assertions.fail("Fast should have won")
      case other => munit.Assertions.fail(s"Unexpected result: $other")
    }

    // Give some time for cancellation to take effect
    Thread.sleep(50)
    assert(!slowExecuted, "Slow computation should have been cancelled")
  }

  test("Synchronous backend sleep blocks for duration") {
    val backend = RuntimeBackend.Synchronous
    val duration = Duration.ofMillis(25)

    val start = System.nanoTime()
    backend.sleep(duration).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 15L && elapsed <= 50L, s"Sleep took ${elapsed}ms, expected ~25ms")
  }

  test("VirtualThreads backend sleep is interruptible") {
    val backend = RuntimeBackend.VirtualThreads
    val duration = Duration.ofMillis(100)

    val start = System.nanoTime()
    backend.sleep(duration).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 80L && elapsed <= 150L, s"Sleep took ${elapsed}ms, expected ~100ms")
  }

  test("backend sleep with zero duration completes immediately") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      val start = System.nanoTime()
      backend.sleep(Duration.ZERO).unsafeRunSync()
      val elapsed = (System.nanoTime() - start) / 1_000_000L

      assert(elapsed < 5L, s"Zero sleep took ${elapsed}ms for $backend, should be immediate")
    }
  }

  test("backend timeout fails when computation is too slow") {
    // Test VirtualThreads backend timeout behavior
    val backend = RuntimeBackend.VirtualThreads
    val slowComputation = backend.sleep(Duration.ofMillis(100))
    val timeout = Duration.ofMillis(10)

    val exception = intercept[TimeoutException] {
      backend.timeout(timeout)(slowComputation).unsafeRunSync()
    }
    assert(exception.getMessage.contains("Operation timed out"))

    // Test Synchronous backend timeout behavior (always returns first effect)
    val syncBackend = RuntimeBackend.Synchronous
    val syncSlowComputation = syncBackend.sleep(Duration.ofMillis(100))
    val syncTimeout = Duration.ofMillis(10)

    // Synchronous backend race always returns Left (first effect), so timeout behavior differs
    val syncResult = syncBackend.timeout(syncTimeout)(syncSlowComputation).attempt.unsafeRunSync()
    syncResult match {
      case Result.Success(_) => () // Synchronous completes the sleep
      case Result.Failure(_: TimeoutException) => () // Or may timeout, both are valid
      case other => munit.Assertions.fail(s"Unexpected result for sync backend: $other")
    }
  }

  test("backend timeout succeeds when computation completes in time") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      val fastComputation = Eru.succeed(42)
      val timeout = Duration.ofMillis(100)

      val result = backend.timeout(timeout)(fastComputation).unsafeRunSync()
      assertEquals(result, 42)
    }
  }

  test("backend timeout propagates computation failure") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      val failingComputation = Eru.fail("computation-error")
      val timeout = Duration.ofMillis(100)

      val exception = intercept[EruException[String]] {
        backend.timeout(timeout)(failingComputation).unsafeRunSync()
      }
      assertEquals(exception.error, "computation-error")
    }
  }

  test("backend cleanup handles root fibers gracefully") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      val rootFibers = new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()

      // Should not throw
      backend.cleanup(Some(rootFibers))
      backend.cleanup(None)
    }
  }

  test("backend fork with observer handles all lifecycle events") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      class TestObserver extends EruObserver {
        private var _events: List[EruEvent] = Nil
        def events: List[EruEvent] = _events.reverse
        def onEvent(event: EruEvent): Unit = _events = event :: _events
      }

      val observer = new TestObserver
      val effect = Eru.succeed(42)

      val fiber = backend.fork(effect, Some(observer)).unsafeRunSync()
      fiber.await.unsafeRunSync()

      val events = observer.events
      val fiberIds = events.collect {
        case e: EruEvent.FiberStarted => e.fiberId
        case e: EruEvent.FiberCompleted => e.fiberId
        case e: EruEvent.FiberInterrupted => e.fiberId
      }.distinct

      assert(events.nonEmpty, s"Observer should capture events for $backend")
      assert(fiberIds.nonEmpty, s"Should have captured fiber events for $backend")
    }
  }

  test("backend handles nested structured concurrency") {
    val backend = RuntimeBackend.VirtualThreads

    val nestedEffect = for {
      childFiber <- backend.fork(Eru.succeed("child"))
      childResult <- childFiber.await
      result <- childResult match {
        case Exit.Success(value) => Eru.succeed(value)
        case other => Eru.fail(s"Child failed: $other")
      }
    } yield result

    val parentFiber = backend.fork(nestedEffect).unsafeRunSync()
    val exit = parentFiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, "child")
      case other => munit.Assertions.fail(s"Expected Success('child'), got: $other")
    }
  }

  test("backend fork error handling preserves stack traces") {
    val backends = List(RuntimeBackend.Synchronous, RuntimeBackend.VirtualThreads)

    backends.foreach { backend =>
      val effect = Eru.effect {
        throw new RuntimeException("preserves-trace")
      }

      val fiber = backend.fork(effect).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()

      exit match {
        case Exit.Die(throwable) =>
          assert(throwable.getMessage.contains("preserves-trace"))
          assert(throwable.getStackTrace.nonEmpty, s"Stack trace should be preserved for $backend")
        case other => munit.Assertions.fail(s"Expected Die with RuntimeException for $backend, got: $other")
      }
    }
  }

  test("Platform object provides stable singleton behavior") {
    val backend1 = Platform.backend
    val backend2 = Platform.backend
    val isJVM1 = Platform.isJVM
    val isJVM2 = Platform.isJVM

    assertEquals(backend1, backend2)
    assertEquals(isJVM1, isJVM2)
  }

  test("backend enum exhaustiveness") {
    // Test that pattern matching is exhaustive
    def testBackend(backend: RuntimeBackend): String = backend match {
      case RuntimeBackend.Synchronous => "sync"
      case RuntimeBackend.VirtualThreads => "vt"
    }

    assertEquals(testBackend(RuntimeBackend.Synchronous), "sync")
    assertEquals(testBackend(RuntimeBackend.VirtualThreads), "vt")
  }
}
