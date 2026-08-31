package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Stale-waiter integrity for the queue's timed operations.
  *
  * A `putWithin`/`takeWithin` that times out must fully deregister its waiter: the previous
  * implementation left the abandoned promise in the queue state, so a later `take` re-enqueued the
  * cancelled put's element (phantom data after `putWithin` returned false) and a later `tryPut`
  * handed its element to a taker that no longer existed (the element vanished).
  */
class QueueStaleWaiterSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  test("a timed-out putWithin leaves no phantom element in the queue") {
    val queue = Eru.queue[Int](1).unsafeRunSync()
    assertEquals(queue.put(1).eru.unsafeRunSync(), ())

    val timedPut = runtime.fork(queue.putWithin(2, Duration.ofMillis(50)).eru).unsafeRunSync()
    timedPut.await.unsafeRunSync() match {
      case Exit.Success(false) => ()
      case other => fail(s"Expected the put to time out with false, got: $other")
    }

    assertEquals(queue.take.eru.unsafeRunSync(), 1)
    assertEquals(queue.tryTake.unsafeRunSync(), None)
  }

  test("a timed-out takeWithin does not swallow later elements") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    val timedTake = runtime.fork(queue.takeWithin(Duration.ofMillis(50)).eru).unsafeRunSync()
    timedTake.await.unsafeRunSync() match {
      case Exit.Success(None) => ()
      case other => fail(s"Expected the take to time out with None, got: $other")
    }

    assertEquals(queue.tryPut(1).unsafeRunSync(), true)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(1))
  }
}
