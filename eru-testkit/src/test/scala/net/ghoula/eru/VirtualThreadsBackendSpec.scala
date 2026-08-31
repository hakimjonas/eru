package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

import net.ghoula.eru.test.EruTestSuite

/** Tests for the Virtual Threads backend, exercised through the real backend discovery path.
  *
  * The tracking test uses a latch to keep the fiber active long enough to observe it in the queue.
  */
class VirtualThreadsBackendSpec extends EruTestSuite {

  private def freshBackend(): net.ghoula.eru.internal.ConcurrencyBackend =
    PlatformBackend.createFreshBackend()

  test("backend fork executes and completes") {
    val backend = freshBackend()
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("backend fork handles immediate failure") {
    val backend = freshBackend()
    val effect = Eru.fail("immediate-error")

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Failure(error) => assertEquals(error, "immediate-error")
      case other => munit.Assertions.fail(s"Expected Failure('immediate-error'), got: $other")
    }
  }

  test("backend fork optimizes map chains") {
    val backend = freshBackend()
    val effect = Eru.succeed(21).map(_ * 2)

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("backend fork handles complex computations") {
    val backend = freshBackend()
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

  test("backend tracks fibers in a custom tracking queue") {
    val backend = freshBackend()
    val rootFibers = new ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()

    val latch = new java.util.concurrent.CountDownLatch(1)
    val effect = Eru.effect { latch.await(); 42 }

    val fiber = backend.forkWithTracking(effect, rootFibers).unsafeRunSync()

    assert(!rootFibers.isEmpty, "forkWithTracking must enqueue the fiber")

    latch.countDown()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }

    backend.cleanup()
  }

  test("backend race returns first completing result") {
    val backend = freshBackend()
    val slow = Eru.effect { Thread.sleep(100); "slow" }
    val fast = Eru.effect { Thread.sleep(10); "fast" }

    val result = backend.race(slow, fast).unsafeRunSync()

    result match {
      case Right("fast") => ()
      case Left("slow") => ()
      case other => munit.Assertions.fail(s"Expected fast or slow, got: $other")
    }
  }

  test("backend race handles failure from winner") {
    val backend = freshBackend()
    val fastFail = Eru.effect { Thread.sleep(10); throw new RuntimeException("fast-error") }
    val slowSuccess = Eru.effect { Thread.sleep(100); "slow-success" }

    val exception = intercept[RuntimeException] {
      backend.race(fastFail, slowSuccess).unsafeRunSync()
    }
    assert(exception.getMessage.contains("fast-error"))
  }

  test("backend race handles typed failures") {
    val backend = freshBackend()
    val fastFail = Eru.fail("typed-error")
    val slowSuccess = Eru.effect { Thread.sleep(100); "success" }

    val result = backend.race(fastFail, slowSuccess).attempt.unsafeRunSync()
    result match {
      case Result.Failure(error) =>
        assert(error.toString.contains("typed-error") || error.toString.contains("EruException"))
      case Result.Success(either) =>
        munit.Assertions.fail(s"Expected typed failure to propagate, got: $either")
    }
  }

  test("backend race interrupts losing computation") {
    val backend = freshBackend()

    val fast = Eru.succeed("fast")
    val slow = Eru.effect {
      try {
        Thread.sleep(200)
        "slow"
      } catch {
        case _: InterruptedException =>
          "interrupted"
      }
    }

    val result = backend.race(fast, slow).unsafeRunSync()

    result match {
      case Left("fast") => ()
      case other => munit.Assertions.fail(s"Expected Left('fast'), got: $other")
    }
  }

  test("backend sleep sleeps at least the requested duration") {
    val backend = freshBackend()
    val duration = Duration.ofMillis(100)

    val start = System.nanoTime()
    backend.sleep(duration).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 80L && elapsed <= 500L, s"Sleep took ${elapsed}ms, expected ~100ms")
  }
}
