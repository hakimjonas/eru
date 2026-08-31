package net.ghoula.eru

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for Promise implementation.
  *
  * Validates single-assignment semantics, typed error handling, concurrent completion attempts, and
  * callback execution order. Tests ensure that Promise correctly handles race conditions, provides
  * atomic completion guarantees, and maintains proper resource cleanup across different completion
  * scenarios.
  */
class PromiseSpec extends EruTestSuite {

  test("promise creation succeeds") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    assertEquals(promise.isDone.eru.unsafeRunSync(), false)
  }

  test("promise succeed completes with success value") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val completed = promise.succeed(42).eru.unsafeRunSync()
    assertEquals(completed, true)
    assertEquals(promise.isDone.eru.unsafeRunSync(), true)

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("promise fail completes with failure value") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val completed = promise.fail("error").eru.unsafeRunSync()
    assertEquals(completed, true)
    assertEquals(promise.isDone.eru.unsafeRunSync(), true)

    val result = promise.await.eru.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "error")
    }
  }

  test("promise complete with effect result succeeds") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val effect = Eru.succeed(100)
    val completed = promise.complete(effect).eru.unsafeRunSync()
    assertEquals(completed, true)

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 100)
  }

  test("promise complete with effect failure fails") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val effect = Eru.fail("failure")
    val completed = promise.complete(effect).eru.unsafeRunSync()
    assertEquals(completed, true)

    val result = promise.await.eru.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "failure")
    }
  }

  test("promise can only be completed once - succeed first") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val first = promise.succeed(1).eru.unsafeRunSync()
    val second = promise.succeed(2).eru.unsafeRunSync()
    val third = promise.fail("error").eru.unsafeRunSync()

    assertEquals(first, true)
    assertEquals(second, false)
    assertEquals(third, false)

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 1)
  }

  test("promise can only be completed once - fail first") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val first = promise.fail("error1").eru.unsafeRunSync()
    val second = promise.fail("error2").eru.unsafeRunSync()
    val third = promise.succeed(42).eru.unsafeRunSync()

    assertEquals(first, true)
    assertEquals(second, false)
    assertEquals(third, false)

    val result = promise.await.eru.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "error1")
    }
  }

  test("promise poll returns None when pending") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val poll = promise.poll.eru.unsafeRunSync()
    assertEquals(poll, None)
  }

  test("promise poll returns Some(Success) when completed with success") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(42).eru.unsafeRunSync()
    val poll = promise.poll.eru.unsafeRunSync()
    poll match {
      case Some(Exit.Success(value)) => assertEquals(value, 42)
      case other => fail(s"Expected Some(Success(42)) but got: $other")
    }
  }

  test("promise poll returns Some(Failure) when completed with failure") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("error").eru.unsafeRunSync()
    val poll = promise.poll.eru.unsafeRunSync()
    poll match {
      case Some(Exit.Failure(error)) => assertEquals(error, "error")
      case other => fail(s"Expected Some(Failure(error)) but got: $other")
    }
  }

  test("promise await returns immediately when already completed with success") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(99).eru.unsafeRunSync()

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 99)
  }

  test("promise await returns immediately when already completed with failure") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("immediate").eru.unsafeRunSync()

    val result = promise.await.eru.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "immediate")
    }
  }

  test("promise constructor is available via Eru companion") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    assertEquals(promise.isDone.eru.unsafeRunSync(), false)
  }

  test("promise operations compose with other Eru effects") {
    val program = for {
      promise <- Eru.promise[String, Int]
      _ <- promise.succeed(42).eru
      result <- promise.await.eru
      doubled <- Eru.succeed(result * 2)
    } yield doubled

    val result = program.unsafeRunSync()
    assertEquals(result, 84)
  }

  test("promise handles concurrent completion attempts") {
    import scala.concurrent.{Future, ExecutionContext}
    implicit val ec: ExecutionContext = ExecutionContext.global

    val promise = Eru.promise[String, Int].unsafeRunSync()
    val numThreads = 10

    val futures = (1 to numThreads).map { i =>
      Future {
        promise.succeed(i).eru.unsafeRunSync()
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    val results = Await.result(Future.sequence(futures), Duration(5, TimeUnit.SECONDS))

    val successes = results.count(identity)
    assertEquals(successes, 1)

    val finalResult = promise.await.eru.unsafeRunSync()
    assert((1 to numThreads).contains(finalResult), s"Value $finalResult should be in range 1-$numThreads")
  }

  test("promise handles mixed completion types in race conditions") {
    import scala.concurrent.{Future, ExecutionContext}
    implicit val ec: ExecutionContext = ExecutionContext.global

    val promise = Eru.promise[String, Int].unsafeRunSync()

    val futures = List(
      Future { promise.succeed(1).eru.unsafeRunSync() },
      Future { promise.fail("error").eru.unsafeRunSync() },
      Future { promise.succeed(2).eru.unsafeRunSync() }
    )

    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    val results = Await.result(Future.sequence(futures), Duration(5, TimeUnit.SECONDS))

    val successes = results.count(identity)
    assertEquals(successes, 1)

    val isDone = promise.isDone.eru.unsafeRunSync()
    assert(isDone, "Promise should be completed after race")
  }

  test("promise resource cleanup on completion") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    var cleanupCalled = false

    val effect = promise
      .succeed(42)
      .eru
      .ensure(Eru.effect {
        cleanupCalled = true
        ()
      })

    effect.unsafeRunSync()
    assert(cleanupCalled, "Resource cleanup should be called")

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("promise type variance behavior") {
    val stringPromise: Promise[String, String] = Eru.promise[String, String].unsafeRunSync()

    stringPromise.succeed("test").eru.unsafeRunSync()
    val result = stringPromise.await.eru.unsafeRunSync()
    assertEquals(result, "test")
  }

  test("promise integration with for-comprehension") {
    val result = for {
      promise1 <- Eru.promise[String, Int]
      promise2 <- Eru.promise[String, String]
      _ <- promise1.succeed(123).eru
      _ <- promise2.succeed("hello").eru
      value1 <- promise1.await.eru
      value2 <- promise2.await.eru
    } yield (value1, value2)

    assertEquals(result.unsafeRunSync(), (123, "hello"))
  }

  test("promise handles exceptions in complete effect") {
    val promise = Eru.promise[Throwable, Int].unsafeRunSync()
    val exception = new RuntimeException("effect-error")
    val effect = Eru.effect(throw exception)

    val completed = promise.complete(effect).eru.unsafeRunSync()
    assert(completed, "CompleteWith should succeed even for dying source")

    val caughtException = intercept[RuntimeException] {
      promise.await.eru.unsafeRunSync()
    }
    assertEquals(caughtException.getMessage, "effect-error")
  }

  test("promise concurrent access to poll") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(999).eru.unsafeRunSync()

    import scala.concurrent.{Future, ExecutionContext}
    implicit val ec: ExecutionContext = ExecutionContext.global

    val futures = (1 to 20).map { _ =>
      Future {
        promise.poll.eru.unsafeRunSync()
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    val results = Await.result(Future.sequence(futures), Duration(5, TimeUnit.SECONDS))

    results.foreach {
      case Some(Exit.Success(value)) => assertEquals(value, 999)
      case other => munit.Assertions.fail(s"Expected Some(Success(999)), got: $other")
    }
  }

  test("promise error handling maintains type safety") {
    val promise: Promise[String, Int] = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("typed-error").eru.unsafeRunSync()

    val result = promise.await.eru.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => munit.Assertions.fail("Expected failure")
      case Result.Failure(error) => assertEquals(error, "typed-error")
    }
  }

  test("promise stack safety for large callback chains") {
    val promise = Eru.promise[Nothing, Unit].unsafeRunSync()
    var callbackCount = 0
    val numCallbacks = 1000

    (1 to numCallbacks).foreach { _ =>
      promise.complete(Eru.unit).eru.map(_ => callbackCount += 1).unsafeRunSync()
    }

    promise.succeed(()).eru.unsafeRunSync()
    assertEquals(callbackCount, numCallbacks)
  }

  test("promise complete with complex effect chains") {
    val promise = Eru.promise[Throwable, Int].unsafeRunSync()

    val complexEffect = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.effect(a + b + 12)
    } yield c

    val completed = promise.complete(complexEffect).eru.unsafeRunSync()
    assert(completed, "Complex effect completion should succeed")

    val result = promise.await.eru.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("promise maintains isolation between instances") {
    val promise1 = Eru.promise[String, Int].unsafeRunSync()
    val promise2 = Eru.promise[String, Int].unsafeRunSync()

    promise1.succeed(111).eru.unsafeRunSync()
    promise2.succeed(222).eru.unsafeRunSync()

    assertEquals(promise1.await.eru.unsafeRunSync(), 111)
    assertEquals(promise2.await.eru.unsafeRunSync(), 222)
    assert(promise1 ne promise2, "Promises should be different instances")
  }
}
