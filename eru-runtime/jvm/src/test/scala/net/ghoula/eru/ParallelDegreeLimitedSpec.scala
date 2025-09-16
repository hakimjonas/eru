package net.ghoula.eru

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Test suite for degree-limited parallel operations.
  *
  * Validates foreachParN and foreachParNDiscard operations that limit the number of concurrent
  * fibers while still providing parallel execution benefits. These operations are essential for
  * resource-controlled parallel processing when working with external systems.
  */
final class ParallelDegreeLimitedSpec extends EruTestSuite {

  test("foreachParN processes all items with results") {
    val items = (1 to 10).toList
    val results = foreachParN(3, items) { i =>
      Eru.succeed(i * 2)
    }.unsafeRunSync()

    val expected = (1 to 10).map(_ * 2).toList
    assertEquals(results, expected)
  }

  test("foreachParN with empty collection returns empty list") {
    val results = foreachParN(5, List.empty[Int]) { i =>
      Eru.succeed(i * 2)
    }.unsafeRunSync()

    assertEquals(results, List.empty)
  }

  test("foreachParN with degree 1 processes sequentially") {
    val processOrder = ConcurrentLinkedQueue[Int]()
    val items = (1 to 5).toList

    val results = foreachParN(1, items) { i =>
      Eru.effect {
        processOrder.add(i)
        i
      }
    }.unsafeRunSync()

    assertEquals(results, List(1, 2, 3, 4, 5))
    assertEquals(processOrder.asScala.toList, List(1, 2, 3, 4, 5))
  }

  test("foreachParN limits concurrent execution") {
    val concurrentCount = new AtomicInteger(0)
    val maxConcurrentCount = new AtomicInteger(0)
    val items = (1 to 12).toList // Reduced to ensure we can measure concurrency

    val results = foreachParN(3, items) { i =>
      Eru.effect {
        val current = concurrentCount.incrementAndGet()
        val max = maxConcurrentCount.get()
        if (current > max) {
          maxConcurrentCount.set(current)
        }

        // Sufficient computation to allow concurrency measurement
        val _ = (1 to 1000).sum

        concurrentCount.decrementAndGet()
        i
      }
    }.unsafeRunSync()

    assertEquals(results.size, 12)
    assert(maxConcurrentCount.get() <= 3, s"Max concurrent was ${maxConcurrentCount.get()}, expected <= 3")
  }

  test("foreachParN propagates first failure") {
    val items = (1 to 10).toList

    intercept[EruException[String]] {
      foreachParN(3, items) { i =>
        if (i == 5) Eru.fail("error") else Eru.succeed(i)
      }.unsafeRunSync()
    }
  }

  test("foreachParN requires positive degree") {
    val items = List(1, 2, 3)

    intercept[IllegalArgumentException] {
      foreachParN(0, items) { i => Eru.succeed(i) }.unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      foreachParN(-1, items) { i => Eru.succeed(i) }.unsafeRunSync()
    }
  }

  test("foreachParNDiscard processes all items without results") {
    val processed = java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val items = (1 to 5).toList

    val result = foreachParNDiscard(2, items) { i =>
      Eru.effect {
        processed.add(i)
        i * 2 // This result should be discarded
      }
    }.unsafeRunSync()

    assertEquals(result, ())
    assertEquals(processed.asScala.toSet, Set(1, 2, 3, 4, 5))
  }

  test("foreachParNDiscard with empty collection succeeds immediately") {
    val result = foreachParNDiscard(5, List.empty[Int]) { i =>
      Eru.succeed(i)
    }.unsafeRunSync()

    assertEquals(result, ())
  }

  test("foreachParNDiscard limits concurrent execution") {
    val concurrentCount = new AtomicInteger(0)
    val maxConcurrentCount = new AtomicInteger(0)
    val items = (1 to 8).toList // Reduced for better concurrency measurement

    foreachParNDiscard(4, items) { i =>
      Eru.effect {
        val current = concurrentCount.incrementAndGet()
        val max = maxConcurrentCount.get()
        if (current > max) {
          maxConcurrentCount.set(current)
        }

        // Sufficient computation to allow concurrency measurement
        val _ = (1 to 1000).sum

        concurrentCount.decrementAndGet()
        i
      }
    }.unsafeRunSync()

    assert(maxConcurrentCount.get() <= 4, s"Max concurrent was ${maxConcurrentCount.get()}, expected <= 4")
  }

  test("foreachParNDiscard propagates failures") {
    val items = (1 to 5).toList

    intercept[EruException[String]] {
      foreachParNDiscard(2, items) { i =>
        if (i == 3) Eru.fail("error") else Eru.succeed(i)
      }.unsafeRunSync()
    }
  }
}
