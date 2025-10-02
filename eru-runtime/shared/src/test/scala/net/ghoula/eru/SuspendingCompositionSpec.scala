package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Comprehensive test suite for Suspending composition methods.
  *
  * Verifies that Suspending values compose correctly through map, flatMap, and other combinators
  * while maintaining suspension safety. Tests ensure that Suspending operations cannot be run with
  * unsafeRunSync (compile-time safety) and must use timeout or fork for execution.
  */
class SuspendingCompositionSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("map transforms the success value") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val doubled: Suspending[Nothing, Int] = queue.take.map(_ * 2)
    val result = doubled.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(result, 84)
  }

  test("map preserves Suspending wrapper") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    queue.tryPut("hello").unsafeRunSync()

    val upperCased: Suspending[Nothing, String] = queue.take.map(_.toUpperCase)

    // Must use timeout - no unsafeRunSync on Suspending
    val result = upperCased.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, "HELLO")
  }

  test("flatMap chains suspending computations") {
    val queue1 = Eru.queue[Int](10).unsafeRunSync()
    val queue2 = Eru.queue[Int](10).unsafeRunSync()

    queue1.tryPut(42).unsafeRunSync()

    val program: Suspending[Nothing, Unit] =
      queue1.take.flatMap(item => queue2.put(item * 2).eru)

    program.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    val result = queue2.tryTake.unsafeRunSync()
    assertEquals(result, Some(84))
  }

  test("flatMap composes with for-comprehension") {
    val queue1 = Eru.queue[String](10).unsafeRunSync()
    val queue2 = Eru.queue[String](10).unsafeRunSync()

    queue1.tryPut("hello").unsafeRunSync()

    val program: Eru[Nothing, String] = for {
      item <- queue1.take.eru
      processed = item.toUpperCase
      _ <- queue2.put(processed).eru
    } yield processed

    val result = program.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, "HELLO")
    assertEquals(queue2.tryTake.unsafeRunSync(), Some("HELLO"))
  }

  test("zip combines two suspending computations") {
    val queue1 = Eru.queue[Int](10).unsafeRunSync()
    val queue2 = Eru.queue[String](10).unsafeRunSync()

    queue1.tryPut(42).unsafeRunSync()
    queue2.tryPut("answer").unsafeRunSync()

    val combined = queue1.take.zip(queue2.take)
    val (num, str) = combined.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(num, 42)
    assertEquals(str, "answer")
  }

  test("orElse provides fallback on failure") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("error").unsafeRunSync()

    val failing: Suspending[String, Int] = promise.await
    val fallback: Suspending[String, Int] = new Suspending(Eru.succeed(99))

    val result = failing.orElse(fallback).timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, 99)
  }

  test("orElse returns first value on success") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(42).unsafeRunSync()

    val success: Suspending[String, Int] = promise.await
    val fallback: Suspending[String, Int] = new Suspending(Eru.succeed(99))

    val result = success.orElse(fallback).timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("recover handles typed errors") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("oops").unsafeRunSync()

    val recovered = promise.await.recover { case "oops" => 88 }
    val result = recovered.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(result, 88)
  }

  test("recover leaves unmatched errors") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("oops").unsafeRunSync()

    val recovered = promise.await.recover { case "different" => 88 }

    interceptMessage[EruException[String]]("oops") {
      recovered.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    }
  }

  test("recoverWith handles errors with effects") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("error").unsafeRunSync()

    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(77).unsafeRunSync()

    val recovered = promise.await.recoverWith { case "error" => queue.take.eru }
    val result = recovered.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(result, 77)
  }

  test("attempt converts errors to Result") {
    val successPromise = Eru.promise[String, Int].unsafeRunSync()
    successPromise.succeed(42).unsafeRunSync()

    val successResult = successPromise.await.attempt.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(successResult, Result.Success(42))

    val failurePromise = Eru.promise[String, Int].unsafeRunSync()
    failurePromise.fail("error").unsafeRunSync()

    val failureResult = failurePromise.await.attempt.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(failureResult, Result.Failure("error"))
  }

  test("attempt eliminates error channel") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("error").unsafeRunSync()

    val attempted: Suspending[Nothing, Result[String, Int]] = promise.await.attempt

    // Should not throw - error is captured in Result
    val result = attempted.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("race completes with first winner") {
    val fast = Eru.queue[Int](10).unsafeRunSync()
    val slow = Eru.queue[String](10).unsafeRunSync()

    fast.tryPut(1).unsafeRunSync()

    val raced = fast.take.race(slow.take)
    val result = raced.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(result, Left(1))
  }

  test("timeout succeeds if computation completes in time") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    queue.tryPut("fast").unsafeRunSync()

    val result = queue.take.timeout(Duration.ofSeconds(1)).unsafeRunSync()
    assertEquals(result, "fast")
  }

  test("Functor law: map identity") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val original = queue.take
    val mapped = original.map(identity)

    val originalResult = original.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    // Refill for second test
    queue.tryPut(42).unsafeRunSync()
    val mappedResult = mapped.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(originalResult, mappedResult)
  }

  test("Functor law: map composition") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    val f: Int => Int = _ * 2
    val g: Int => Int = _ + 5

    queue.tryPut(10).unsafeRunSync()
    val composedResult = queue.take.map(f.andThen(g)).timeout(Duration.ofSeconds(1)).unsafeRunSync()

    queue.tryPut(10).unsafeRunSync()
    val sequentialResult = queue.take.map(f).map(g).timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(composedResult, sequentialResult)
  }

  test("Monad law: left identity") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    val a = 42
    val f: Int => Eru[Nothing, Int] = x => queue.put(x).map(_ => x * 2).eru

    val left = new Suspending(Eru.succeed(a)).flatMap(f).timeout(Duration.ofSeconds(1)).unsafeRunSync()
    val right = f(a).timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("Monad law: right identity") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val original = queue.take
    val leftSide = original.flatMap(a => new Suspending(Eru.succeed(a)).eru)

    val originalResult = original.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    queue.tryPut(42).unsafeRunSync()
    val leftResult = leftSide.timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(originalResult, leftResult)
  }

  test("Monad law: associativity") {
    val queue1 = Eru.queue[Int](10).unsafeRunSync()
    val queue2 = Eru.queue[Int](10).unsafeRunSync()

    val m = queue1.take
    val f: Int => Eru[Nothing, Int] = x => queue2.put(x).map(_ => x * 2).eru
    val g: Int => Eru[Nothing, Int] = x => Eru.succeed(x + 10)

    queue1.tryPut(5).unsafeRunSync()
    val left = m.flatMap(f).flatMap(g).timeout(Duration.ofSeconds(1)).unsafeRunSync()

    queue1.tryPut(5).unsafeRunSync()
    val right = m.flatMap(a => new Suspending(f(a)).flatMap(g).eru).timeout(Duration.ofSeconds(1)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("fork allows async execution of Suspending") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val program = for {
      fiber <- queue.take.fork
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val result = program.unsafeRunSync()
    assertEquals(result, 42)
  }
}
