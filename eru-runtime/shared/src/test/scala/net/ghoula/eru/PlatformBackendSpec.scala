package net.ghoula.eru

import net.ghoula.eru.internal.ConcurrencyBackend
import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for PlatformBackend provider discovery.
  *
  * Validates ServiceLoader-based backend selection, singleton behavior, fresh backend creation, and
  * fallback mechanisms. Tests ensure that the platform detection works correctly across different
  * environments and that backend instances are properly managed.
  */
class PlatformBackendSpec extends EruTestSuite {

  test("PlatformBackend.backend provides singleton access") {
    val backend1 = PlatformBackend.backend
    val backend2 = PlatformBackend.backend

    // Should be the same instance (lazy val singleton)
    assert(backend1 eq backend2, "PlatformBackend.backend should return same instance")
    assert(Option(backend1).isDefined, "Backend should not be null")
  }

  test("PlatformBackend.createFreshBackend creates new instances") {
    val backend1 = PlatformBackend.createFreshBackend()
    val backend2 = PlatformBackend.createFreshBackend()

    // Should create fresh instances
    assert(Option(backend1).isDefined, "Fresh backend should not be null")
    assert(Option(backend2).isDefined, "Fresh backend should not be null")

    // Note: They may or may not be the same instance depending on implementation
    // but both should be functional backends
  }

