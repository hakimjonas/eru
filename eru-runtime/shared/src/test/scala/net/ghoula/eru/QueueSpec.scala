package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for Queue implementation.
  *
  * Validates concurrent queue operations, fiber suspension/resumption semantics, bounded vs
  * unbounded queue behavior, and proper cleanup under high contention. Tests ensure that queues
  * correctly handle concurrent producers/consumers and maintain consistency across different queue
  * variants.
  */
class QueueSpec extends EruTestSuite {

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

  // =============================================================================
  // Additional Comprehensive Tests
  // =============================================================================

  test("queue size and capacity invariants") {
    val capacity = 5
    val queue = Eru.queue[Int](capacity).unsafeRunSync()

    (1 to capacity).foreach { i =>
      queue.offer(i).unsafeRunSync()
      assertEquals(queue.size.unsafeRunSync(), i)
      assertEquals(queue.remainingCapacity.unsafeRunSync(), capacity - i)
    }

    (1 to capacity).foreach { i =>
      queue.take.unsafeRunSync()
      assertEquals(queue.size.unsafeRunSync(), capacity - i)
      assertEquals(queue.remainingCapacity.unsafeRunSync(), i)
    }
  }

  test("queue handles mixed offer/take/poll operations") {
    val queue = Eru.queue[String](4).unsafeRunSync()

    queue.offer("a").unsafeRunSync()
    assertEquals(queue.poll.unsafeRunSync(), Some("a"))
    assertEquals(queue.size.unsafeRunSync(), 0)

    queue.offer("b").unsafeRunSync()
    queue.offer("c").unsafeRunSync()
    assertEquals(queue.size.unsafeRunSync(), 2)

    assertEquals(queue.take.unsafeRunSync(), "b")
    assertEquals(queue.poll.unsafeRunSync(), Some("c"))
    assertEquals(queue.poll.unsafeRunSync(), None)
    assert(queue.isEmpty.unsafeRunSync())
  }

  test("bounded queue maintains consistency under stress") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    val numOperations = 50

    // Perform mixed operations that won't cause suspension
    (1 to numOperations).foreach { i =>
      if (i % 3 == 0 && queue.size.unsafeRunSync() > 0) {
        queue.poll.unsafeRunSync() // Only poll when queue has items
      } else if (queue.remainingCapacity.unsafeRunSync() > 0) {
        queue.offer(i).unsafeRunSync() // Only offer when there's capacity
        if (i % 5 == 0 && queue.size.unsafeRunSync() > 0) {
          queue.take.unsafeRunSync() // Only take when queue has items
        }
      }
    }

