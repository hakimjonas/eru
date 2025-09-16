package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Test case to reproduce and fix the collectAll deadlock with queue operations.
  *
  * This test demonstrates the bug where collectAll with concurrent queue.take operations can
  * deadlock, hanging after processing only some of the operations.
  */
class CollectAllDeadlockSpec extends EruTestSuite {

  test("collectAll should handle concurrent queue operations without deadlock") {
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
          queue.offer(s"P$producerId-I$i")
        }
      } yield s"producer$producerId-done").fork.unsafeRunSync()
    }

    // Consumer using collectAll (the problematic pattern)
    val consumer = (for {
      _ <- allReady.countDown
      _ <- allReady.await
      // This is where the deadlock occurs in the original test
      items <- Eru.collectAll((1 to itemCount).map(_ => queue.take).toList)
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
      queue.offer(s"item-$i").unsafeRunSync()
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
    queue.offer(1).unsafeRunSync()
    queue.offer(2).unsafeRunSync()
    queue.offer(3).unsafeRunSync()

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
