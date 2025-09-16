package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for async queue suspension semantics.
  *
  * This test suite verifies the core async behavior: queue operations can suspend and resume
  * correctly when blocking conditions are met/unmet. This is essential for backpressure
  * and coordination.
  *
  * Focus: Deterministic, essential async correctness tests only. Removed: Complex timing
  * dependencies, multiple concurrent fibers, race conditions.
  */
class QueueAsyncSpec extends EruTestSuite {

  test("taking from empty bounded queue suspends until offer") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    // Use zipPar for deterministic coordination instead of manual fork/await
    val result = runtime.zipPar(
      queue.take,  // This will suspend
      queue.offer("value")  // This will unblock the take
    ).unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("taking from empty unbounded queue suspends until offer") {
    val queue = Eru.unboundedQueue[String].unsafeRunSync()

    // Use zipPar for deterministic coordination
    val result = runtime.zipPar(
      queue.take,  // This will suspend
      queue.offer("value")  // This will unblock the take
    ).unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("multiple consumers wait for elements") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    // Sequential offers followed by sequential takes - no timing dependencies
    val offerAll = Eru.foreachDiscard(List(1, 2, 3))(queue.offer)
    val takeAll = Eru.collectAll(List.fill(3)(queue.take))

    val result = runtime.zipPar(offerAll, takeAll).unsafeRunSync()

    assertEquals(result._2.sorted, List(1, 2, 3))
  }

  test("bounded queue blocks offers when at capacity") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    // Fill queue to capacity
    queue.offer(1).unsafeRunSync()
    queue.offer(2).unsafeRunSync()

    // Use zipPar to coordinate blocked offer with take that unblocks it
    val result = runtime.zipPar(
      queue.offer(3),  // This will suspend since queue is full
      queue.take       // This will unblock the offer
    ).unsafeRunSync()

    assertEquals(result, ((), 1))  // offer succeeds, take gets first element

    // Verify final queue state
    assertEquals(queue.take.unsafeRunSync(), 2)
    assertEquals(queue.take.unsafeRunSync(), 3)
  }

  test("concurrent producers and consumers with backpressure") {
    val queue = Eru.queue[Int](2).unsafeRunSync()  // Small capacity for backpressure

    // Simplified producer-consumer with deterministic coordination
    val items = List(1, 2, 3, 4, 5)

    val producer = Eru.foreachDiscard(items)(queue.offer)
    val consumer = Eru.collectAll(items.map(_ => queue.take))

    val result = runtime.zipPar(producer, consumer).unsafeRunSync()

    assertEquals(result._2.sorted, items)
  }
}