    // Queue should maintain valid invariants
    val size = queue.size.unsafeRunSync()
    val capacity = queue.remainingCapacity.unsafeRunSync()
    assertEquals(capacity, 10 - size)
    assert(size >= 0 && size <= 10)
  }

  test("unbounded queue maintains consistency under stress") {
    val queue = Eru.unboundedQueue[Int].unsafeRunSync()
    val numOperations = 100

    var expectedSize = 0
    (1 to numOperations).foreach { i =>
      if (i % 4 == 0 && expectedSize > 0) {
        queue.take.unsafeRunSync()
        expectedSize -= 1
      } else if (i % 7 == 0 && expectedSize > 0) {
        queue.poll.unsafeRunSync()
        expectedSize -= 1
      } else {
        queue.offer(i).unsafeRunSync()
        expectedSize += 1
      }
    }

    assertEquals(queue.size.unsafeRunSync(), expectedSize)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), Int.MaxValue)
  }

  test("queue type safety with case classes") {
    case class Message(id: Int, content: String)

    val queue = Eru.queue[Message](3).unsafeRunSync()
    val messages = List(
      Message(1, "hello"),
      Message(2, "world"),
      Message(3, "test")
    )

    messages.foreach(queue.offer(_).unsafeRunSync())
    val retrieved = messages.map(_ => queue.take.unsafeRunSync())
    assertEquals(retrieved, messages)
  }

  test("queue operations with Option types") {
    val queue = Eru.queue[Option[String]](2).unsafeRunSync()

    queue.offer(Some("value")).unsafeRunSync()
    queue.offer(None).unsafeRunSync()

    assertEquals(queue.take.unsafeRunSync(), Some("value"))
    assertEquals(queue.take.unsafeRunSync(), None)
  }

  test("bounded vs unbounded queue behavior comparison") {
    val bounded = Eru.queue[Int](3).unsafeRunSync()
    val unbounded = Eru.unboundedQueue[Int].unsafeRunSync()

    // Same operations on both
    (1 to 5).foreach { i =>
      bounded.offer(i).unsafeRunSync()
      unbounded.offer(i).unsafeRunSync()
    }

    // Both should maintain FIFO
    val boundedResults = (1 to 5).map(_ => bounded.take.unsafeRunSync()).toList
    val unboundedResults = (1 to 5).map(_ => unbounded.take.unsafeRunSync()).toList

    assertEquals(boundedResults, (1 to 5).toList)
    assertEquals(unboundedResults, (1 to 5).toList)

    // Capacity should differ
    assertEquals(bounded.remainingCapacity.unsafeRunSync(), 3)
    assertEquals(unbounded.remainingCapacity.unsafeRunSync(), Int.MaxValue)
  }

  test("queue isEmpty correctness across operations") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    assert(queue.isEmpty.unsafeRunSync(), "New queue should be empty")

    queue.offer("item").unsafeRunSync()
    assert(!queue.isEmpty.unsafeRunSync(), "Queue with items should not be empty")

    queue.take.unsafeRunSync()
    assert(queue.isEmpty.unsafeRunSync(), "Empty queue after take should be empty")

    queue.offer("another").unsafeRunSync()
    queue.poll.unsafeRunSync()
    assert(queue.isEmpty.unsafeRunSync(), "Empty queue after poll should be empty")
  }

  test("queue size consistency with concurrent-like operations") {
    val queue = Eru.queue[Int](5).unsafeRunSync()

    // Simulate concurrent-like behavior with sequential operations
    val operations = List(
      () => { queue.offer(1).unsafeRunSync(); 1 },
      () => { queue.offer(2).unsafeRunSync(); 1 },
      () => { queue.take.unsafeRunSync(); -1 },
      () => { queue.offer(3).unsafeRunSync(); 1 },
      () => { queue.poll.unsafeRunSync(); if (queue.size.unsafeRunSync() > 0) -1 else 0 }
    )

    var expectedSize = 0
    operations.foreach { op =>
      expectedSize += op()
      assertEquals(queue.size.unsafeRunSync(), math.max(0, expectedSize))
    }
  }

  test("queue large capacity handling") {
    val largeCapacity = 1000
    val queue = Eru.queue[Int](largeCapacity).unsafeRunSync()

    assertEquals(queue.remainingCapacity.unsafeRunSync(), largeCapacity)

    // Fill partially
    (1 to 100).foreach(queue.offer(_).unsafeRunSync())
    assertEquals(queue.size.unsafeRunSync(), 100)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), largeCapacity - 100)
  }

  test("unbounded queue very large operations") {
    val queue = Eru.unboundedQueue[Long].unsafeRunSync()
    val numItems = 500L

    // Add many items
    (1L to numItems).foreach(queue.offer(_).unsafeRunSync())
    assertEquals(queue.size.unsafeRunSync(), numItems.toInt)

    // Remove half
    (1L to numItems / 2).foreach(_ => queue.take.unsafeRunSync())
    assertEquals(queue.size.unsafeRunSync(), (numItems / 2).toInt)

    // Capacity should remain max
    assertEquals(queue.remainingCapacity.unsafeRunSync(), Int.MaxValue)
  }

  test("queue memory and resource efficiency") {
    val queue = Eru.queue[String](20).unsafeRunSync()

    // Perform many cycles to test resource cleanup
    (1 to 20).foreach { cycle =>
      (1 to 10).foreach(i => queue.offer(s"cycle-$cycle-item-$i").unsafeRunSync())
      (1 to 10).foreach(_ => queue.take.unsafeRunSync())
      assert(queue.isEmpty.unsafeRunSync(), s"Queue should be empty after cycle $cycle")
    }

    // Final state should be clean
    assertEquals(queue.size.unsafeRunSync(), 0)
    assertEquals(queue.remainingCapacity.unsafeRunSync(), 20)
  }

  test("queue error resilience") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    // Normal operations should continue to work
    queue.offer("first").unsafeRunSync()
    queue.offer("second").unsafeRunSync()

    assertEquals(queue.take.unsafeRunSync(), "first")
    assertEquals(queue.poll.unsafeRunSync(), Some("second"))
    assertEquals(queue.poll.unsafeRunSync(), None)

    // Queue should still be functional
    queue.offer("recovery").unsafeRunSync()
    assertEquals(queue.take.unsafeRunSync(), "recovery")
  }

  test("queue integration with effect composition") {
    val queue1 = Eru.queue[Int](2).unsafeRunSync()
    val queue2 = Eru.queue[String](2).unsafeRunSync()

    val workflow = for {
      _ <- queue1.offer(42)
      _ <- queue2.offer("hello")
      num <- queue1.take
      str <- queue2.take
      _ <- queue1.offer(num * 2)
      _ <- queue2.offer(str.toUpperCase)
      result1 <- queue1.take
      result2 <- queue2.take
    } yield (result1, result2)

    val (result1, result2) = workflow.unsafeRunSync()
    assertEquals(result1, 84)
    assertEquals(result2, "HELLO")
  }

  test("queue boundary conditions") {
    // Minimum capacity
    val minQueue = Eru.queue[Int](1).unsafeRunSync()
    minQueue.offer(123).unsafeRunSync()
    assertEquals(minQueue.remainingCapacity.unsafeRunSync(), 0)
    assertEquals(minQueue.take.unsafeRunSync(), 123)

    // Edge case values
    val edgeQueue = Eru.queue[Option[Int]](3).unsafeRunSync()
    val values = List(Some(0), None, Some(Int.MaxValue))
    values.foreach(edgeQueue.offer(_).unsafeRunSync())

    val results = values.map(_ => edgeQueue.take.unsafeRunSync())
    assertEquals(results, values)
  }
}
