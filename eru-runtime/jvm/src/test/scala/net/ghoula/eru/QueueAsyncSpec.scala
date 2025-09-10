package net.ghoula.eru

import net.ghoula.eru.prelude.*

class QueueAsyncSpec extends munit.FunSuite {

  implicit val runtime: EruRuntime = EruRuntime.create()

  // =============================================================================
  // Async Suspension Tests (JVM Only)
  // =============================================================================

  test("taking from empty bounded queue suspends until offer") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    val consumer = queue.take.fork.unsafeRunSync()

    // Give consumer a moment to register
    Eru.succeed(Thread.sleep(50)).unsafeRunSync()

    queue.offer("delayed").unsafeRunSync()
    val result = consumer.await.unsafeRunSync()

    result match {
      case Exit.Success(value) => assertEquals(value, "delayed")
      case other => fail(s"Expected success but got: $other")
    }
  }

  test("taking from empty unbounded queue suspends until offer") {
    val queue = Eru.unboundedQueue[String].unsafeRunSync()

    val consumer = queue.take.fork.unsafeRunSync()

    // Give consumer a moment to register
    Eru.succeed(Thread.sleep(50)).unsafeRunSync()

    queue.offer("delayed").unsafeRunSync()
    val result = consumer.await.unsafeRunSync()

    result match {
      case Exit.Success(value) => assertEquals(value, "delayed")
      case other => fail(s"Expected success but got: $other")
    }
  }

  test("multiple consumers wait for elements") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    val consumer1 = queue.take.fork.unsafeRunSync()
    val consumer2 = queue.take.fork.unsafeRunSync()
    val consumer3 = queue.take.fork.unsafeRunSync()

    // Give consumers time to register
    Eru.succeed(Thread.sleep(50)).unsafeRunSync()

    // Offer elements one by one
    queue.offer(1).unsafeRunSync()
    queue.offer(2).unsafeRunSync()
    queue.offer(3).unsafeRunSync()

    val results = List(consumer1, consumer2, consumer3).map { fiber =>
      fiber.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Expected success but got: $other")
      }
    }

    assertEquals(results.sorted, List(1, 2, 3))
  }

  test("bounded queue blocks offers when at capacity") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    // Fill the queue to capacity
    queue.offer(1).unsafeRunSync()
    queue.offer(2).unsafeRunSync()

    // This offer should suspend since queue is full
    val producer = queue.offer(3).fork.unsafeRunSync()

    // Give producer time to register
    Eru.succeed(Thread.sleep(50)).unsafeRunSync()

    // Taking an element should unblock the producer
    val taken = queue.take.unsafeRunSync()
    assertEquals(taken, 1)

    // Producer should now complete
    val result = producer.await.unsafeRunSync()
    result match {
      case Exit.Success(_) => // Expected
      case other => fail(s"Expected success but got: $other")
    }

    // Queue should now contain 2 and 3
    assertEquals(queue.take.unsafeRunSync(), 2)
    assertEquals(queue.take.unsafeRunSync(), 3)
  }

  test("concurrent producers and consumers with backpressure") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    val producer = Eru
      .foreach(1 to 10) { i =>
        queue.offer(i)
      }
      .fork
      .unsafeRunSync()

    val consumer = Eru.collectAll((1 to 10).map(_ => queue.take)).fork.unsafeRunSync()

    val results = consumer.await.unsafeRunSync() match {
      case Exit.Success(values) => values
      case other => fail(s"Expected success but got: $other")
    }

    producer.await.unsafeRunSync() // Ensure producer completes

    assertEquals(results.sorted, (1 to 10).toList)
  }
}
