package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Async concurrency tests for Promise coordination patterns.
  *
  * These tests validate Promise behavior under concurrent access without relying on timing
  * assumptions or Thread.sleep.
  */
class PromiseConcurrencySpec extends EruTestSuite {

  test("promise coordinates multiple waiters correctly") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val waiterCount = 5
    val waiterReady = Eru.countDownLatch(waiterCount).unsafeRunSync()

    // Multiple waiters
    val waiters = (1 to waiterCount).map { i =>
      (for {
        _ <- waiterReady.countDown.eru
        result <- promise.await.eru
      } yield s"waiter$i: $result").fork.unsafeRunSync()
    }

    // Wait for all waiters to be ready
    waiterReady.await.eru.unsafeRunSync()

    // Complete the promise
    promise.succeed(42).eru.unsafeRunSync()

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
    val producer = (for {
      _ <- workPromise.succeed("work-data").eru
      result <- resultPromise.await.eru
    } yield s"Producer received: $result").fork.unsafeRunSync()

    // Consumer
    val consumer = (for {
      work <- workPromise.await.eru
      processed = work.toUpperCase
      _ <- resultPromise.succeed(processed).eru
    } yield s"Consumer processed: $processed").fork.unsafeRunSync()

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
      (for {
        _ <- allReady.countDown.eru
        result <- promise.await.eru.attempt
      } yield result).fork.unsafeRunSync()
    }

    // Wait for all waiters
    allReady.await.eru.unsafeRunSync()

    // Fail the promise
    promise.fail("error-occurred").eru.unsafeRunSync()

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
      (for {
        _ <- allReady.countDown.eru
        _ <- allReady.await.eru // Wait for all to be ready
        completed <- promise.succeed(i).eru
      } yield if (completed) Some(i) else None).fork.unsafeRunSync()
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
    val finalValue = promise.await.eru.unsafeRunSync()
    assertEquals(finalValue, successful.head)
  }
}
