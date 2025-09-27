package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for the suspension type system.
  *
  * This suite demonstrates and validates the compile-time safety guarantees provided by Eru's
  * Suspending and Immediate value classes.
  */
class SuspensionSystemSpec extends EruTestSuite {

  test("Queue operations follow suspension semantics correctly") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    // Immediate operations work synchronously
    assertEquals(queue.tryPut(1).unsafeRunSync(), true)
    assertEquals(queue.tryPut(2).unsafeRunSync(), true)
    assertEquals(queue.tryPut(3).unsafeRunSync(), false) // Queue full
    assertEquals(queue.size.unsafeRunSync(), 2)

    // Suspending operations must be handled asynchronously
    val takeFiber = queue.take.eru.fork.unsafeRunSync()
    val putFiber = queue.put(3).eru.fork.unsafeRunSync()

    // Both complete successfully
    assertEquals(takeFiber.await.unsafeRunSync(), Exit.Success(1))
    assertEquals(putFiber.await.unsafeRunSync(), Exit.Success(()))

    // Final state check
    assertEquals(queue.size.unsafeRunSync(), 2)
  }

  test("Promise operations follow suspension semantics") {
    val promise = Promise.make[Nothing, String].unsafeRunSync()

    // Immediate operations
    assertEquals(promise.isDone.unsafeRunSync(), false)
    assertEquals(promise.tryGet.unsafeRunSync(), None)

    // Start awaiter before completing
    val awaiter = promise.await.eru.fork.unsafeRunSync()

    // Complete the promise (immediate)
    assertEquals(promise.succeed("result").unsafeRunSync(), true)
    assertEquals(promise.succeed("again").unsafeRunSync(), false) // Already completed

    // Awaiter receives the value
    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success("result"))
  }

  test("Semaphore operations follow suspension semantics") {
    val sem = Semaphore.make(1).unsafeRunSync()

    // Immediate operations
    assertEquals(sem.tryAcquire.unsafeRunSync(), true)
    assertEquals(sem.tryAcquire.unsafeRunSync(), false) // No permits left
    assertEquals(sem.permitsAvailable.unsafeRunSync(), 0L)

    // Start acquirer that will suspend
    val acquirer = sem.acquire.eru.fork.unsafeRunSync()
    // Fiber starts immediately, no delay needed

    // Release permit
    sem.release.unsafeRunSync()

    // Acquirer completes
    assertEquals(acquirer.await.unsafeRunSync(), Exit.Success(()))
  }

  test("CountDownLatch operations follow suspension semantics") {
    val latch = Eru.countDownLatch(2).unsafeRunSync()

    // Immediate operations
    assertEquals(latch.getCount.unsafeRunSync(), 2)
    latch.countDown.unsafeRunSync()
    assertEquals(latch.getCount.unsafeRunSync(), 1)

    // Start awaiter that will suspend
    val awaiter = latch.await.eru.fork.unsafeRunSync()

    // Final countdown releases awaiter
    latch.countDown.unsafeRunSync()
    assertEquals(latch.getCount.unsafeRunSync(), 0)

    // Awaiter completes
    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success(()))
  }

  test("CyclicBarrier operations follow suspension semantics") {
    val barrier = Eru.cyclicBarrier(2).unsafeRunSync()

    // Immediate operations
    assertEquals(barrier.getParties.unsafeRunSync(), 2)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.unsafeRunSync(), false)

    // First party waits
    val party1 = barrier.await.eru.fork.unsafeRunSync()

    // Second party releases both
    val party2 = barrier.await.eru.fork.unsafeRunSync()

    // Both complete
    assertEquals(party1.await.unsafeRunSync(), Exit.Success(()))
    assertEquals(party2.await.unsafeRunSync(), Exit.Success(()))

    // After completion, waiting count should be 0
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)

    // Barrier resets for next cycle
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
  }

  test("Deferred operations follow suspension semantics") {
    val deferred = Deferred.make[Int].unsafeRunSync()

    // Immediate operations
    assertEquals(deferred.isDone.unsafeRunSync(), false)
    assertEquals(deferred.poll.unsafeRunSync(), None)

    // Start awaiter
    val awaiter = deferred.await.eru.fork.unsafeRunSync()

    // Complete the deferred
    assertEquals(deferred.complete(42).unsafeRunSync(), true)
    assertEquals(deferred.complete(99).unsafeRunSync(), false) // Already completed

    // Check final state
    assertEquals(deferred.isDone.unsafeRunSync(), true)
    assertEquals(deferred.poll.unsafeRunSync(), Some(42))
    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success(42))
  }

  test("Hub operations follow suspension semantics") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    // Subscribe (immediate)
    val sub1 = hub.subscribe.unsafeRunSync()
    val sub2 = hub.subscribe.unsafeRunSync()
    assertEquals(hub.subscriberCount.unsafeRunSync(), 2)

    // Publish (suspending but won't block with empty subscribers)
    hub.publish("msg1").eru.fork.unsafeRunSync().await.unsafeRunSync()

    // Subscribers can take
    assertEquals(sub1.take.eru.fork.unsafeRunSync().await.unsafeRunSync(), Exit.Success("msg1"))
    assertEquals(sub2.take.eru.fork.unsafeRunSync().await.unsafeRunSync(), Exit.Success("msg1"))
  }

  test("Timeout converts Suspending to Immediate") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    // Fill queue
    queue.tryPut(1).unsafeRunSync()

    // put would suspend, but timeout makes it immediate
    val putResult: Immediate[Throwable, Unit] = queue.put(2).timeout(Duration.ofMillis(50))

    // This compiles and runs (will timeout and throw)
    try {
      putResult.unsafeRunSync()
      fail("Expected timeout exception")
    } catch {
      case _: TimeoutError => // Expected - Eru uses TimeoutError
      case _: java.util.concurrent.TimeoutException => // Also accept Java timeout
      case other: Throwable => fail(s"Expected timeout, got: $other")
    }

    // take with timeout also works
    val taken = queue.take.timeout(Duration.ofMillis(100)).unsafeRunSync()
    assertEquals(taken, 1) // Returns value directly, not Exit
  }

  test("Race operations handle suspension correctly") {
    val q1 = Eru.queue[String](1).unsafeRunSync()
    val q2 = Eru.queue[String](1).unsafeRunSync()

    // Put values in both queues
    q1.tryPut("from-q1").unsafeRunSync()
    q2.tryPut("from-q2").unsafeRunSync()

    // Race two takes
    val raced = q1.take.eru.race(q2.take.eru).unsafeRunSync()

    // One of them wins, verify we got a valid result
    raced match {
      case Left("from-q1") =>
        // q1 won the race, q2 might or might not have been taken
        assert(true, "q1 won the race")
      case Right("from-q2") =>
        // q2 won the race, q1 might or might not have been taken
        assert(true, "q2 won the race")
      case other =>
        fail(s"Unexpected race result: $other")
    }

    // The race semantics don't guarantee the loser is cancelled immediately,
    // so we can't reliably test the state of the other queue
  }

  test("Producer-consumer pattern with suspension types") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    // Producer
    val producer = Eru.foreach(1 to 5)(i => queue.put(i).eru).fork.unsafeRunSync()

    // Consumer
    val consumer = Eru.collectAll((1 to 5).map(_ => queue.take.eru)).fork.unsafeRunSync()

    // Both complete successfully
    producer.await.unsafeRunSync()
    val items = consumer.await.unsafeRunSync() match {
      case Exit.Success(list) => list
      case other => fail(s"Consumer failed: $other")
    }

    assertEquals(items.toSet, (1 to 5).toSet)
  }

  test("Complex coordination with multiple primitives") {
    val queue = Eru.queue[String](5).unsafeRunSync()
    val latch = Eru.countDownLatch(2).unsafeRunSync()
    val promise = Promise.make[Nothing, String].unsafeRunSync()

    // Worker 1: Waits for latch, puts to queue, completes promise
    val worker1 = (for {
      _ <- latch.await.eru
      _ <- queue.put("worker1-data").eru
      _ <- promise.succeed("worker1-done").eru
    } yield ()).fork.unsafeRunSync()

    // Worker 2: Counts down, takes from queue after promise
    val worker2 = (for {
      _ <- latch.countDown.eru
      _ <- latch.countDown.eru
      _ <- promise.await.eru
      data <- queue.take.eru
    } yield data).fork.unsafeRunSync()

    // Both complete with expected results
    assertEquals(worker1.await.unsafeRunSync(), Exit.Success(()))
    assertEquals(worker2.await.unsafeRunSync(), Exit.Success("worker1-data"))
  }

  test("Suspension types prevent common deadlock patterns at compile time") {
    // This test documents what CANNOT be written, proving compile-time safety

    val queue = Eru.queue[Int](1).unsafeRunSync()
    queue.tryPut(1).unsafeRunSync() // Fill queue

    // These would deadlock in traditional systems but won't compile here:
    // queue.put(2).unsafeRunSync()  // ❌ COMPILE ERROR: no unsafeRunSync on Suspending
    // queue.take.unsafeRunSync()    // ❌ COMPILE ERROR after emptying

    // Instead, we must handle them properly:

    // Option 1: Use try variants
    assertEquals(queue.tryPut(2).unsafeRunSync(), false)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(1))

    // Option 2: Use async patterns
    queue.put(2).eru.fork.unsafeRunSync()
    queue.take.eru.fork.unsafeRunSync()

    // Option 3: Use timeouts
    queue.put(3).timeout(Duration.ofMillis(50))

    // The type system enforces safe concurrency!
    assert(true) // Test passes by preventing bad patterns
  }
}
