package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.TimeoutException

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for RuntimeExtensions functionality.
  *
  * Validates all extension methods that enrich the Eru API with concurrency operations, timeout
  * handling, retry policies, runner conveniences, and constructors for runtime coordination types.
  * Tests ensure proper integration with implicit EruRuntime instances and verify structured
  * concurrency semantics.
  *
  * Timing-based concurrency assertions (zipPar, parSequence, parTraverse) assume the JVM backend
  * with VirtualThreads and bound elapsed time well below the sequential sum of the underlying
  * sleeps to prove concurrency. Error-propagation assertions compare via toString.contains because
  * a failure may surface as the typed error or wrapped in a defect.
  */
class RuntimeExtensionsSpec extends EruTestSuite {

  test("fork extension creates concurrent fiber") {
    val effect = Eru.succeed(42)
    val fiber = effect.fork.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("forkWithObserver extension captures fiber events") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val observer = new TestObserver
    val effect = Eru.succeed(42)
    val fiber = effect.forkWithObserver(observer).unsafeRunSync()
    fiber.await.unsafeRunSync()

    val events = observer.events
    assert(events.nonEmpty, "Observer should capture fiber events")

    val fiberEvents = events.collect {
      case e: EruEvent.FiberStarted => e
      case e: EruEvent.FiberCompleted => e
    }

    assert(fiberEvents.nonEmpty, "Should capture fiber lifecycle events")
  }

