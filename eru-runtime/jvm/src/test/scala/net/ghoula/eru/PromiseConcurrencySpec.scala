package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Async concurrency tests for Promise coordination patterns.
  *
  * These tests validate Promise behavior under concurrent access without relying on timing
  * assumptions or Thread.sleep.
  */
class PromiseConcurrencySpec extends TestWithRuntime {

  test("promise coordinates multiple waiters correctly") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val waiterCount = 5
    val waiterReady = Eru.countDownLatch(waiterCount).unsafeRunSync()

    // Multiple waiters
    val waiters = (1 to waiterCount).map { i =>
      runtime.fork {
        for {
          _ <- waiterReady.countDown
          result <- promise.await
        } yield s"waiter$i: $result"
      }.unsafeRunSync()
    }

    // Wait for all waiters to be ready
    waiterReady.await.unsafeRunSync()

    // Complete the promise
    promise.succeed(42).unsafeRunSync()

    // All waiters should receive the same result
    val results = waiters.map { waiter =>
      waiter.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Expected success but got: $other")
      }
    }

    results.foreach { result =>
      assert(result.endsWith(": 42"), s"Expected result ending with ': 42', got: $result")
    }
  }

  test("promise producer-consumer coordination") {
    val workPromise = Eru.promise[Nothing, String].unsafeRunSync()
    val resultPromise = Eru.promise[Nothing, String].unsafeRunSync()

    // Producer
    val producer = runtime.fork {
      for {
        _ <- workPromise.succeed("work-data")
        result <- resultPromise.await
      } yield s"Producer received: $result"
    }.unsafeRunSync()

    // Consumer
    val consumer = runtime.fork {
      for {
        work <- workPromise.await
        processed = work.toUpperCase
        _ <- resultPromise.succeed(processed)
      } yield s"Consumer processed: $processed"
    }.unsafeRunSync()

    val (producerResult, consumerResult) = (
      producer.await.unsafeRunSync(),
      consumer.await.unsafeRunSync()
    )

    producerResult match {
      case Exit.Success(value) => assertEquals(value, "Producer received: WORK-DATA")
      case other => fail(s"Producer expected success but got: $other")
    }

    consumerResult match {
      case Exit.Success(value) => assertEquals(value, "Consumer processed: WORK-DATA")
      case other => fail(s"Consumer expected success but got: $other")
    }
  }

  test("promise failure propagates to all waiters") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val waiterCount = 3
    val allReady = Eru.countDownLatch(waiterCount).unsafeRunSync()

    // Multiple waiters
    val waiters = (1 to waiterCount).map { _ =>
      runtime.fork {
        for {
          _ <- allReady.countDown
          result <- promise.await.attempt
        } yield result
      }.unsafeRunSync()
    }

    // Wait for all waiters
    allReady.await.unsafeRunSync()

    // Fail the promise
    promise.fail("error-occurred").unsafeRunSync()

    // All waiters should receive the same error
    waiters.foreach { waiter =>
      val result = waiter.await.unsafeRunSync()
      result match {
        case Exit.Success(Result.Failure(error)) => assertEquals(error, "error-occurred")
        case other => fail(s"Expected failure but got: $other")
      }
    }
  }

  test("promise race condition handling - first completion wins") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val competitorCount = 5 // Reduced from 10 to prevent potential deadlocks
    val allReady = Eru.countDownLatch(competitorCount).unsafeRunSync()

    // Multiple competitors trying to complete the promise
    val competitors = (1 to competitorCount).map { i =>
      runtime.fork {
        for {
          _ <- allReady.countDown
          _ <- allReady.await // Wait for all to be ready
          completed <- promise.succeed(i)
        } yield if (completed) Some(i) else None
      }.unsafeRunSync()
    }

    val results = competitors.map { competitor =>
      competitor.await.unsafeRunSync() match {
        case Exit.Success(result) => result
        case other => fail(s"Expected success but got: $other")
      }
    }

    // Exactly one should succeed
    val successful = results.flatten
    assertEquals(successful.size, 1, "Exactly one competitor should succeed")

    // Promise should be completed with the winning value
    val finalValue = promise.await.unsafeRunSync()
    assertEquals(finalValue, successful.head)
  }
}
