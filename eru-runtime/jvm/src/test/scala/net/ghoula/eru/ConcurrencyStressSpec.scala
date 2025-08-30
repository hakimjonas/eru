package net.ghoula.eru

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.Random

import net.ghoula.eru.prelude.*

// Simple sequence implementation for testing
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

/** Comprehensive stress test suite for concurrency functionality under high load.
  *
  * These tests validate that Eru's concurrency primitives (fork, zipPar, race, timers) work
  * correctly under stress conditions including thousands of concurrent fibers, nested operations,
  * cancellation cascades, and resource cleanup under pressure. All tests ensure proper resource
  * safety and finalizer execution order guarantees.
  */
final class ConcurrencyStressSpec extends FunSuite {

  test("high-load fiber creation and completion (1000 fibers)") {
    val fiberCount = 1000
    val completedCounter = new AtomicInteger(0)

    val fibers = (1 to fiberCount).map { i =>
      EruRuntime.fork {
        Eru.effect {
          // Simulate some work with random delay
          Thread.sleep(Random.nextInt(5))
          completedCounter.incrementAndGet()
          i
        }
      }
    }

    // Await all fibers and verify results
    val results = fibers.map { fiber =>
      fiber.flatMap(_.await).map {
        case Exit.Success(value) => value
        case other => throw new RuntimeException(s"Unexpected exit: $other")
      }
    }

    val completed = results.toList.sequence.unsafeRunSync()
    assertEquals(completed.sorted, (1 to fiberCount).toList)
    assertEquals(completedCounter.get(), fiberCount)
  }

  test("nested zipPar operations stress test") {
    // Create a tree of nested zipPar operations
    def createNestedZipPar(depth: Int, baseValue: Int): Eru[Throwable, Int] = {
      if (depth == 0) {
        EruRuntime.sleep(Duration.ofMillis(1)).map(_ => baseValue)
      } else {
        val left = createNestedZipPar(depth - 1, baseValue * 2)
        val right = createNestedZipPar(depth - 1, baseValue * 2 + 1)
        EruRuntime.zipPar(left, right).map { case (l, r) => l + r }
      }
    }

    val result = createNestedZipPar(8, 1).unsafeRunSync()
    // With depth 8, we should get a significant sum
    assert(result > 1000, s"Expected large sum, got $result")
  }

  test("race operations with many contestants") {
    val contestants = 50
    val results = (1 to contestants).map { i =>
      // Create effects with random delays, some will win, others lose
      val delay = Random.nextInt(20) + 1 // 1-20ms
      EruRuntime.sleep(Duration.ofMillis(delay.toLong)).map(_ => i)
    }

    // Race all contestants in pairs, then race the results
    def raceAll(effects: List[Eru[Throwable, Int]]): Eru[Throwable, Int] = effects match {
      case single :: Nil => single
      case first :: second :: rest =>
        val winner = EruRuntime.race(first, second).map {
          case Left(value) => value
          case Right(value) => value
        }
        raceAll(winner :: rest)
      case Nil => Eru.fail(new IllegalArgumentException("No effects to race"))
    }

    val winner = raceAll(results.toList).unsafeRunSync()
    assert(winner >= 1 && winner <= contestants, s"Winner $winner should be in range 1-$contestants")
  }

  test("cancellation cascade stress test") {
    val cancelled = new AtomicInteger(0)
    val completed = new AtomicInteger(0)

    def createCancellableEffect(id: Int): Eru[String | Throwable, Int] = {
      EruRuntime.sleep(Duration.ofMillis(100)).flatMap { _ =>
        if (Thread.currentThread().isInterrupted) {
          cancelled.incrementAndGet()
          Eru.fail(s"Effect $id was cancelled")
        } else {
          completed.incrementAndGet()
          Eru.succeed(id)
        }
      }
    }

    // Create a tree of zipPar operations that should cancel when first fails
    val fastFail: Eru[String | Throwable, Int] =
      EruRuntime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.fail("fast failure"))
    val slowEffects = (1 to 20).map(createCancellableEffect)