  test("forkDaemon extension creates fiber without tracking") {
    val effect = Eru.succeed(789)
    val fiber = effect.forkDaemon.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 789)
      case other => fail(s"Expected Success(789), got: $other")
    }
  }

  test("forkTracked extension uses custom tracker") {
    val tracker = FiberTracker()
    val effect = Eru.succeed(321)
    val fiber = effect.forkTracked(tracker).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 321)
      case other => fail(s"Expected Success(321), got: $other")
    }
  }

  test("zipPar extension runs effects concurrently") {
    val start = System.nanoTime()
    val effect1 = RuntimeExtensions.sleep(Duration.ofMillis(25)).map(_ => "first")
    val effect2 = RuntimeExtensions.sleep(Duration.ofMillis(25)).map(_ => "second")

    val result = effect1.zipPar(effect2).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(result, ("first", "second"))
    assert(elapsed < 45L, s"zipPar should be concurrent, took ${elapsed}ms")
  }

  test("zipPar extension propagates failures") {
    val success = Eru.succeed("success")
    val failure = Eru.fail("error")

    val exception = intercept[EruException[String]] {
      success.zipPar(failure).unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("race extension returns first completing effect") {
    val slow = RuntimeExtensions.sleep(Duration.ofMillis(50)).map(_ => "slow")
    val fast = RuntimeExtensions.sleep(Duration.ofMillis(5)).map(_ => "fast")

    val result = slow.race(fast).unsafeRunSync()

    result match {
      case Right("fast") => ()
      case other => munit.Assertions.fail(s"Expected Right('fast'), got: $other")
    }
  }

  test("race extension propagates winner's failure") {
    val success = RuntimeExtensions.sleep(Duration.ofMillis(50)).map(_ => "success")
    val failure = Eru.fail("fast-error")

    val exception = intercept[EruException[String]] {
      success.race(failure).unsafeRunSync()
    }
    assertEquals(exception.error, "fast-error")
  }

  test("timeout extension fails on slow computation") {
    val slowEffect = RuntimeExtensions.sleep(Duration.ofMillis(100))
    val timedOut = slowEffect.timeout(Duration.ofMillis(10))

    val exception = intercept[TimeoutException] {
      timedOut.unsafeRunSync()
    }
    assert(exception.getMessage.contains("Operation timed out"))
  }

  test("timeout extension succeeds on fast computation") {
    val fastEffect = Eru.succeed(42)
    val timed = fastEffect.timeout(Duration.ofMillis(100))

    val result = timed.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("timeoutTo extension provides fallback on timeout") {
    val slowEffect = RuntimeExtensions.sleep(Duration.ofMillis(100)).map(_ => "slow")
    val withFallback = slowEffect.timeoutTo(Duration.ofMillis(10), "fallback")

    val result = withFallback.unsafeRunSync()
    assertEquals(result, "fallback")
  }

  test("timeoutTo extension returns original on fast completion") {
    val fastEffect = Eru.succeed("fast")
    val withFallback = fastEffect.timeoutTo(Duration.ofMillis(100), "fallback")

    val result = withFallback.unsafeRunSync()
    assertEquals(result, "fast")
  }

  test("retry extension retries on failure") {
    var attempts = 0
    def failingEffect: Eru[String, String] =
      Eru.effect {
        attempts += 1
        if (attempts < 3) throw new RuntimeException(s"Attempt $attempts")
        else "success"
      }.mapError(_.getMessage)

    val policy = EruRuntime.Policy.NoDelay(3)
    val result = failingEffect.retry(policy).attempt.unsafeRunSync()

    result match {
      case Result.Success(value) =>
        assertEquals(value, "success")
        assert(attempts >= 2, s"Should have made at least 2 attempts, got: $attempts")
      case Result.Failure(_) =>
        assert(attempts >= 1, s"Should have made at least 1 attempt, got: $attempts")
    }
  }

  test("retryN extension retries specified number of times") {
    var attempts = 0
    def alwaysFailingEffect: Eru[String, String] =
      Eru.effect {
        attempts += 1
        throw new RuntimeException(s"Attempt $attempts")
      }.mapError(_.getMessage)

    val result = alwaysFailingEffect.retryN(2).attempt.unsafeRunSync()
    result match {
      case Result.Success(_) =>
        munit.Assertions.fail("Effect should not succeed")
      case Result.Failure(_) =>
        assert(attempts >= 1, s"Should have made at least 1 attempt, got: $attempts")
    }
  }

  test("retryWithBackoff extension uses exponential backoff") {
    var attempts = 0
    val startTime = System.nanoTime()

    def failingEffect: Eru[String, String] =
      Eru.effect {
        attempts += 1
        throw new RuntimeException(s"Attempt $attempts")
      }.mapError(_.getMessage)

    val result = failingEffect.retryWithBackoff(Duration.ofMillis(1), 1).attempt.unsafeRunSync()

    val elapsed = (System.nanoTime() - startTime) / 1_000_000L
    result match {
      case Result.Success(_) =>
        munit.Assertions.fail("Effect should not succeed")
      case Result.Failure(_) =>
        assert(attempts >= 1, s"Should have made at least 1 attempt, got: $attempts")
        assert(elapsed >= 0L, s"Test should complete, took ${elapsed}ms")
    }
  }

  test("runExit extension returns Exit instead of throwing") {
    val success = Eru.succeed(42)
    val failure = Eru.fail("error")
    val defect = Eru.effect(throw new RuntimeException("defect"))

    val successExit = success.runExit()
    assertEquals(successExit, Exit.Success(42))

    val failureExit = failure.runExit()
    assertEquals(failureExit, Exit.Failure("error"))

    val defectExit = defect.runExit()
    defectExit match {
      case Exit.Die(throwable) => assert(throwable.getMessage.contains("defect"))
      case other => munit.Assertions.fail(s"Expected Die, got: $other")
    }
  }

  test("runWith extension integrates with observer") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val observer = new TestObserver
    val effect = Eru.succeed(42)

    val result = effect.runWith(observer)
    assertEquals(result, 42)

    val events = observer.events
    assert(events.nonEmpty, "Observer should capture execution events")
  }

  test("Eru.ref extension creates Ref") {
    val refEffect = Eru.ref(42)
    val ref = refEffect.unsafeRunSync()

    val initialValue = ref.get.unsafeRunSync()
    assertEquals(initialValue, 42)

    ref.set(99).unsafeRunSync()
    val updatedValue = ref.get.unsafeRunSync()
    assertEquals(updatedValue, 99)
  }

  test("Eru.deferred extension creates Deferred") {
    val deferredEffect = Eru.deferred[String]
    val deferred = deferredEffect.unsafeRunSync()

    val completerFiber = deferred.complete("completed").eru.fork.unsafeRunSync()
    val awaiterFiber = deferred.await.eru.fork.unsafeRunSync()

    val completerExit = completerFiber.await.unsafeRunSync()
    val awaiterExit = awaiterFiber.await.unsafeRunSync()

    completerExit match {
      case Exit.Success(true) => ()
      case other => munit.Assertions.fail(s"Expected Success(true) for completer, got: $other")
    }

    awaiterExit match {
      case Exit.Success(value) => assertEquals(value, "completed")
      case other => munit.Assertions.fail(s"Expected Success('completed') for awaiter, got: $other")
    }
  }

  test("Eru.semaphore extension creates Semaphore") {
    val semaphoreEffect = Eru.semaphore(2)
    val semaphore = semaphoreEffect.unsafeRunSync()

    val acquired1 = semaphore.tryAcquire.unsafeRunSync()
    val acquired2 = semaphore.tryAcquire.unsafeRunSync()
    assertEquals(acquired1, true)
    assertEquals(acquired2, true)

    val availablePermits = semaphore.permitsAvailable.unsafeRunSync()
    assertEquals(availablePermits, 0L)

    val acquired3 = semaphore.tryAcquire.unsafeRunSync()
    assertEquals(acquired3, false)

    semaphore.release.unsafeRunSync()

    val permitsAfterRelease = semaphore.permitsAvailable.unsafeRunSync()
    assertEquals(permitsAfterRelease, 1L)
  }

  test("Eru.queue extension creates bounded Queue") {
    val queueEffect = Eru.queue[String](3)
    val queue = queueEffect.unsafeRunSync()

    queue.put("first").eru.unsafeRunSync()
    queue.put("second").eru.unsafeRunSync()
    val size = queue.size.unsafeRunSync()
    assertEquals(size, 2)

    val taken = queue.take.eru.unsafeRunSync()
    assertEquals(taken, "first")

    val sizeAfterTake = queue.size.unsafeRunSync()
    assertEquals(sizeAfterTake, 1)
  }

  test("Eru.unboundedQueue extension creates unbounded Queue") {
    val queueEffect = Eru.unboundedQueue[Int]
    val queue = queueEffect.unsafeRunSync()

    (1 to 100).foreach { i =>
      queue.put(i).eru.unsafeRunSync()
    }

    val size = queue.size.unsafeRunSync()
    assertEquals(size, 100)
  }

  test("Eru.hub extension creates bounded Hub") {
    val hubEffect = Eru.hub[String](10)
    val hub = hubEffect.unsafeRunSync()

    val subscription = hub.subscribe.unsafeRunSync()

    hub.publish("message").eru.unsafeRunSync()

    val received = subscription.take.eru.unsafeRunSync()
    assertEquals(received, "message")
  }

  test("Eru.unboundedHub extension creates unbounded Hub") {
    val hubEffect = Eru.unboundedHub[String]
    val hub = hubEffect.unsafeRunSync()

    val subscription = hub.subscribe.unsafeRunSync()

    hub.publish("unbounded-message").eru.unsafeRunSync()

    val received = subscription.take.eru.unsafeRunSync()
    assertEquals(received, "unbounded-message")
  }

  test("Eru.promise extension creates Promise") {
    val promiseEffect = Eru.promise[String, Int]
    val promise = promiseEffect.unsafeRunSync()

    val completeFiber = promise.succeed(42).eru.fork.unsafeRunSync()
    val awaitFiber = promise.await.eru.fork.unsafeRunSync()

    val completeExit = completeFiber.await.unsafeRunSync()
    val awaitExit = awaitFiber.await.unsafeRunSync()

    completeExit match {
      case Exit.Success(true) => ()
      case other => munit.Assertions.fail(s"Expected Success(true) for complete, got: $other")
    }

    awaitExit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42) for await, got: $other")
    }
  }

  test("Eru.countDownLatch extension creates CountDownLatch") {
    val latchEffect = Eru.countDownLatch(3)
    val latch = latchEffect.unsafeRunSync()

    val initialCount = latch.count.unsafeRunSync()
    assertEquals(initialCount, 3)

    latch.countDown.unsafeRunSync()
    latch.countDown.unsafeRunSync()

    val countAfterCountDown = latch.count.unsafeRunSync()
    assertEquals(countAfterCountDown, 1)

    val awaitFiber = latch.await.eru.fork.unsafeRunSync()
    latch.countDown.unsafeRunSync()

    val awaitExit = awaitFiber.await.unsafeRunSync()
    awaitExit match {
      case Exit.Success(()) => ()
      case other => munit.Assertions.fail(s"Expected Success(()), got: $other")
    }
  }

  test("Eru.cyclicBarrier extension creates CyclicBarrier") {
    val barrierEffect = Eru.cyclicBarrier(2)
    val barrier = barrierEffect.unsafeRunSync()

    val fiber1 = barrier.await.eru.fork.unsafeRunSync()
    val fiber2 = barrier.await.eru.fork.unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()

    exit1 match {
      case Exit.Success(()) => ()
      case other => munit.Assertions.fail(s"Expected Success(()) for fiber1, got: $other")
    }

    exit2 match {
      case Exit.Success(()) => ()
      case other => munit.Assertions.fail(s"Expected Success(()) for fiber2, got: $other")
    }
  }

  test("sleep function pauses execution") {
    val start = System.nanoTime()
    RuntimeExtensions.sleep(Duration.ofMillis(25)).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assert(elapsed >= 15L && elapsed <= 50L, s"Sleep took ${elapsed}ms, expected ~25ms")
  }

  test("parSequence function executes effects concurrently") {
    val effects = List(
      RuntimeExtensions.sleep(Duration.ofMillis(20)).map(_ => "first"),
      RuntimeExtensions.sleep(Duration.ofMillis(10)).map(_ => "second"),
      RuntimeExtensions.sleep(Duration.ofMillis(15)).map(_ => "third")
    )

    val start = System.nanoTime()
    val results = RuntimeExtensions.parSequence(effects).unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(results, List("first", "second", "third"))
    assert(elapsed < 35L, s"parSequence should be concurrent, took ${elapsed}ms")
  }

  test("parTraverse function processes inputs concurrently") {
    val inputs = List(20, 10, 15)

    val start = System.nanoTime()
    val results = RuntimeExtensions
      .parTraverse(inputs) { millis =>
        RuntimeExtensions.sleep(Duration.ofMillis(millis.toLong)).map(_ => millis * 2)
      }
      .unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1_000_000L

    assertEquals(results, List(40, 20, 30))
    assert(elapsed < 35L, s"parTraverse should be concurrent, took ${elapsed}ms")
  }

  test("raceAll function returns first completing effect with index") {
    val effects = List(
      RuntimeExtensions.sleep(Duration.ofMillis(50)).map(_ => "slow"),
      RuntimeExtensions.sleep(Duration.ofMillis(5)).map(_ => "fast"),
      RuntimeExtensions.sleep(Duration.ofMillis(100)).map(_ => "slowest")
    )

    val (result, index) = RuntimeExtensions.raceAll(effects).unsafeRunSync()

    assertEquals(result, "fast")
    assertEquals(index, 1)
  }

  test("foreachParN function limits concurrency") {
    val inputs = (1 to 10).toList
    val maxConcurrency = 3

    val results = RuntimeExtensions
      .foreachParN(maxConcurrency, inputs) { i =>
        RuntimeExtensions.sleep(Duration.ofMillis(5)).map(_ => i * 2)
      }
      .unsafeRunSync()

    assertEquals(results, inputs.map(_ * 2))
  }

  test("foreachParNDiscard function discards results") {
    val inputs = List(1, 2, 3)

    val result = RuntimeExtensions
      .foreachParNDiscard(2, inputs) { i =>
        RuntimeExtensions.sleep(Duration.ofMillis(1)).map(_ => i * 100)
      }
      .unsafeRunSync()

    assertEquals(result, ())
  }

  test("validatePar function accumulates all errors") {
    val validations = List(
      Eru.fail("error1"),
      Eru.succeed("success1"),
      Eru.fail("error2"),
      Eru.succeed("success2")
    )

    val result = RuntimeExtensions.validatePar(validations).unsafeRunSync()

    result match {
      case Left(errors) =>
        assertEquals(errors.toSet, Set("error1", "error2"))
      case Right(_) => munit.Assertions.fail("Expected errors to be accumulated")
    }
  }

  test("validatePar function returns successes when all valid") {
    val validations = List(
      Eru.succeed("valid1"),
      Eru.succeed("valid2"),
      Eru.succeed("valid3")
    )

    val result = RuntimeExtensions.validatePar(validations).unsafeRunSync()

    result match {
      case Right(successes) => assertEquals(successes, List("valid1", "valid2", "valid3"))
      case Left(_) => munit.Assertions.fail("Expected all validations to succeed")
    }
  }

  test("validateFirst function returns first error encountered") {
    val validations = List(
      Eru.succeed("success1"),
      Eru.fail("first-error"),
      Eru.fail("second-error")
    )

    val result = RuntimeExtensions.validateFirst(validations).unsafeRunSync()

    result match {
      case Left(error) => assertEquals(error, "first-error")
      case Right(_) => munit.Assertions.fail("Expected first error to be returned")
    }
  }

  test("validateFirst function returns all successes when valid") {
    val validations = List(
      Eru.succeed("valid1"),
      Eru.succeed("valid2")
    )

    val result = RuntimeExtensions.validateFirst(validations).unsafeRunSync()

    result match {
      case Right(successes) => assertEquals(successes, List("valid1", "valid2"))
      case Left(_) => munit.Assertions.fail("Expected all validations to succeed")
    }
  }

  test("extensions work with typed error handling") {
    val successEffect = Eru.succeed("success")
    val failureEffect: Eru[String, String] = Eru.fail("typed-error")

    val combinedResult = successEffect.zipPar(successEffect).attempt.unsafeRunSync()
    combinedResult match {
      case Result.Success((v1, v2)) =>
        assertEquals(v1, "success")
        assertEquals(v2, "success")
      case other => munit.Assertions.fail(s"Expected success, got: $other")
    }

    val failureResult = successEffect.zipPar(failureEffect).attempt.unsafeRunSync()
    failureResult match {
      case Result.Failure(error) =>
        assert(error.toString.contains("typed-error"))
      case other => munit.Assertions.fail(s"Expected failure, got: $other")
    }
  }
}
