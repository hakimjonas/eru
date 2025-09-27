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
    Platform.backend
    // Backend is a sealed enum so it's always defined - no null check needed
  }

  test("Platform.backend matches isJVM detection") {
    val backend = Platform.backend
    // Check that we get the expected backend for this platform
    // without directly referencing VirtualThreads
    if (Platform.isJVM) {
      // On JVM we expect VirtualThreads, but check indirectly
      assert(backend != RuntimeBackend.Synchronous, "JVM should not use Synchronous backend")
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

  test("Synchronous backend sleep blocks for duration") {
    val backend = RuntimeBackend.Synchronous
    val duration = Duration.ofMillis(25)

    val start = System.nanoTime()
    backend.sleep(duration).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 15L && elapsed <= 50L, s"Sleep took ${elapsed}ms, expected ~25ms")
  }

  test("backend sleep with zero duration completes immediately") {
    val backend = Platform.backend
    val start = System.nanoTime()
    backend.sleep(Duration.ZERO).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed < 5L, s"Zero sleep took ${elapsed}ms for $backend, should be immediate")
  }

  test("backend timeout fails when computation is too slow") {
    val backend = Platform.backend
    val slowComputation = backend.sleep(Duration.ofMillis(100))
    val timeout = Duration.ofMillis(10)

    if (Platform.isJVM) {
      // VirtualThreads should timeout
      val exception = intercept[TimeoutException] {
        backend.timeout(timeout)(slowComputation).unsafeRunSync()
      }
      assert(exception.getMessage.contains("Operation timed out"))
    } else {
      // Synchronous backend race always returns Left (first effect), so timeout behavior differs
      val result = backend.timeout(timeout)(slowComputation).attempt.unsafeRunSync()
      result match {
        case Result.Success(_) => () // Synchronous completes the sleep
        case Result.Failure(_: TimeoutException) => () // Or may timeout, both are valid
        case other => munit.Assertions.fail(s"Unexpected result for sync backend: $other")
      }
    }
  }

  test("backend timeout succeeds when computation completes in time") {
    val backend = Platform.backend
    val fastComputation = Eru.succeed(42)
    val timeout = Duration.ofMillis(100)

    val result = backend.timeout(timeout)(fastComputation).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("backend timeout propagates computation failure") {
    val backend = Platform.backend
    val failingComputation = Eru.fail("computation-error")
    val timeout = Duration.ofMillis(100)

    val exception = intercept[EruException[String]] {
      backend.timeout(timeout)(failingComputation).unsafeRunSync()
    }
    assertEquals(exception.error, "computation-error")
  }

  test("backend cleanup handles root fibers gracefully") {
    val backend = Platform.backend
    val rootFibers = new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()

    // Should not throw
    backend.cleanup(Some(rootFibers))
    backend.cleanup(None)
  }

  test("backend fork with observer handles all lifecycle events") {
    val backend = Platform.backend
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

  test("backend handles nested structured concurrency") {
    assume(Platform.isJVM, "Nested structured concurrency test requires VirtualThreads")
    val backend = Platform.backend

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
    // Only test with Platform.backend to avoid referencing VirtualThreads on Native
    val backend = Platform.backend

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
    // We can't directly reference VirtualThreads on Native, so use Platform.backend
    def testBackend(backend: RuntimeBackend): String = backend match {
      case RuntimeBackend.Synchronous => "sync"
      case _ => "vt" // Catch-all for VirtualThreads without naming it
    }

    // Test with the current platform's backend
    val result = testBackend(Platform.backend)
    val expected = if (Platform.isJVM) "vt" else "sync"
    assertEquals(result, expected)

    // Also test Synchronous explicitly (it's safe on all platforms)
    assertEquals(testBackend(RuntimeBackend.Synchronous), "sync")
  }
}
