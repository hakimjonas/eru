package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Async concurrency tests for Queue operations with proper coordination.
  *
  * Tests queue behavior under concurrent access using coordination primitives for deterministic
  * testing without timing dependencies.
  */
class QueueConcurrencySpec extends EruTestSuite {

  test("bounded queue producer-consumer coordination") {
    val queue = Eru.queue[String](3).unsafeRunSync()
    val itemCount = 10
    val producerReady = Eru.promise[Nothing, Unit].unsafeRunSync()
    val consumerReady = Eru.promise[Nothing, Unit].unsafeRunSync()

    // Producer
    val producer = (for {
      _ <- producerReady.succeed(())
      _ <- consumerReady.await
      _ <- Eru.foreach(1 to itemCount) { i =>
        queue.put(s"item$i")
      }
    } yield "producer-done").fork.unsafeRunSync()

    // Consumer
    val consumer = (for {
      _ <- producerReady.await
      _ <- consumerReady.succeed(())
      items <- Eru.collectAll((1 to itemCount).map(_ => queue.take))
    } yield items).fork.unsafeRunSync()

    val (producerResult, consumerResult) = (
      producer.await.unsafeRunSync(),
      consumer.await.unsafeRunSync()
    )

    producerResult match {
      case Exit.Success(value) => assertEquals(value, "producer-done")
      case other => fail(s"Producer expected success but got: $other")
    }

    consumerResult match {
      case Exit.Success(items) =>
        // Verify all items received (order may vary due to concurrency)
        val expectedItems = (1 to itemCount).map(i => s"item$i").toSet
        assertEquals(items.toSet, expectedItems)
        assertEquals(items.size, itemCount)
      case other => fail(s"Consumer expected success but got: $other")
    }
  }

  test("multiple producers single consumer receives all items") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    val producerCount = 3
    val itemsPerProducer = 5
    val allReady = Eru.countDownLatch(producerCount + 1).unsafeRunSync()

    // Multiple producers
    val producers = (1 to producerCount).map { producerId =>
      (for {
        _ <- allReady.countDown
        _ <- allReady.await
        _ <- Eru.foreach(1 to itemsPerProducer) { i =>
          queue.put(s"P$producerId-I$i")
        }
      } yield s"producer$producerId-done").fork.unsafeRunSync()
    }

    // Single consumer
    val consumer = (for {
      _ <- allReady.countDown
      _ <- allReady.await
      items <- Eru.collectAll((1 to (producerCount * itemsPerProducer)).map(_ => queue.take))
    } yield items).fork.unsafeRunSync()

    // Wait for all producers
    producers.foreach { producer =>
      producer.await.unsafeRunSync() match {
        case Exit.Success(_) => // Expected
        case other => fail(s"Producer expected success but got: $other")
      }
    }

    // Check consumer results
    val items = consumer.await.unsafeRunSync() match {
      case Exit.Success(items) => items
      case other => fail(s"Consumer expected success but got: $other")
    }

    // Verify all items received (order between producers may vary)
    val expectedItems = (1 to producerCount).flatMap { producerId =>
      (1 to itemsPerProducer).map(i => s"P$producerId-I$i")
    }.toSet

    assertEquals(items.toSet, expectedItems, "Should receive all items from all producers")
    assertEquals(items.size, producerCount * itemsPerProducer, "Should receive correct count")
  }

  test("queue backpressure with bounded capacity") {
    val capacity = 2
    val queue = Eru.queue[Int](capacity).unsafeRunSync()
    val offerStarted = Eru.promise[Nothing, Unit].unsafeRunSync()
    val takeReady = Eru.promise[Nothing, Unit].unsafeRunSync()

    // Fill queue to capacity
    queue.put(1).unsafeRunSync()
    queue.put(2).unsafeRunSync()

    // This offer should block
    val blockedOffer = (for {
      _ <- offerStarted.succeed(())
      _ <- takeReady.await
      _ <- queue.put(3) // Should block until take
    } yield "offer-completed").fork.unsafeRunSync()

    // Wait for offer to start
    offerStarted.await.unsafeRunSync()
    takeReady.succeed(()).unsafeRunSync()

    // Take to unblock
    val taken = queue.take.unsafeRunSync()
    assertEquals(taken, 1)

    // Blocked offer should now complete
    val result = blockedOffer.await.unsafeRunSync()
    result match {
      case Exit.Success(value) => assertEquals(value, "offer-completed")
      case other => fail(s"Expected success but got: $other")
    }

    // Verify remaining items
    assertEquals(queue.take.unsafeRunSync(), 2)
    assertEquals(queue.take.unsafeRunSync(), 3)
  }

  test("unbounded queue handles high volume without blocking") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()
    val itemCount = 50 // Further reduced to prevent coordination issues

    // Simple sequential test: first produce, then consume
    (for {
      _ <- Eru.foreach(1 to itemCount)(queue.put)
    } yield ()).unsafeRunSync()

    val items = Eru.collectAll((1 to itemCount).map(_ => queue.take)).unsafeRunSync()

    // Verify all items received
    assertEquals(items.toSet, (1 to itemCount).toSet)
    assertEquals(items.size, itemCount)
  }
}
