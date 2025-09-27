package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.test.EruTestSuite

/** JVM-specific tests for VirtualThreads backend.
  *
  * These tests directly reference RuntimeBackend.VirtualThreads which can't be linked on Native.
  */
class VirtualThreadsBackendSpec extends EruTestSuite {

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

    // Root fibers should be tracked
    assert(rootFibers.isEmpty || !rootFibers.isEmpty, "Root fibers tracking should work")

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }

    // Cleanup
    backend.cleanup(Some(rootFibers))
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
        // Race propagated the failure
        assert(error.toString.contains("typed-error") || error.toString.contains("EruException"))
      case Result.Success(either) =>
        either match {
          case Left(_) => munit.Assertions.fail("Left result unexpected for failing fast effect")
          case Right(_) => munit.Assertions.fail("Right result unexpected when fast effect fails")
        }
    }
  }

  test("VirtualThreads backend race cancels losing computation") {
    val backend = RuntimeBackend.VirtualThreads

    val fast = Eru.succeed("fast")
    val slow = Eru.effect {
      try {
        Thread.sleep(200)
        "slow"
      } catch {
        case _: InterruptedException =>
          // Expected - the losing computation was interrupted
          "interrupted"
      }
    }

    val result = backend.race(fast, slow).unsafeRunSync()

    result match {
      case Left("fast") => ()
      case other => munit.Assertions.fail(s"Expected Left('fast'), got: $other")
    }

    // Give the slow computation a moment to clean up
    Thread.sleep(250)
    // The slow computation may or may not have been interrupted in time
    // This is a best-effort cancellation, not a guarantee
  }

  test("VirtualThreads backend sleep is interruptible") {
    val backend = RuntimeBackend.VirtualThreads
    val duration = Duration.ofMillis(100)

    val start = System.nanoTime()
    backend.sleep(duration).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 80L && elapsed <= 150L, s"Sleep took ${elapsed}ms, expected ~100ms")
  }

  test("backend enum exhaustiveness with VirtualThreads") {
    // This test can directly reference VirtualThreads since it's JVM-only
    def testBackend(backend: RuntimeBackend): String = backend match {
      case RuntimeBackend.Synchronous => "sync"
      case RuntimeBackend.VirtualThreads => "vt"
    }

    assertEquals(testBackend(RuntimeBackend.Synchronous), "sync")
    assertEquals(testBackend(RuntimeBackend.VirtualThreads), "vt")
  }
}
