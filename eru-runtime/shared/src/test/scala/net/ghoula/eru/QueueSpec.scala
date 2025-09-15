package net.ghoula.eru

import net.ghoula.eru.prelude.*

class QueueSpec extends TestWithSharedRuntime {

  // =============================================================================
  // Bounded Queue Tests
  // =============================================================================

  test("bounded queue creation succeeds") {
    val queue = Eru.queue[Int](3).unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 0)
    assertEquals(queue.isEmpty.unsafeRunSync(), true)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), 3)
  }

  test("bounded queue capacity validation") {
    intercept[IllegalArgumentException] {
      Eru.queue[Int](0).unsafeRunSync()
    }
    intercept[IllegalArgumentException] {
      Eru.queue[Int](-1).unsafeRunSync()
    }
  }

  test("bounded queue offer and take basic operations") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    queue.offer("first").unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 1)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), 1)

    queue.offer("second").unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 2)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), 0)

    val first = queue.take.unsafeRunSync()
    assertEquals(first, "first")
    assertEquals(queue.size.unsafeRunSync(), 1)

    val second = queue.take.unsafeRunSync()
    assertEquals(second, "second")
    assertEquals(queue.size.unsafeRunSync(), 0)
    assertEquals(queue.isEmpty.unsafeRunSync(), true)
  }

  test("bounded queue poll returns Some when elements available") {
    val queue = Eru.queue[Int](2).unsafeRunSync()
    queue.offer(42).unsafeRunSync()

    val result = queue.poll.unsafeRunSync()
    assertEquals(result, Some(42))
    assertEquals(queue.size.unsafeRunSync(), 0)
  }

  test("bounded queue poll returns None when empty") {
    val queue = Eru.queue[Int](2).unsafeRunSync()
    val result = queue.poll.unsafeRunSync()
    assertEquals(result, None)
  }

  test("bounded queue FIFO ordering") {
    val queue = Eru.queue[Int](5).unsafeRunSync()
    val items = List(1, 2, 3, 4, 5)

    items.foreach(i => queue.offer(i).unsafeRunSync())

    val results = (1 to 5).map(_ => queue.take.unsafeRunSync()).toList
    assertEquals(results, items)
  }

  test("bounded queue sequential operations") {
    val queue = Eru.queue[Int](5).unsafeRunSync()

    // Sequential offer and take (Native-compatible)
    Eru.foreach(1 to 5)(queue.offer).unsafeRunSync()
    val results = Eru.collectAll((1 to 5).map(_ => queue.take)).unsafeRunSync()
    assertEquals(results, List(1, 2, 3, 4, 5))
  }

  // =============================================================================
  // Unbounded Queue Tests
  // =============================================================================

  test("unbounded queue creation succeeds") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 0)
    assertEquals(queue.isEmpty.unsafeRunSync(), true)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), Int.MaxValue)
  }

  test("unbounded queue offer and take basic operations") {
    val queue = Eru.unboundedQueue[String].unsafeRunSync()

    queue.offer("test").unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 1)
    assertEquals(queue.isEmpty.unsafeRunSync(), false)

    val result = queue.take.unsafeRunSync()
    assertEquals(result, "test")
    assertEquals(queue.size.unsafeRunSync(), 0)
  }

  test("unbounded queue handles large volumes") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()
    val items = 1 to 1000

    items.foreach(i => queue.offer(i).unsafeRunSync())
    assertEquals(queue.size.unsafeRunSync(), 1000)

    val results = (1 to 1000).map(_ => queue.take.unsafeRunSync()).toList
    assertEquals(results, items.toList)
  }

  test("unbounded queue sequential operations") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()

    // Sequential operations (Native-compatible)
    Eru.foreach(1 to 100)(queue.offer).unsafeRunSync()
    val results = Eru.collectAll((1 to 100).map(_ => queue.take)).unsafeRunSync()
    assertEquals(results, (1 to 100).toList)
  }

  // =============================================================================
  // Cross-Platform Edge Cases
  // =============================================================================
  // Note: Async suspension tests are in QueueAsyncSpec (JVM-only)

  test("poll returns None consistently on empty queue") {
    val bounded = Eru.queue[Int](5).unsafeRunSync()
    val unbounded = Eru.unboundedQueue[Int].unsafeRunSync()

    assertEquals(bounded.poll.unsafeRunSync(), None)
    assertEquals(unbounded.poll.unsafeRunSync(), None)

    // After offering and taking, should be empty again
    bounded.offer(42).unsafeRunSync()
    bounded.take.unsafeRunSync()
    assertEquals(bounded.poll.unsafeRunSync(), None)
  }

  // =============================================================================
  // Integration with RuntimeExtensions
  // =============================================================================

  test("queue constructors available via Eru companion") {
    val bounded = Eru.queue[String](5).unsafeRunSync()
    val unbounded = Eru.unboundedQueue[String].unsafeRunSync()

    bounded.offer("bounded").unsafeRunSync()
    unbounded.offer("unbounded").unsafeRunSync()

    assertEquals(bounded.take.unsafeRunSync(), "bounded")
    assertEquals(unbounded.take.unsafeRunSync(), "unbounded")
  }

  test("queue operations compose with other Eru effects") {
    val queue = Eru.queue[String](3).unsafeRunSync()

    val program = for {
      _ <- queue.offer("first")
      _ <- queue.offer("second")
      size <- queue.size
      _ <- Eru.when(size > 0)(queue.offer("third"))
      results <- Eru.collectAll(List(queue.take, queue.take, queue.take))
    } yield results

    val results = program.unsafeRunSync()
    assertEquals(results, List("first", "second", "third"))
  }
}
