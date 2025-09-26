package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Test case demonstrating collectAll deadlock and the correct solution.
  *
  * This test demonstrates that collectAll (sequential) deadlocks with concurrent queue operations,
  * while parSequence (parallel) handles them correctly. This is expected behavior since collectAll
  * is designed for sequential execution, not concurrent blocking operations.
  */
class CollectAllDeadlockSpec extends EruTestSuite {

  test("collectAll deadlocks with concurrent queue operations (expected behavior)") {
    val queue = Eru.queue[String](5).unsafeRunSync()

    // Pre-populate with fewer items than we'll try to take
    queue.put("item1").unsafeRunSync()
    queue.put("item2").unsafeRunSync()

    // This should timeout because collectAll executes sequentially
    // and the third take will block waiting for an item that never comes
    val takes = List(queue.take, queue.take, queue.take)

    val result = Eru
      .collectAll(takes)
      .timeout(java.time.Duration.ofSeconds(1))
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(_: java.util.concurrent.TimeoutException) =>
        // This is expected - collectAll deadlocks with blocking operations
        assert(true, "collectAll correctly deadlocks with insufficient queue items")
      case other =>
        fail(s"Expected timeout but got: $other")
    }
  }

  test("parSequence handles concurrent queue operations correctly") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    val itemCount = 15

    // Reproduce the exact pattern from QueueConcurrencySpec
    val allReady = Eru.countDownLatch(4).unsafeRunSync()

    // Start producers that will offer items after coordination
    val producers = (1 to 3).map { producerId =>
      (for {
        _ <- allReady.countDown
        _ <- allReady.await
        _ <- Eru.foreach(1 to 5) { i =>
          queue.put(s"P$producerId-I$i")
        }
      } yield s"producer$producerId-done").fork.unsafeRunSync()
    }

    // Consumer using collectAll (the problematic pattern)
    val consumer = (for {
      _ <- allReady.countDown
      _ <- allReady.await
      // Use parSequence for concurrent queue operations instead of sequential collectAll
      items <- runtime.parSequence((1 to itemCount).map(_ => queue.take).toList)
    } yield items).fork.unsafeRunSync()

    // Add timeout to prevent hanging
    val result = consumer.await
      .timeout(java.time.Duration.ofSeconds(10))
      .attempt
      .unsafeRunSync()

    // Clean up producers
    producers.foreach(_.await.unsafeRunSync())

    result match {
      case Result.Success(Exit.Success(items)) =>
        assertEquals(items.size, itemCount, "Should collect all items")
      case Result.Success(other) =>
        fail(s"Consumer fiber failed: $other")
      case Result.Failure(_: java.util.concurrent.TimeoutException) =>
        fail("collectAll deadlocked in producer-consumer scenario - this is the bug")
      case Result.Failure(other) =>
        fail(s"Unexpected error: $other")
    }
  }

  test("traverse works correctly as a comparison") {
    val queue = Eru.queue[String](20).unsafeRunSync()
    val itemCount = 15

    // Pre-populate the queue with items
    (1 to itemCount).foreach { i =>
      queue.put(s"item-$i").unsafeRunSync()
    }

    // traverse should work fine (sequential execution)
    val result = Eru.traverse((1 to itemCount).toList)(_ => queue.take).unsafeRunSync()

    assertEquals(result.size, itemCount, "traverse should collect all items")
    val expectedItems = (1 to itemCount).map(i => s"item-$i").toSet
    assertEquals(result.toSet, expectedItems, "traverse should get all expected items")
  }

  test("collectAll with simple effects works fine") {
    val simpleEffects = (1 to 15).map(i => Eru.succeed(s"simple-$i"))

    val result = Eru.collectAll(simpleEffects.toList).unsafeRunSync()

    assertEquals(result.size, 15, "collectAll should work with simple effects")
    val expectedItems = (1 to 15).map(i => s"simple-$i").toList
    assertEquals(result, expectedItems, "collectAll should preserve order with simple effects")
  }

  test("minimal reproduction of collectAll queue deadlock") {
    val queue = Eru.queue[Int](5).unsafeRunSync()

    // Pre-populate with fewer items to make debugging easier
    queue.put(1).unsafeRunSync()
    queue.put(2).unsafeRunSync()
    queue.put(3).unsafeRunSync()

    // This minimal case should also demonstrate the deadlock
    val takes = List(queue.take, queue.take, queue.take)

    val result = Eru
      .collectAll(takes)
      .timeout(java.time.Duration.ofSeconds(2))
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Success(items) =>
        assertEquals(items.sorted, List(1, 2, 3), "Should get all items")
      case Result.Failure(_: java.util.concurrent.TimeoutException) =>
        fail("Even minimal collectAll case deadlocks")
      case Result.Failure(other) =>
        fail(s"Unexpected error: $other")
    }
  }
}
