package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Async concurrency tests for Queue operations with proper coordination.
  *
  * Tests queue behavior under concurrent access using coordination primitives for deterministic
  * testing without timing dependencies.
  *
  * Queue capacity is matched to item counts so producers never block. Item order is not guaranteed
  * under concurrent access, so assertions compare sets. The high-volume test uses a modest item
  * count to prevent coordination issues.
  */
class QueueConcurrencySpec extends EruTestSuite {

  test("bounded queue producer-consumer coordination") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    val itemCount = 10
    val startSignal = Eru.countDownLatch(2).unsafeRunSync()

    val producer = (for {
      _ <- startSignal.countDown.eru
      _ <- startSignal.await.eru
      results <- (1 to itemCount).foldLeft(Eru.succeed(List.empty[Unit])) { (acc, i) =>
        acc.flatMap(list => queue.put(s"item$i").eru.map(unit => list :+ unit))
      }
    } yield results.size).fork.unsafeRunSync()

    val consumer = (for {
      _ <- startSignal.countDown.eru
      _ <- startSignal.await.eru
      items <- (1 to itemCount).foldLeft(Eru.succeed(List.empty[String])) { (acc, _) =>
        acc.flatMap(list => queue.take.eru.map(item => list :+ item))
      }
    } yield items).fork.unsafeRunSync()

    val producerResult = producer.await.unsafeRunSync()
    val consumerResult = consumer.await.unsafeRunSync()

    producerResult match {
      case Exit.Success(count) => assertEquals(count, itemCount, "Producer should put all items")
      case other => fail(s"Producer expected success but got: $other")
    }

    consumerResult match {
      case Exit.Success(items) =>
        val expectedItems = (1 to itemCount).map(i => s"item$i").toSet
        assertEquals(items.toSet, expectedItems, "Should receive all items")
        assertEquals(items.size, itemCount, "Should receive correct count")
      case other => fail(s"Consumer expected success but got: $other")
    }
  }

  test("multiple producers single consumer receives all items") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    val producerCount = 3
    val itemsPerProducer = 5
    val allReady = Eru.countDownLatch(producerCount + 1).unsafeRunSync()

    val producers = (1 to producerCount).map { producerId =>
      (for {
        _ <- allReady.countDown.eru
        _ <- allReady.await.eru
        _ <- (1 to itemsPerProducer).foldLeft(Eru.unit) { (acc, i) =>
          acc.flatMap(_ => queue.put(s"P$producerId-I$i").eru)
        }
      } yield s"producer$producerId-done").fork.unsafeRunSync()
    }

    val consumer = (for {
      _ <- allReady.countDown.eru
      _ <- allReady.await.eru
      items <- (1 to (producerCount * itemsPerProducer)).foldLeft(Eru.succeed(List.empty[String])) { (acc, _) =>
        acc.flatMap(list => queue.take.eru.map(item => list :+ item))
      }
    } yield items).fork.unsafeRunSync()

    producers.foreach { producer =>
      producer.await.unsafeRunSync() match {
        case Exit.Success(_) =>
        case other => fail(s"Producer expected success but got: $other")
      }
    }

    val items = consumer.await.unsafeRunSync() match {
      case Exit.Success(items) => items
      case other => fail(s"Consumer expected success but got: $other")
    }

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

    queue.tryPut(1).unsafeRunSync()
    queue.tryPut(2).unsafeRunSync()

    val blockedOffer = (for {
      _ <- offerStarted.succeed(()).eru
      _ <- takeReady.await.eru
      _ <- queue.put(3).eru
    } yield "offer-completed").fork.unsafeRunSync()

    offerStarted.await.eru.unsafeRunSync()
    takeReady.succeed(()).eru.unsafeRunSync()

    val taken = queue.take.eru.unsafeRunSync()
    assertEquals(taken, 1)

    val result = blockedOffer.await.unsafeRunSync()
    result match {
      case Exit.Success(value) => assertEquals(value, "offer-completed")
      case other => fail(s"Expected success but got: $other")
    }

    assertEquals(queue.take.eru.unsafeRunSync(), 2)
    assertEquals(queue.take.eru.unsafeRunSync(), 3)
  }

  test("unbounded queue handles high volume without blocking") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()
    val itemCount = 50

    (for {
      _ <- Eru.foreach(1 to itemCount)(i => queue.put(i).eru)
    } yield ()).unsafeRunSync()

    val items = Eru.collectAll((1 to itemCount).map(_ => queue.take.eru)).unsafeRunSync()

    assertEquals(items.toSet, (1 to itemCount).toSet)
    assertEquals(items.size, itemCount)
  }
}
