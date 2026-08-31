package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Tests that demonstrate compile-time deadlock prevention through the suspension type system.
  *
  * This test suite proves that Eru's suspension types prevent deadlocks at compile time by making
  * it impossible to call blocking operations synchronously.
  *
  * Blocking patterns (acquire, put on a full queue, take on an empty queue, sequential blocking
  * chains) are prevented at compile time because Suspending has no unsafeRunSync. Immediate and
  * Suspending are value classes wrapping Eru, so they erase to the same runtime type and the type
  * distinction costs zero overhead.
  */
class SuspensionSafetySpec extends EruTestSuite {

  test("Immediate operations can be called with unsafeRunSync") {
    val queue = Eru.queue[Int](5).unsafeRunSync()

    assertEquals(queue.tryPut(42).unsafeRunSync(), true)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(42))
    assertEquals(queue.size.unsafeRunSync(), 0)
    assertEquals(queue.isEmpty.unsafeRunSync(), true)
    assertEquals(queue.poll.unsafeRunSync(), None)
    assertEquals(queue.capacity.unsafeRunSync(), Some(5))
  }

  test("Suspending operations require explicit handling") {
    val queue = Eru.queue[String](1).unsafeRunSync()

    assertEquals(queue.tryPut("first").unsafeRunSync(), true)

    val putFiber = queue.put("second").eru.fork.unsafeRunSync()
    assertEquals(queue.tryTake.unsafeRunSync(), Some("first"))
    putFiber.await.unsafeRunSync()

    import java.time.Duration
    val taken = queue.take.timeout(Duration.ofMillis(100)).eru.unsafeRunSync()
    assertEquals(taken, "second")

    assertEquals(queue.tryPut("third").unsafeRunSync(), true)
    val raced = queue.take.eru.race(queue.take.eru).fork.unsafeRunSync().await.unsafeRunSync()
    raced match {
      case Exit.Success(Left("third")) =>
      case Exit.Success(Right("third")) =>
      case other => fail(s"Expected Exit.Success with 'third', got: $other")
    }
  }

  test("Compile-time prevention of common deadlock patterns") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    assertEquals(queue.tryPut(1).unsafeRunSync(), true)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(1))
  }

  test("Value class erasure - zero runtime overhead") {
    val queue = Eru.queue[String](10).unsafeRunSync()

    val suspending: Suspending[Nothing, Unit] = queue.put("test")
    val immediate: Immediate[Nothing, Boolean] = queue.tryPut("test")

    val _: Eru[Nothing, Unit] = suspending.eru
    val _: Eru[Nothing, Boolean] = immediate.eru

    val _: Suspending[Nothing, Boolean] = immediate.suspending
  }

  test("Timeout operations convert Suspending to Immediate") {
    val queue = Eru.queue[Int](1).unsafeRunSync()

    import java.time.Duration
    val timeout = Duration.ofMillis(50)

    val putResult: Immediate[Throwable, Unit] = queue.put(1).timeout(timeout)
    putResult.unsafeRunSync()

    val takeResult: Immediate[Throwable, Int] = queue.take.timeout(timeout)
    assertEquals(takeResult.unsafeRunSync(), 1)
  }

  test("Promise follows same suspension pattern") {
    val promise = Promise.make[Nothing, String].unsafeRunSync()

    promise.await

    val succeeded: Immediate[Nothing, Boolean] = promise.succeed("value")
    succeeded.unsafeRunSync()

    val tried: Immediate[Nothing, Option[Exit[Nothing, String]]] = promise.tryGet
    assertEquals(tried.unsafeRunSync(), Some(Exit.Success("value")))
  }

  test("Semaphore follows suspension pattern") {
    val sem = Semaphore.make(1).unsafeRunSync()

    val _: Suspending[Nothing, Unit] = sem.acquire

    val tried: Immediate[Nothing, Boolean] = sem.tryAcquire
    assertEquals(tried.unsafeRunSync(), true)

    assertEquals(sem.tryAcquire.unsafeRunSync(), false)

    val released: Immediate[Nothing, Unit] = sem.release
    released.unsafeRunSync()

    assertEquals(sem.tryAcquire.unsafeRunSync(), true)
  }

  test("Composition preserves suspension safety") {
    val queue = Eru.queue[Int](2).unsafeRunSync()

    val program: Eru[Nothing, (Int, Int)] = for {
      _ <- queue.put(1).eru
      _ <- queue.put(2).eru
      first <- queue.take.eru
      second <- queue.take.eru
    } yield (first, second)

    val result = program.fork.unsafeRunSync().await.unsafeRunSync()
    result match {
      case Exit.Success((1, 2)) =>
      case other => fail(s"Expected Exit.Success((1, 2)), got: $other")
    }
  }

  test("Real-world deadlock scenario prevented") {
    val queue = Eru.queue[String](1).unsafeRunSync()

    assertEquals(queue.tryPut("message").unsafeRunSync(), true)

    import java.time.Duration

    val producer = queue.put("another").timeout(Duration.ofMillis(50)).eru
    val consumer = queue.take.timeout(Duration.ofMillis(50)).eru

    val result = for {
      consumerFiber <- consumer.fork
      producerFiber <- producer.fork
      consumed <- consumerFiber.await
      _ <- producerFiber.await
    } yield consumed

    result.unsafeRunSync() match {
      case Exit.Success("message") =>
      case other => fail(s"Expected Exit.Success('message'), got: $other")
    }
  }

  test("Type inference works correctly with suspension types") {
    val queue = Eru.queue[Int](5).unsafeRunSync()

    queue.put(42)
    val tryPut = queue.tryPut(42)
    queue.take
    val tryTake = queue.tryTake

    tryPut.unsafeRunSync()
    tryTake.unsafeRunSync()
  }

  test("suspension-safe primitives construct and complete safely") {
    val queue = Eru.queue[Int](1).unsafeRunSync()
    val semaphore = Semaphore.make(1).unsafeRunSync()
    val promise = Promise.make[Nothing, Int].unsafeRunSync()

    assertEquals(queue.tryTake.unsafeRunSync(), None)
    assertEquals(semaphore.withPermitTry(Eru.succeed(42)).unsafeRunSync(), Some(42))
    assert(promise.complete(Eru.succeed(42)).unsafeRunSync())
  }
}
