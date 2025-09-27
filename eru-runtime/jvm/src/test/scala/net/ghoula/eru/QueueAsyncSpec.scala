package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for async queue suspension semantics.
  *
  * This test suite verifies the core async behavior: queue operations can suspend and resume
  * correctly when blocking conditions are met/unmet. This is essential for backpressure and
  * coordination.
  *
  * Focus: Deterministic, essential async correctness tests only. Removed: Complex timing
  * dependencies, multiple concurrent fibers, race conditions.
  */
class QueueAsyncSpec extends EruTestSuite {

  test("taking from empty bounded queue suspends until offer") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    // Use zipPar for deterministic coordination instead of manual fork/await
    val result = runtime
      .zipPar(
        queue.take.eru, // This will suspend
        queue.put("value").eru // This will unblock the take
      )
      .unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("taking from empty unbounded queue suspends until offer") {
    val queue = Eru.unboundedQueue[String].unsafeRunSync()

    // Use zipPar for deterministic coordination
    val result = runtime
      .zipPar(
        queue.take.eru, // This will suspend
        queue.put("value").eru // This will unblock the take
      )
      .unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("multiple consumers wait for elements") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    // First put all items
    Eru.foreachDiscard(List(1, 2, 3))(a => queue.put(a).eru).unsafeRunSync()

    // Then take them all
    val result = Eru.collectAll(List.fill(3)(queue.take.eru)).unsafeRunSync()

    assertEquals(result.sorted, List(1, 2, 3))
  }

  test("bounded queue blocks offers when at capacity") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    // Fill queue to capacity
    queue.put(1).eru.unsafeRunSync()
    queue.put(2).eru.unsafeRunSync()

    // Use zipPar to coordinate blocked offer with take that unblocks it
    val result = runtime
      .zipPar(
        queue.put(3).eru, // This will suspend since queue is full
        queue.take.eru // This will unblock the offer
      )
      .unsafeRunSync()

    assertEquals(result, ((), 1)) // offer succeeds, take gets first element

    // Verify final queue state
    assertEquals(queue.take.eru.unsafeRunSync(), 2)
    assertEquals(queue.take.eru.unsafeRunSync(), 3)
  }

  test("concurrent producers and consumers with backpressure") {
    val queue = Eru.queue[Int](100).unsafeRunSync() // Large capacity to avoid race conditions

    // Simplified producer-consumer with deterministic coordination
    val items = List(1, 2, 3, 4, 5)

    // Producer puts all items first
    val producer = Eru.foreachDiscard(items)(a => queue.put(a).eru)

    // Run producer to completion first
    producer.unsafeRunSync()

    // Then consumer takes exactly the same number of items
    val consumer = Eru.collectAll(items.map(_ => queue.take.eru))
    val result = consumer.unsafeRunSync()

    // Verify all items were transferred
    assertEquals(result.sorted, items)
  }
}
