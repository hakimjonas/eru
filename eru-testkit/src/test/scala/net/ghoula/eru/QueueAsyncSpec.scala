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
  *
  * Queue capacity is sized generously to avoid race conditions.
  */
class QueueAsyncSpec extends EruTestSuite {

  test("taking from empty bounded queue suspends until offer") {
    val queue = Eru.queue[String](2).unsafeRunSync()

    val result = runtime
      .zipPar(
        queue.take.eru,
        queue.put("value").eru
      )
      .unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("taking from empty unbounded queue suspends until offer") {
    val queue = Eru.unboundedQueue[String].unsafeRunSync()

    val result = runtime
      .zipPar(
        queue.take.eru,
        queue.put("value").eru
      )
      .unsafeRunSync()

    assertEquals(result, ("value", ()))
  }

  test("multiple consumers wait for elements") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    Eru.foreachDiscard(List(1, 2, 3))(a => queue.put(a).eru).unsafeRunSync()

    val result = Eru.collectAll(List.fill(3)(queue.take.eru)).unsafeRunSync()

    assertEquals(result.sorted, List(1, 2, 3))
  }

  test("bounded queue blocks offers when at capacity") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    queue.put(1).eru.unsafeRunSync()
    queue.put(2).eru.unsafeRunSync()

    val result = runtime
      .zipPar(
        queue.put(3).eru,
        queue.take.eru
      )
      .unsafeRunSync()

    assertEquals(result, ((), 1))

    assertEquals(queue.take.eru.unsafeRunSync(), 2)
    assertEquals(queue.take.eru.unsafeRunSync(), 3)
  }

  test("concurrent producers and consumers with backpressure") {
    val queue = Eru.queue[Int](100).unsafeRunSync()

    val items = List(1, 2, 3, 4, 5)

    val producer = Eru.foreachDiscard(items)(a => queue.put(a).eru)

    producer.unsafeRunSync()

    val consumer = Eru.collectAll(items.map(_ => queue.take.eru))
    val result = consumer.unsafeRunSync()

    assertEquals(result.sorted, items)
  }
}
