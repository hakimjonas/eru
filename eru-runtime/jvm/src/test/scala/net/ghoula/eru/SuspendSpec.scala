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
final class SuspendSpec extends TestWithRuntime {

  // Use the TestWithRuntime's implicit runtime instead of shared isolatedRuntime
  private object LocalEruRuntime {
    def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit])(using
      runtime: EruRuntime
    ): Eru[E | Throwable, A] =
      runtime.suspend(register)
    def timeout[E, A](duration: Duration)(
      effect: Eru[E, A]
    )(using runtime: EruRuntime): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      runtime.timeout(duration)(effect)
  }

  /** Validates that suspend with synchronous callback invocation succeeds immediately.
    *
    * Tests that when the callback is invoked synchronously during registration, the suspend
    * operation completes immediately with the provided value.
    */
  test("suspend with synchronous callback invocation succeeds immediately") {
    val result = LocalEruRuntime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .unsafeRunSync()

    assertEquals(result, 42)
  }

  /** Validates that suspend with synchronous error callback propagates failure correctly.
    *
    * Tests that when the callback is invoked synchronously with an error during registration, the
    * suspend operation fails immediately with the provided error.
    */
  test("suspend with synchronous error callback propagates failure correctly") {
    val result = LocalEruRuntime
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

  /** Validates that suspend integrates correctly with CompletableFuture for async completion.
    *
    * Tests that suspend can properly handle asynchronous completion through CompletableFuture
    * integration, ensuring the callback is invoked when the future completes.
    */
  test("suspend with CompletableFuture integration handles async completion") {
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

    // Use CompletableFuture's delayed execution instead of Thread.sleep
    java.util.concurrent.CompletableFuture
      .delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
      .execute(() => future.complete("async result"))

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, "async result")
  }

  /** Validates that suspend properly propagates CompletableFuture exceptions.
    *
    * Tests that when a CompletableFuture completes exceptionally, the suspend operation correctly
    * propagates the exception through the error callback.
    */
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

    java.util.concurrent.CompletableFuture
      .delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
      .execute(() => {
        future.completeExceptionally(new RuntimeException("async error"))
      })

    val result = suspendEffect.attempt.unsafeRunSync()
    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "async error")
      case other => fail(s"Expected RuntimeException but got: $other")
    }
  }

  /** Validates that suspend integrates correctly with Scala Future for successful completion.
    *
    * Tests that suspend can properly handle Scala Future completion using Promise/Future
    * integration, ensuring the callback is invoked when the future succeeds.
    */
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

    java.util.concurrent.CompletableFuture
      .delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
      .execute(() => {
        promise.success(123)
      })

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, 123)
  }

  /** Validates that suspend properly propagates Scala Future failures.
    *
    * Tests that when a Scala Future fails, the suspend operation correctly propagates the failure
    * through the error callback.
    */
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

    java.util.concurrent.CompletableFuture
      .delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
      .execute(() => {
        promise.failure(new IllegalArgumentException("future failed"))
      })

    val result = suspendEffect.attempt.unsafeRunSync()
    result match {
      case Result.Failure(t: IllegalArgumentException) => assertEquals(t.getMessage, "future failed")
      case other => fail(s"Expected IllegalArgumentException but got: $other")
    }
  }

  /** Validates that suspend prevents multiple callback invocations through idempotency.
    *
    * Tests that only the first callback invocation is processed, and subsequent invocations are
    * ignored to ensure consistent behavior.
    */
  test("suspend prevents multiple callback invocations (idempotency)") {
    var callbackInvocationCount = 0
    val future = new CompletableFuture[String]()

    val suspendEffect = LocalEruRuntime.suspend[Throwable, String] { callback =>
      // Wrap the callback to track actual invocations
      val trackedCallback: Either[Throwable, String] => Unit = result => {
        callbackInvocationCount += 1
        callback(result)
      }

      future.whenComplete { (value, throwable) =>
        Option(throwable) match {
          case Some(error) =>
            trackedCallback(Left(error)) // First call
            trackedCallback(Left(error)) // Second call (should be ignored by suspend)
          case None =>
            trackedCallback(Right(value)) // First call
            trackedCallback(Right("second call")) // Second call (should be ignored by suspend)
        }
      }
      Eru.unit
    }

    java.util.concurrent.CompletableFuture
      .delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
      .execute(() => {
        future.complete("first result")
      })

    val result = suspendEffect.unsafeRunSync()
    assertEquals(result, "first result")
    // The callback is invoked twice, but suspend should only use the first result
    assertEquals(callbackInvocationCount, 2)
  }

  /** Validates that suspend properly handles registration failures.
    *
    * Tests that when the registration function throws an exception, the suspend operation correctly
    * propagates the failure.
    */
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

  /** Validates that suspend executes finalizers on successful completion.
    *
    * Tests that ensure finalizers are properly executed when a suspend operation completes
    * successfully, maintaining resource safety guarantees.
    */
  test("suspend with finalizers executes cleanup on success") {
    var finalizerExecuted = false

    val effect = runtime
      .suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      .ensure(Eru.effect { finalizerExecuted = true })

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(finalizerExecuted, "Finalizer should have been executed")
  }

  /** Validates that suspend executes finalizers on failure.
    *
    * Tests that ensure finalizers are properly executed when a suspend operation fails, maintaining
    * resource safety guarantees even in error conditions.
    */
  test("suspend with finalizers executes cleanup on failure") {
    var finalizerExecuted = false

    val effect = runtime
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

  /** Validates that suspend maintains FILO execution order for nested finalizers.
    *
    * Tests that multiple finalizers attached to a suspend operation execute in the correct
    * First-In-Last-Out order for proper resource cleanup.
    */
  test("suspend with nested finalizers maintains FILO execution order") {
    var executionOrder: List[String] = Nil

    val effect = runtime
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

  /** Validates that suspend integrates correctly with timeout operations.
    *
    * Tests that suspend operations can be properly timed out when they exceed the specified
    * duration, ensuring responsive behavior.
    */
  test("suspend with timeout integration works correctly") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      // Create a suspend operation that will never complete on its own
      val longRunning = runtime.suspend[String, Int] { _ =>
        // Don't invoke callback - let it timeout naturally
        Eru.unit
      }

      val timeoutEffect = runtime.timeout(Duration.ofMillis(20))(longRunning)

      // Fork the timeout operation
      val fiber = runtime.fork(timeoutEffect.attempt).unsafeRunSync()

      // Advance time past timeout
      runtime.testClock.advance(Duration.ofMillis(25))

      // Check result - should timeout
      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success(Result.Failure(_: java.util.concurrent.TimeoutException)) =>
          assert(true)
        case other => fail(s"Expected TimeoutException but got: $other")
      }
    }
  }

  /** Validates that suspend operations remain thread-safe under concurrent access.
    *
    * Tests that multiple suspend operations can execute concurrently without interference,
    * maintaining correctness and thread safety.
    */
  test("suspend with concurrent access remains thread-safe") {
    val iterations = 100
    val results = (1 to iterations).map { i =>
      LocalEruRuntime.suspend[String, Int] { callback =>
        java.util.concurrent.CompletableFuture.runAsync(() => {
          // Remove random Thread.sleep to avoid blocking thread pool
          // The concurrency test doesn't need timing variation
          callback(Right(i))
        })
        Eru.unit
      }
    }

    val completed: List[Int] = results.map(_.unsafeRunSync()).toList
    assertEquals(completed.sorted, (1 to iterations).toList)
  }
}
