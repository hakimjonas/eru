package net.ghoula.eru

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.util.{Failure, Success}

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Comprehensive test suite for H.9.4 async boundary support via LocalEruRuntime.suspend.
  *
  * These tests validate that the suspend mechanism works correctly across different async patterns,
  * error conditions, and integration scenarios while maintaining Eru's correctness guarantees
  * including proper finalizer execution and resource safety.
  */
final class SuspendSpec extends FunSuite {

  // Create a single isolated runtime instance for the entire test class to prevent interference
  private val isolatedRuntime = IsolatedTestRunner.createIsolatedRuntime()

  // Override EruRuntime with isolated instance for this test suite
  private object LocalEruRuntime {
    def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A] =
      isolatedRuntime.suspend(register)
    def timeout[E, A](duration: Duration)(
      effect: Eru[E, A]
    ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      isolatedRuntime.timeout(duration)(effect)
  }

  test("suspend with synchronous callback invocation succeeds immediately") {
    val result = LocalEruRuntime
      .suspend[String, Int] { callback =>
        // Invoke callback synchronously during registration
        callback(Right(42))
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, 42)
  }

  test("suspend with synchronous error callback propagates failure correctly") {
    val result = LocalEruRuntime
      .suspend[String, Int] { callback =>
        // Invoke callback synchronously with error
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
    val future = new CompletableFuture[String]()

    // Start the suspend operation
    val suspendEffect = LocalEruRuntime.suspend[Throwable, String] { callback =>
      future.whenComplete { (value, throwable) =>
        Option(throwable) match {
          case Some(error) => callback(Left(error))
          case None => callback(Right(value))
        }
      }
      Eru.unit
    }

    // Complete the future asynchronously on another thread
    java.util.concurrent.CompletableFuture.runAsync(() => {
      Thread.sleep(10) // Small delay to ensure async behavior
      future.complete("async result")
    })

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, "async result")
  }

  test("suspend with CompletableFuture exception handling propagates errors") {
    val future = new CompletableFuture[String]()

    val suspendEffect = LocalEruRuntime.suspend[Throwable, String] { callback =>
      future.whenComplete { (value, throwable) =>
        Option(throwable) match {
          case Some(error) => callback(Left(error))
          case None => callback(Right(value))
        }
      }
      Eru.unit
    }

    // Complete the future with exception asynchronously
    java.util.concurrent.CompletableFuture.runAsync(() => {
      Thread.sleep(10)
      future.completeExceptionally(new RuntimeException("async error"))
    })

    val result = suspendEffect.attempt.unsafeRunSync()
    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "async error")
      case other => fail(s"Expected RuntimeException but got: $other")
    }
  }

  test("suspend with Scala Future integration handles successful completion") {
    val promise = Promise[Int]()
    val future = promise.future

    val suspendEffect = LocalEruRuntime.suspend[Throwable, Int] { callback =>
      future.onComplete {
        case Success(value) => callback(Right(value))
        case Failure(error) => callback(Left(error))
      }
      Eru.unit
    }

    // Complete the promise asynchronously
    java.util.concurrent.CompletableFuture.runAsync(() => {
      Thread.sleep(10)
      promise.success(123)
    })

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, 123)
  }

  test("suspend with Scala Future failure propagates exceptions correctly") {
    val promise = Promise[Int]()
    val future = promise.future

    val suspendEffect = LocalEruRuntime.suspend[Throwable, Int] { callback =>
      future.onComplete {
        case Success(value) => callback(Right(value))
        case Failure(error) => callback(Left(error))
      }
      Eru.unit
    }

    // Fail the promise asynchronously
    java.util.concurrent.CompletableFuture.runAsync(() => {
      Thread.sleep(10)
      promise.failure(new IllegalArgumentException("future failed"))
    })

    val result = suspendEffect.attempt.unsafeRunSync()
    result match {
      case Result.Failure(t: IllegalArgumentException) => assertEquals(t.getMessage, "future failed")
      case other => fail(s"Expected IllegalArgumentException but got: $other")
    }
  }

  test("suspend prevents multiple callback invocations (idempotency)") {
    var callbackCount = 0
    val future = new CompletableFuture[String]()

    val suspendEffect = LocalEruRuntime.suspend[Throwable, String] { callback =>
      future.whenComplete { (value, throwable) =>
        callbackCount += 1
        Option(throwable) match {
          case Some(error) => callback(Left(error))
          case None => callback(Right(value))
        }

        // Try to invoke callback again (should be ignored)
        Option(throwable) match {
          case Some(error) => callback(Left(error))
          case None => callback(Right("second call"))
        }
      }
      Eru.unit
    }

    // Complete future and verify only first result is used
    java.util.concurrent.CompletableFuture.runAsync(() => {
      Thread.sleep(10)
      future.complete("first result")
    })

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, "first result")
    assertEquals(callbackCount, 1)
  }

  test("suspend handles registration failure correctly") {
    val suspendEffect = LocalEruRuntime.suspend[String, Int] { _ =>
      throw new RuntimeException("registration failed")
    }

    val result = suspendEffect.attempt.unsafeRunSync()
    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "registration failed")
      case other => fail(s"Expected RuntimeException but got: $other")
    }
  }

  test("suspend with finalizers executes cleanup on success") {
    var finalizerExecuted = false

    val effect = EruRuntime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .ensure(Eru.effect { finalizerExecuted = true })

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(finalizerExecuted, "Finalizer should have been executed")
  }

  test("suspend with finalizers executes cleanup on failure") {
    var finalizerExecuted = false

    val effect = EruRuntime
      .suspend[String, Int] { callback =>
        callback(Left("error"))
        Eru.unit
      }
      .ensure(Eru.effect { finalizerExecuted = true })

    val result = effect.attempt.unsafeRunSync()
    result match {
      case Result.Failure("error") => assert(finalizerExecuted, "Finalizer should have been executed")
      case other => fail(s"Expected failure but got: $other")
    }
  }

  test("suspend with nested finalizers maintains FILO execution order") {
    var executionOrder: List[String] = Nil

    val effect = EruRuntime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .ensure(Eru.effect { executionOrder = "outer" :: executionOrder })
      .ensure(Eru.effect { executionOrder = "inner" :: executionOrder })

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assertEquals(executionOrder, List("outer", "inner"))
  }

  test("suspend with timeout integration works correctly") {
    val longRunning = LocalEruRuntime.suspend[String, Int] { callback =>
      // Simulate long async operation that never completes in time
      java.util.concurrent.CompletableFuture.runAsync(() => {
        Thread.sleep(100) // Longer than timeout
        callback(Right(42))
      })
      Eru.unit
    }

    val result = LocalEruRuntime.timeout(Duration.ofMillis(20))(longRunning).attempt.unsafeRunSync()
    result match {
      case Result.Failure(_: java.util.concurrent.TimeoutException) =>
        // Expected timeout
        assert(true)
      case other => fail(s"Expected TimeoutException but got: $other")
    }
  }

  test("suspend with concurrent access remains thread-safe") {
    val iterations = 100
    val results = (1 to iterations).map { i =>
      LocalEruRuntime.suspend[String, Int] { callback =>
        // Simulate concurrent async completion
        java.util.concurrent.CompletableFuture.runAsync(() => {
          Thread.sleep(scala.util.Random.nextInt(5)) // Random small delay
          callback(Right(i))
        })
        Eru.unit
      }
    }

    // Execute all suspend operations and collect results
    val completed = results.map(_.unsafeRunSync()).toList
    assertEquals(completed.sorted, (1 to iterations).toList)
  }
}
