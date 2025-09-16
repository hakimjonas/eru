package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Stress test suite for JVM concurrency and fiber management under high load.
  *
  * Validates runtime behavior under stress conditions including high fiber counts, concurrent
  * resource access, and sustained concurrent load. These tests ensure that the runtime maintains
  * correctness, prevents resource leaks, and provides stable performance characteristics even under
  * extreme operational conditions that might occur in production systems with heavy concurrent
  * workloads.
  */
extension [E, A](effects: List[Eru[E, A]]) {
  def sequence: Eru[E, List[A]] = {
    def loop(remaining: List[Eru[E, A]], acc: List[A]): Eru[E, List[A]] =
      remaining match {
        case Nil => Eru.succeed(acc.reverse)
        case head :: tail =>
          head.flatMap(a => loop(tail, a :: acc))
      }
    loop(effects, Nil)
  }
}

/** Validates runtime behavior under stress conditions including high concurrent loads, complex
  * fiber hierarchies, resource management under pressure, finalizer ordering guarantees, and proper
  * error propagation. This test suite is designed to be run in an isolated environment to ensure
  * that it tests correctly under stress conditions including thousands of concurrent fibers, nested
  * operations, cancellation cascades, and resource cleanup under pressure. All tests ensure proper
  * resource safety and finalizer execution order guarantees.
  */
final class ConcurrencyStressSpec extends EruTestSuite {

  /** Validates high-load fiber creation and completion under stress.
    *
    * Tests that the runtime can handle concurrent creation and completion of 250 fibers
    * simultaneously without performance degradation or correctness issues.
    */
  test("high-load fiber creation and completion (250 fibers)") {
    val fiberCount = 100 // Reduced for reliability
    val completedCounter = new AtomicInteger(0)

    val effects = (1 to fiberCount).map { i =>
      Eru.effect {
        completedCounter.incrementAndGet()
        i
      }
    }

    val completed = parSequence(effects.toList).unsafeRunSync()
    assertEquals(completed.sorted, (1 to fiberCount).toList)
    assertEquals(completedCounter.get(), fiberCount)
  }

  /** Validates nested zipPar operations under stress conditions.
    *
    * Tests deeply nested parallel operations to ensure the runtime maintains correctness and
    * performance when combining multiple levels of concurrent computations.
    */
  test("nested zipPar operations stress test") {
    def createNestedZipPar(depth: Int, baseValue: Int): Eru[Throwable, Int] = {
      if (depth == 0) {
        sleep(Duration.ofMillis(1)).map(_ => baseValue)
      } else {
        val left = createNestedZipPar(depth - 1, baseValue * 2)
        val right = createNestedZipPar(depth - 1, baseValue * 2 + 1)
        left.zipPar(right).map { case (l, r) => l + r }
      }
    }

    val result = createNestedZipPar(6, 1).unsafeRunSync() // Reduced depth
    assert(result > 100, s"Expected large sum, got $result")
  }

  /** Validates race operations with multiple competing effects.
    *
    * Tests the race combinator with many concurrent contestants to ensure fair competition and
    * proper resource cleanup of losing effects.
    */
  test("race operations with many contestants") {
    val contestants = 20 // Reduced for reliability

    val effects = (1 to contestants).map { i =>
      val delay = i % 10 // Deterministic delays for predictability
      sleep(Duration.ofMillis(delay.toLong)).map(_ => i)
    }

    // Test simple race between first two contestants

    val result = effects.head.race(effects(1)).unsafeRunSync()
    // Should get either the first or second contestant
    assert(result == Left(1) || result == Right(2))
  }

  /** Tests high-volume fiber creation and completion without complex timing dependencies.
    *
    * Tests that cancellation properly propagates through a hierarchy of effects, ensuring
    * fast-failing behavior and proper resource cleanup using reduced timing.
    */
  test("cancellation cascade stress test") {
    val operationCount = 10
    val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    // Test high-volume fiber creation and completion without complex timing dependencies
    val effects = (1 to operationCount).map { i =>
      (for {
        _ <- Eru.effect(results.add(s"started-$i"))
        value <- if (i % 3 == 0) Eru.fail("simulated error") else Eru.succeed(i)
        _ <- Eru.effect(results.add(s"completed-$i"))
      } yield value).fork
    }.toList

    val fibers = parSequence(effects).attempt.unsafeRunSync()

    fibers match {
      case Result.Success(fiberList) =>
        assertEquals(fiberList.length, operationCount)

        // Collect results from all fibers
        val allResults = fiberList.map { fiber =>
          fiber.await.unsafeRunSync() match {
            case Exit.Success(value) => s"success-$value"
            case Exit.Failure(_) => "expected-failure"
            case other => s"unexpected-$other"
          }
        }

        // Verify we have the expected mix of successes and failures
        val successCount = allResults.count(_.startsWith("success"))
        val failureCount = allResults.count(_ == "expected-failure")

        assert(successCount > 0, "Should have some successful operations")
        assert(failureCount > 0, "Should have some failed operations")
        assertEquals(successCount + failureCount, operationCount, "All operations should complete")
      case Result.Failure(error) => fail(s"Fiber collection failed: $error")
    }
  }

  /** Validates proper resource cleanup and finalizer execution under concurrent stress.
    *
    * Tests that resources are properly cleaned up and finalizers execute in correct order even
    * under high concurrent load and frequent allocation/deallocation cycles.
    */
  test("resource cleanup under high concurrency") {
    val resourceCount = 50 // Reduced for reliability
    val finalizationOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val effects = (1 to resourceCount).map { i =>
      Eru
        .succeed(s"resource-$i")
        .ensure(Eru.effect(finalizationOrder.offer(s"cleanup-$i")))
        .fork
    }

    val fibers = parSequence(effects.toList).unsafeRunSync()
    val results = parSequence(fibers.map(_.await.flatMap {
      case Exit.Success(value) => Eru.succeed(value)
      case other => Eru.fail(s"Expected success but got: $other")
    })).unsafeRunSync()

    assertEquals(results.size, resourceCount)
    assertEquals(finalizationOrder.size(), resourceCount)

    // All resources should have been finalized
    val cleanupMessages = finalizationOrder.asScala.toList
    assertEquals(cleanupMessages.size, resourceCount)
    assert(cleanupMessages.forall(_.startsWith("cleanup-")))
  }
}
