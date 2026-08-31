package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for the suspension type system.
  *
  * This suite demonstrates and validates the compile-time safety guarantees provided by Eru's
  * Suspending and Immediate value classes.
  *
  * Race does not guarantee the loser is cancelled immediately, so the state of the losing queue is
  * not asserted.
  */
class SuspensionSystemSpec extends EruTestSuite {

  test("Queue operations follow suspension semantics correctly") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    assertEquals(queue.tryPut(1).unsafeRunSync(), true)
    assertEquals(queue.tryPut(2).unsafeRunSync(), true)
    assertEquals(queue.tryPut(3).unsafeRunSync(), false)
    assertEquals(queue.size.unsafeRunSync(), 2)

    val takeFiber = queue.take.eru.fork.unsafeRunSync()
    val putFiber = queue.put(3).eru.fork.unsafeRunSync()

    assertEquals(takeFiber.await.unsafeRunSync(), Exit.Success(1))
    assertEquals(putFiber.await.unsafeRunSync(), Exit.Success(()))

    assertEquals(queue.size.unsafeRunSync(), 2)
  }

  test("Promise operations follow suspension semantics") {
    val promise = Promise.make[Nothing, String].unsafeRunSync()

    assertEquals(promise.isDone.unsafeRunSync(), false)
    assertEquals(promise.tryGet.unsafeRunSync(), None)

    val awaiter = promise.await.eru.fork.unsafeRunSync()

    assertEquals(promise.succeed("result").unsafeRunSync(), true)
    assertEquals(promise.succeed("again").unsafeRunSync(), false)

    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success("result"))
  }

  test("Semaphore operations follow suspension semantics") {
    val sem = Semaphore.make(1).unsafeRunSync()

    assertEquals(sem.tryAcquire.unsafeRunSync(), true)
    assertEquals(sem.tryAcquire.unsafeRunSync(), false)
    assertEquals(sem.permitsAvailable.unsafeRunSync(), 0L)

    val acquirer = sem.acquire.eru.fork.unsafeRunSync()

    sem.release.unsafeRunSync()

    assertEquals(acquirer.await.unsafeRunSync(), Exit.Success(()))
  }

  test("CountDownLatch operations follow suspension semantics") {
    val latch = Eru.countDownLatch(2).unsafeRunSync()

    assertEquals(latch.count.unsafeRunSync(), 2)
    latch.countDown.unsafeRunSync()
    assertEquals(latch.count.unsafeRunSync(), 1)

    val awaiter = latch.await.eru.fork.unsafeRunSync()

    latch.countDown.unsafeRunSync()
    assertEquals(latch.count.unsafeRunSync(), 0)

    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success(()))
  }

  test("CyclicBarrier operations follow suspension semantics") {
    val barrier = Eru.cyclicBarrier(2).unsafeRunSync()

    assertEquals(barrier.parties.unsafeRunSync(), 2)
    assertEquals(barrier.waiting.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.unsafeRunSync(), false)

    val party1 = barrier.await.eru.fork.unsafeRunSync()

    val party2 = barrier.await.eru.fork.unsafeRunSync()

    assertEquals(party1.await.unsafeRunSync(), Exit.Success(()))
    assertEquals(party2.await.unsafeRunSync(), Exit.Success(()))

    assertEquals(barrier.waiting.unsafeRunSync(), 0)

    assertEquals(barrier.waiting.unsafeRunSync(), 0)
  }

  test("Deferred operations follow suspension semantics") {
    val deferred = Deferred.make[Int].unsafeRunSync()

    assertEquals(deferred.isDone.unsafeRunSync(), false)
    assertEquals(deferred.poll.unsafeRunSync(), None)

    val awaiter = deferred.await.eru.fork.unsafeRunSync()

    assertEquals(deferred.complete(42).unsafeRunSync(), true)
    assertEquals(deferred.complete(99).unsafeRunSync(), false)

    assertEquals(deferred.isDone.unsafeRunSync(), true)
    assertEquals(deferred.poll.unsafeRunSync(), Some(42))
    assertEquals(awaiter.await.unsafeRunSync(), Exit.Success(42))
  }

  test("Hub operations follow suspension semantics") {
    val hub = Eru.hub[String](2).unsafeRunSync()

    val sub1 = hub.subscribe.unsafeRunSync()
    val sub2 = hub.subscribe.unsafeRunSync()
    assertEquals(hub.subscriberCount.unsafeRunSync(), 2)

    hub.publish("msg1").eru.fork.unsafeRunSync().await.unsafeRunSync()

    assertEquals(sub1.take.eru.fork.unsafeRunSync().await.unsafeRunSync(), Exit.Success("msg1"))
    assertEquals(sub2.take.eru.fork.unsafeRunSync().await.unsafeRunSync(), Exit.Success("msg1"))
  }

  test("Timeout converts Suspending to Immediate") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    queue.tryPut(1).unsafeRunSync()

    val putResult: Immediate[Throwable, Unit] = queue.put(2).timeout(Duration.ofMillis(50))

    try {
      putResult.unsafeRunSync()
      fail("Expected timeout exception")
    } catch {
      case _: java.util.concurrent.TimeoutException =>
      case other: Throwable => fail(s"Expected timeout, got: $other")
    }

    val taken = queue.take.timeout(Duration.ofMillis(100)).unsafeRunSync()
    assertEquals(taken, 1)
  }

  test("Race operations handle suspension correctly") {
    val q1 = Eru.queue[String](1).unsafeRunSync()
    val q2 = Eru.queue[String](1).unsafeRunSync()

    q1.tryPut("from-q1").unsafeRunSync()
    q2.tryPut("from-q2").unsafeRunSync()

    val raced = q1.take.eru.race(q2.take.eru).unsafeRunSync()

    raced match {
      case Left("from-q1") =>
        assert(true, "q1 won the race")
      case Right("from-q2") =>
        assert(true, "q2 won the race")
      case other =>
        fail(s"Unexpected race result: $other")
    }
  }

  test("Producer-consumer pattern with suspension types") {
    val queue = Eru.queue[Int](3).unsafeRunSync()

    val producer = Eru.foreach(1 to 5)(i => queue.put(i).eru).fork.unsafeRunSync()

    val consumer = Eru.collectAll((1 to 5).map(_ => queue.take.eru)).fork.unsafeRunSync()

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

    val worker1 = (for {
      _ <- latch.await.eru
      _ <- queue.put("worker1-data").eru
      _ <- promise.succeed("worker1-done").eru
    } yield ()).fork.unsafeRunSync()

    val worker2 = (for {
      _ <- latch.countDown.eru
      _ <- latch.countDown.eru
      _ <- promise.await.eru
      data <- queue.take.eru
    } yield data).fork.unsafeRunSync()

    assertEquals(worker1.await.unsafeRunSync(), Exit.Success(()))
    assertEquals(worker2.await.unsafeRunSync(), Exit.Success("worker1-data"))
  }

  test("suspension types enforce safe alternatives at compile time") {
    val queue = Eru.queue[Int](1).unsafeRunSync()
    queue.tryPut(1).unsafeRunSync()

    assertEquals(queue.tryPut(2).unsafeRunSync(), false)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(1))

    queue.put(2).eru.fork.unsafeRunSync()
    queue.take.eru.fork.unsafeRunSync()

    queue.put(3).timeout(Duration.ofMillis(50)).unsafeRunSync()
  }
}
