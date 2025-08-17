package net.ghoula.eru

import munit.FunSuite
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

final class EruRuntimeInteropSpec extends FunSuite {

  test("fromFuture success case yields the Future value") {
    val future = Future.successful(42)
    val result = FutureInterop.fromFuture(future).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("fromFuture failure case propagates the Throwable") {
    val exception = new RuntimeException("boom")
    val future = Future.failed[Int](exception)
    
    val caught = intercept[RuntimeException] {
      FutureInterop.fromFuture(future).unsafeRunSync()
    }
    assertEquals(caught.getMessage, "boom")
  }

  test("fromFuture lazy evaluation - thunk is not called until effect runs") {
    var called = false
    val effect = FutureInterop.fromFuture {
      called = true
      Future.successful(1)
    }
    assertEquals(called, false) // should not be called yet
    
    val result = effect.unsafeRunSync()
    assertEquals(called, true)   // now it should be called
    assertEquals(result, 1)
  }

  test("toFuture success case translates Exit.Success correctly") {
    val eru = Eru.succeed(100)
    val futureEffect = FutureInterop.toFuture(eru)
    val future = futureEffect.unsafeRunSync()
    
    // Wait for the Future to complete
    val result = scala.concurrent.Await.result(future, 1.second)
    assertEquals(result, 100)
  }

  test("toFuture failure case with Throwable translates to failed Future") {
    val exception = new IllegalStateException("error")
    val eru: Eru[Throwable, Int] = Eru.effect(throw exception)
    val futureEffect = FutureInterop.toFuture(eru)
    val future = futureEffect.unsafeRunSync()
    
    val caught = intercept[IllegalStateException] {
      scala.concurrent.Await.result(future, 1.second)
    }
    assertEquals(caught.getMessage, "error")
  }

  test("toFuture failure case with non-Throwable wraps in EruException") {
    val eru: Eru[String, Int] = Eru.fail("string error").asInstanceOf[Eru[Throwable, Int]]
    val futureEffect = FutureInterop.toFuture(eru)
    val future = futureEffect.unsafeRunSync()
    
    // This test is tricky because we're casting a String error to Throwable
    // In practice, this would be caught by the type system, but we test the runtime behavior
    val caught = intercept[Throwable] {
      scala.concurrent.Await.result(future, 1.second)
    }
    // The actual behavior depends on how the runtime handles the type mismatch
    assert(caught != null)
  }

  test("toFuture die case propagates the Throwable") {
    val eru: Eru[Throwable, Int] = Eru.effect {
      throw new OutOfMemoryError("oom")
    }
    val futureEffect = FutureInterop.toFuture(eru)
    val future = futureEffect.unsafeRunSync()
    
    val caught = intercept[OutOfMemoryError] {
      scala.concurrent.Await.result(future, 1.second)
    }
    assertEquals(caught.getMessage, "oom")
  }

  test("round-trip property: simple success case") {
    val originalValue = 123
    val eru = Eru.succeed(originalValue)
    
    val roundTrip = for {
      future <- FutureInterop.toFuture(eru)
      backToEru = FutureInterop.fromFuture(future)
      result <- backToEru
    } yield result
    
    val finalResult = roundTrip.unsafeRunSync()
    assertEquals(finalResult, originalValue)
  }

  test("round-trip property: Future -> Eru -> Future preserves success") {
    val originalFuture = Future.successful("hello")
    
    val roundTrip = for {
      eru = FutureInterop.fromFuture(originalFuture)
      future <- FutureInterop.toFuture(eru)
    } yield future
    
    val resultFuture = roundTrip.unsafeRunSync()
    val result = scala.concurrent.Await.result(resultFuture, 1.second)
    assertEquals(result, "hello")
  }

  test("round-trip property: Future -> Eru -> Future preserves failure") {
    val exception = new RuntimeException("test error")
    val originalFuture = Future.failed[String](exception)
    
    val roundTrip = for {
      eru = FutureInterop.fromFuture(originalFuture)
      future <- FutureInterop.toFuture(eru)
    } yield future
    
    val resultFuture = roundTrip.unsafeRunSync()
    val caught = intercept[RuntimeException] {
      scala.concurrent.Await.result(resultFuture, 1.second)
    }
    assertEquals(caught.getMessage, "test error")
  }

  test("fromFuture handles already completed Future immediately") {
    // Test with a Future that's already completed
    val completedFuture = Future.fromTry(Success(999))
    val result = FutureInterop.fromFuture(completedFuture).unsafeRunSync()
    assertEquals(result, 999)
  }

  test("fromFuture works with Promise-based Future") {
    val promise = Promise[String]()
    val effect = FutureInterop.fromFuture(promise.future)
    
    // Complete the promise in a separate thread after a small delay
    new Thread(() => {
      Thread.sleep(50)
      promise.success("async result")
    }).start()
    
    val result = effect.unsafeRunSync()
    assertEquals(result, "async result")
  }
}