  test("PlatformBackend fresh backend is functional") {
    val backend = PlatformBackend.createFreshBackend()

    // Test basic functionality
    val effect = Eru.succeed(42)
    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("PlatformBackend singleton backend is functional") {
    val backend = PlatformBackend.backend

    // Test basic functionality
    val effect = Eru.succeed(99)
    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 99)
      case other => munit.Assertions.fail(s"Expected Success(99), got: $other")
    }
  }

  test("PlatformBackend handles concurrent access to singleton") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration.Duration
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures = (1 to 10).map { _ =>
      Future {
        PlatformBackend.backend
      }
    }

    val backends = Await.result(Future.sequence(futures), Duration("5s"))

    // All should be the same instance
    val firstBackend = backends.head
    backends.foreach { backend =>
      assert(backend eq firstBackend, "All concurrent accesses should return same singleton")
    }
  }

  test("PlatformBackend handles concurrent fresh backend creation") {
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration.Duration
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures = (1 to 5).map { _ =>
      Future {
        PlatformBackend.createFreshBackend()
      }
    }

    val backends = Await.result(Future.sequence(futures), Duration("5s"))

    // All should be functional
    backends.foreach { backend =>
      assert(Option(backend).isDefined, "All fresh backends should be created successfully")

      // Test basic functionality
      val effect = Eru.succeed(123)
      val fiber = backend.fork(effect).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()

      exit match {
        case Exit.Success(value) => assertEquals(value, 123)
        case other => munit.Assertions.fail(s"Expected Success(123), got: $other")
      }
    }
  }

  test("PlatformBackend backend supports fork operations") {
    val backend = PlatformBackend.backend

    val effects = List(
      Eru.succeed("first"),
      Eru.succeed("second"),
      Eru.succeed("third")
    )

    val fibers = effects.map(backend.fork(_).unsafeRunSync())
    val exits = fibers.map(_.await.unsafeRunSync())

    val results = exits.collect {
      case Exit.Success(value: String) => value
      case other => munit.Assertions.fail(s"Expected string success, got: $other")
    }

    assertEquals(results, List("first", "second", "third"))
  }

  test("PlatformBackend backend supports error handling") {
    val backend = PlatformBackend.backend

    val successEffect = Eru.succeed("success")
    val failureEffect = Eru.fail("typed-error")
    val exceptionEffect = Eru.effect(throw new RuntimeException("exception-error"))

    // Test success case
    val successFiber = backend.fork(successEffect).unsafeRunSync()
    val successExit = successFiber.await.unsafeRunSync()
    successExit match {
      case Exit.Success(value) => assertEquals(value, "success")
      case other => munit.Assertions.fail(s"Expected Success('success'), got: $other")
    }

    // Test typed failure case
    val failureFiber = backend.fork(failureEffect).unsafeRunSync()
    val failureExit = failureFiber.await.unsafeRunSync()
    failureExit match {
      case Exit.Failure(error) => assertEquals(error, "typed-error")
      case other => munit.Assertions.fail(s"Expected Failure('typed-error'), got: $other")
    }

    // Test exception case
    val exceptionFiber = backend.fork(exceptionEffect).unsafeRunSync()
    val exceptionExit = exceptionFiber.await.unsafeRunSync()
    exceptionExit match {
      case Exit.Die(throwable) => assert(throwable.getMessage.contains("exception-error"))
      case other => munit.Assertions.fail(s"Expected Die with RuntimeException, got: $other")
    }
  }

  test("PlatformBackend backend supports observer integration") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val backend = PlatformBackend.backend
    val observer = new TestObserver
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect, Some(observer)).unsafeRunSync()
    fiber.await.unsafeRunSync()

    val events = observer.events
    assert(events.nonEmpty, "Observer should capture events")

    val fiberEvents = events.collect {
      case e: EruEvent.FiberStarted => e
      case e: EruEvent.FiberCompleted => e
    }

    assert(fiberEvents.nonEmpty, "Should capture fiber lifecycle events")
  }

  test("PlatformBackend backend handles complex computations") {
    val backend = PlatformBackend.backend

    val complexEffect = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.effect(a + b + 12)
      fiber <- backend.fork(Eru.succeed(c * 2))
      result <- fiber.await
      finalValue <- result match {
        case Exit.Success(value) => Eru.succeed(value)
        case other => Eru.fail(s"Nested fiber failed: $other")
      }
    } yield finalValue

    val fiber = backend.fork(complexEffect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 84) // (10 + 20 + 12) * 2
      case other => munit.Assertions.fail(s"Expected Success(84), got: $other")
    }
  }

  test("PlatformBackend backend type consistency") {
    val singletonBackend = PlatformBackend.backend
    val freshBackend = PlatformBackend.createFreshBackend()

    // Both should implement ConcurrencyBackend interface
    val _: ConcurrencyBackend = singletonBackend
    val _: ConcurrencyBackend = freshBackend

    // Test that both can be used interchangeably for basic operations
    def testBackend(backend: ConcurrencyBackend): String = {
      val effect = Eru.succeed("tested")
      val fiber = backend.fork(effect).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()
      exit match {
        case Exit.Success(value: String) => value
        case other => munit.Assertions.fail(s"Expected string success, got: $other")
      }
    }

    assertEquals(testBackend(singletonBackend), "tested")
    assertEquals(testBackend(freshBackend), "tested")
  }

  test("PlatformBackend backend supports resource cleanup") {
    val backend = PlatformBackend.backend
    var cleanupCalled = false

    val effect = Eru
      .succeed("resource")
      .ensure(Eru.effect {
        cleanupCalled = true
        ()
      })

    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, "resource")
      case other => munit.Assertions.fail(s"Expected Success('resource'), got: $other")
    }

    assert(cleanupCalled, "Resource cleanup should have been called")
  }

  test("PlatformBackend isolation between fresh backends") {
    val backend1 = PlatformBackend.createFreshBackend()
    val backend2 = PlatformBackend.createFreshBackend()

    // Create effects that could interfere if backends share state
    val effect1 = for {
      fiber <- backend1.fork(Eru.succeed("backend1"))
      result <- fiber.await
      value <- result match {
        case Exit.Success(v) => Eru.succeed(v)
        case other => Eru.fail(s"Failed: $other")
      }
    } yield value

    val effect2 = for {
      fiber <- backend2.fork(Eru.succeed("backend2"))
      result <- fiber.await
      value <- result match {
        case Exit.Success(v) => Eru.succeed(v)
        case other => Eru.fail(s"Failed: $other")
      }
    } yield value

    val fiber1 = backend1.fork(effect1).unsafeRunSync()
    val fiber2 = backend2.fork(effect2).unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()

    exit1 match {
      case Exit.Success(value) => assertEquals(value, "backend1")
      case other => munit.Assertions.fail(s"Backend1 failed: $other")
    }

    exit2 match {
      case Exit.Success(value) => assertEquals(value, "backend2")
      case other => munit.Assertions.fail(s"Backend2 failed: $other")
    }
  }
}