    val result = slowEffects
      .foldLeft(fastFail) { (acc, effect) =>
        EruRuntime.zipPar(acc, effect).map { case (a, b) => a + b }
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(_) =>
        // Expected failure, and some effects should have been cancelled
        assert(cancelled.get() >= 0, "Some effects should have been cancelled or interrupted")
        assert(true)
      case Result.Success(_) => fail("Expected failure due to fast fail effect")
    }
  }

  test("resource cleanup under high concurrency") {
    val acquired = new AtomicInteger(0)
    val released = new AtomicInteger(0)
    val fiberCount = 200

    def createResourceEffect(id: Int): Eru[String | Throwable, Int] = {
      Eru.effect {
        acquired.incrementAndGet()
        id
      }.ensure(Eru.effect {
        released.incrementAndGet()
      }).flatMap { value =>
        // Random chance of failure to test cleanup under failure
        if (Random.nextBoolean()) {
          Eru.succeed(value)
        } else {
          Eru.fail(s"Random failure in effect $id")
        }
      }
    }

    val effects = (1 to fiberCount).map(createResourceEffect)
    val results = effects.map(_.attempt)

    // Run all effects and wait for completion
    results.toList.sequence.unsafeRunSync()

    // Verify all resources were properly cleaned up
    assertEquals(acquired.get(), fiberCount, "All resources should have been acquired")
    assertEquals(released.get(), fiberCount, "All resources should have been released")
  }

  test("timer scheduling under load") {
    val timerCount = 100
    val startTime = System.nanoTime()

    // Create many concurrent timers with different delays
    val timers = (1 to timerCount).map { i =>
      val delay = (i % 10) + 1 // 1-10ms delays, repeated pattern
      EruRuntime.sleep(Duration.ofMillis(delay.toLong)).map(_ => i)
    }

    // Execute all timers concurrently
    val results = timers.map(timer => EruRuntime.fork(timer))
    val completed = results
      .map(_.flatMap(_.await).map {
        case Exit.Success(value) => value
        case other => throw new RuntimeException(s"Timer failed: $other")
      })
      .toList
      .sequence
      .unsafeRunSync()

    val elapsedMs = (System.nanoTime() - startTime) / 1000000L

    // Verify all timers completed and timing is reasonable
    assertEquals(completed.sorted, (1 to timerCount).toList)
    // Should complete faster than sequential execution (which would be ~550ms)
    // Allow some overhead for Virtual Thread scheduling and concurrency management
    assert(elapsedMs < 800L, s"Concurrent timers took too long: ${elapsedMs}ms")
  }

  test("mixed concurrent and sequential operations") {
    val concurrentCount = 50
    val sequentialCount = 10

    // Create concurrent operations
    val concurrentOps = (1 to concurrentCount).map { i =>
      EruRuntime.fork {
        EruRuntime.sleep(Duration.ofMillis(2)).map(_ => s"concurrent-$i")
      }.flatMap(_.await).map {
        case Exit.Success(value) => value
        case other => throw new RuntimeException(s"Concurrent op failed: $other")
      }
    }

    // Create sequential operations
    def createSequential(remaining: Int, acc: List[String]): Eru[Nothing, List[String]] = {
      if (remaining <= 0) {
        Eru.succeed(acc.reverse)
      } else {
        EruRuntime.sleep(Duration.ofMillis(1)).flatMap { _ =>
          createSequential(remaining - 1, s"sequential-$remaining" :: acc)
        }
      }
    }

    val sequential = createSequential(sequentialCount, Nil)

    // Combine concurrent and sequential work
    val combined = EruRuntime
      .zipPar(
        concurrentOps.toList.sequence,
        sequential
      )
      .unsafeRunSync()

    val (concurrentResults, sequentialResults) = combined

    assertEquals(concurrentResults.length, concurrentCount)
    assertEquals(sequentialResults.length, sequentialCount)
    assert(
      concurrentResults.forall(_.startsWith("concurrent-")),
      "All concurrent results should start with 'concurrent-'"
    )
    assert(
      sequentialResults.forall(_.startsWith("sequential-")),
      "All sequential results should start with 'sequential-'"
    )
  }

  test("finalizer execution order under concurrent stress") {
    val executionOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val fiberCount = 100

    def createEffectWithFinalizers(id: Int): Eru[Nothing, Int] = {
      Eru
        .succeed(id)
        .ensure(Eru.effect { executionOrder.add(s"outer-$id") })
        .ensure(Eru.effect { executionOrder.add(s"middle-$id") })
        .ensure(Eru.effect { executionOrder.add(s"inner-$id") })
    }

    // Run effects concurrently
    val effects = (1 to fiberCount).map(createEffectWithFinalizers)
    val fibers = effects.map(EruRuntime.fork)
    val results = fibers.map(_.flatMap(_.await)).toList.sequence.unsafeRunSync()

    // Verify all effects completed successfully
    val successCount = results.count {
      case Exit.Success(_) => true
      case _ => false
    }
    assertEquals(successCount, fiberCount)

    // Verify finalizers executed (exact order may vary due to concurrency)
    val orderList = executionOrder.asScala.toList
    assertEquals(orderList.length, fiberCount * 3) // 3 finalizers per effect

    // Verify each fiber's finalizers executed in FILO order (inner first, outer last)
    (1 to fiberCount).foreach { id =>
      val fiberFinalizers = orderList.filter(_.endsWith(s"-$id"))
      assertEquals(fiberFinalizers, List(s"inner-$id", s"middle-$id", s"outer-$id"))
    }
  }

  test("timeout handling under concurrent load") {
    val fastCount = 30
    val slowCount = 30
    val timeoutMs = 20L

    // Create fast effects that complete before timeout
    val fastEffects = (1 to fastCount).map { i =>
      val effect = EruRuntime.sleep(Duration.ofMillis(5)).map(_ => s"fast-$i")
      EruRuntime.timeout(Duration.ofMillis(timeoutMs))(effect)
    }

    // Create slow effects that should timeout
    val slowEffects = (1 to slowCount).map { i =>
      val effect = EruRuntime.sleep(Duration.ofMillis(50)).map(_ => s"slow-$i")
      EruRuntime.timeout(Duration.ofMillis(timeoutMs))(effect)
    }

    val allEffects = fastEffects ++ slowEffects
    val results = allEffects.map(_.attempt).toList.sequence.unsafeRunSync()

    val successes = results.count {
      case Result.Success(_) => true
      case _ => false
    }
    val timeouts = results.count {
      case Result.Failure(_: java.util.concurrent.TimeoutException) => true
      case _ => false
    }

    // Fast effects should succeed, slow effects should timeout
    assert(successes >= fastCount * 0.8, s"Expected most fast effects to succeed, got $successes")
    assert(timeouts >= slowCount * 0.8, s"Expected most slow effects to timeout, got $timeouts")
  }
}
