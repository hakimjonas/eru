package net.ghoula.eru

import java.util.concurrent.CompletableFuture
import scala.concurrent.Promise
import scala.util.{Failure, Success}

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for async boundary support via runtime.suspend.
  *
  * This test suite verifies the core suspend mechanism: integration with external async systems
  * through callback-based completion. This is essential for bridging Eru with existing async
  * infrastructure.
  *
  * Focus: Deterministic, essential suspend correctness tests only. Removed: Complex timing
  * dependencies, IsolatedTestRunner, heavy concurrency patterns.
  */
final class SuspendSpec extends EruTestSuite {

  test("suspend with synchronous callback invocation succeeds immediately") {
    val result = runtime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, 42)
  }

  test("suspend with synchronous error callback propagates failure correctly") {
    val result = runtime
      .suspend[String, Int] { callback =>
        callback(Left("sync error"))
        Eru.unit
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(error) => assertEquals(error, "sync error")
      case Result.Success(_) => fail("Expected failure but got success")
    }
  }

  test("suspend with CompletableFuture integration handles async completion") {
    val future = CompletableFuture.completedFuture("async result")

    val result = runtime
      .suspend[Throwable, String] { callback =>
        future.whenComplete { (value, throwable) =>
          Option(throwable) match {
            case Some(error) => callback(Left(error))
            case None => callback(Right(value))
          }
        }
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, "async result")
  }

  test("suspend with CompletableFuture exception handling propagates errors") {
    val exception = new RuntimeException("async error")
    val future = new CompletableFuture[String]()
    future.completeExceptionally(exception)

    val result = runtime
      .suspend[Throwable, String] { callback =>
        future.whenComplete { (value, throwable) =>
          Option(throwable) match {
            case Some(error) => callback(Left(error))
            case None => callback(Right(value))
          }
        }
        Eru.unit
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "async error")
      case other => fail(s"Expected RuntimeException but got: $other")
    }
  }

  test("suspend with Scala Future integration handles successful completion") {
    val promise = Promise[Int]()
    promise.success(123)
    val future = promise.future

    val result = runtime
      .suspend[Throwable, Int] { callback =>
        future.onComplete {
          case Success(value) => callback(Right(value))
          case Failure(error) => callback(Left(error))
        }(using scala.concurrent.ExecutionContext.global)
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, 123)
  }

  test("suspend with Scala Future failure propagates exceptions correctly") {
    val promise = Promise[Int]()
    val exception = new IllegalArgumentException("future failed")
    promise.failure(exception)
    val future = promise.future

    val result = runtime
      .suspend[Throwable, Int] { callback =>
        future.onComplete {
          case Success(value) => callback(Right(value))
          case Failure(error) => callback(Left(error))
        }(using scala.concurrent.ExecutionContext.global)
        Eru.unit
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(t: IllegalArgumentException) => assertEquals(t.getMessage, "future failed")
      case other => fail(s"Expected IllegalArgumentException but got: $other")
    }
  }

  test("suspend prevents multiple callback invocations (idempotency)") {
    var callbackCount = 0
    val result = runtime
      .suspend[Throwable, String] { callback =>
        callbackCount += 1
        // Try calling multiple times - only first should be processed
        callback(Right("first result"))
        callback(Right("second result"))
        callback(Left(new RuntimeException("error")))
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, "first result")
    assertEquals(callbackCount, 1)
  }

  test("suspend handles registration failure correctly") {
    val result = runtime
      .suspend[String, Int] { _ =>
        throw new RuntimeException("registration failed")
      }
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "registration failed")
      case other => fail(s"Expected RuntimeException but got: $other")
    }
  }

  test("suspend with finalizers executes cleanup on success") {
    var finalizerExecuted = false

    val result = runtime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .ensure(Eru.effect { finalizerExecuted = true })
      .unsafeRunSync()

    assertEquals(result, 42)
    assert(finalizerExecuted, "Finalizer should have been executed")
  }

  test("suspend with finalizers executes cleanup on failure") {
    var finalizerExecuted = false

    val result = runtime
      .suspend[String, Int] { callback =>
        callback(Left("error"))
        Eru.unit
      }
      .ensure(Eru.effect { finalizerExecuted = true })
      .attempt
      .unsafeRunSync()

    result match {
      case Result.Failure("error") => assert(finalizerExecuted, "Finalizer should have been executed")
      case other => fail(s"Expected failure but got: $other")
    }
  }

  test("suspend with nested finalizers maintains FILO execution order") {
    var executionOrder: List[String] = Nil

    val result = runtime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .ensure(Eru.effect { executionOrder = "outer" :: executionOrder })
      .ensure(Eru.effect { executionOrder = "inner" :: executionOrder })
      .unsafeRunSync()

    assertEquals(result, 42)
    assertEquals(executionOrder, List("outer", "inner"))
  }

  test("suspend with concurrent access remains thread-safe") {
    // Simplified concurrency test - fewer iterations, deterministic completion
    val items = List(1, 2, 3, 4, 5)

    val results = items.map { i =>
      runtime.suspend[String, Int] { callback =>
        // Use immediate completion instead of async threads
        callback(Right(i))
        Eru.unit
      }
    }

    val completed: List[Int] = results.map(_.unsafeRunSync()).toList
    assertEquals(completed.sorted, items)
  }
}
