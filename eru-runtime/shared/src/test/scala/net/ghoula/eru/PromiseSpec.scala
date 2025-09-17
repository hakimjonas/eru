package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

class PromiseSpec extends EruTestSuite {

  test("promise creation succeeds") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    assertEquals(promise.isDone.unsafeRunSync(), false)
  }

  test("promise succeed completes with success value") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val completed = promise.succeed(42).unsafeRunSync()
    assertEquals(completed, true)
    assertEquals(promise.isDone.unsafeRunSync(), true)

    val result = promise.await.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("promise fail completes with failure value") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val completed = promise.fail("error").unsafeRunSync()
    assertEquals(completed, true)
    assertEquals(promise.isDone.unsafeRunSync(), true)

    val result = promise.await.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "error")
    }
  }

  test("promise complete with effect result succeeds") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val effect = Eru.succeed(100)
    val completed = promise.complete(effect).unsafeRunSync()
    assertEquals(completed, true)

    val result = promise.await.unsafeRunSync()
    assertEquals(result, 100)
  }

  test("promise complete with effect failure fails") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val effect = Eru.fail("failure")
    val completed = promise.complete(effect).unsafeRunSync()
    assertEquals(completed, true)

    val result = promise.await.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "failure")
    }
  }

  test("promise can only be completed once - succeed first") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val first = promise.succeed(1).unsafeRunSync()
    val second = promise.succeed(2).unsafeRunSync()
    val third = promise.fail("error").unsafeRunSync()

    assertEquals(first, true)
    assertEquals(second, false)
    assertEquals(third, false)

    val result = promise.await.unsafeRunSync()
    assertEquals(result, 1)
  }

  test("promise can only be completed once - fail first") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val first = promise.fail("error1").unsafeRunSync()
    val second = promise.fail("error2").unsafeRunSync()
    val third = promise.succeed(42).unsafeRunSync()

    assertEquals(first, true)
    assertEquals(second, false)
    assertEquals(third, false)

    val result = promise.await.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "error1")
    }
  }

  test("promise poll returns None when pending") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val poll = promise.poll.unsafeRunSync()
    assertEquals(poll, None)
  }

  test("promise poll returns Some(Success) when completed with success") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(42).unsafeRunSync()
    val poll = promise.poll.unsafeRunSync()
    poll match {
      case Some(Exit.Success(value)) => assertEquals(value, 42)
      case other => fail(s"Expected Some(Success(42)) but got: $other")
    }
  }

  test("promise poll returns Some(Failure) when completed with failure") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("error").unsafeRunSync()
    val poll = promise.poll.unsafeRunSync()
    poll match {
      case Some(Exit.Failure(error)) => assertEquals(error, "error")
      case other => fail(s"Expected Some(Failure(error)) but got: $other")
    }
  }

  test("promise await returns immediately when already completed with success") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.succeed(99).unsafeRunSync()

    val result = promise.await.unsafeRunSync()
    assertEquals(result, 99)
  }

  test("promise await returns immediately when already completed with failure") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("immediate").unsafeRunSync()

    val result = promise.await.attempt.unsafeRunSync()
    result match {
      case Result.Success(_) => fail("Expected failure but got success")
      case Result.Failure(error) => assertEquals(error, "immediate")
    }
  }

  test("promise constructor is available via Eru companion") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    assertEquals(promise.isDone.unsafeRunSync(), false)
  }

  test("promise operations compose with other Eru effects") {
    val program = for {
      promise <- Eru.promise[String, Int]
      _ <- promise.succeed(42)
      result <- promise.await
      doubled <- Eru.succeed(result * 2)
    } yield doubled

    val result = program.unsafeRunSync()
    assertEquals(result, 84)
  }
}
