package net.ghoula.eru

import net.ghoula.eru.internal.{BackendFactory, ConcurrencyBackend}
import net.ghoula.eru.test.EruTestSuite

/** Regression tests for the ServiceLoader-backed backend resolution.
  *
  * The spec pins the contracts that matter at runtime:
  *
  *   1. `createFreshBackend()` returns a working, fork-capable ConcurrencyBackend.
  *   2. Successive `createFreshBackend()` calls return distinct adapter instances (each EruRuntime
  *      gets its own rootFibers + timerWheel).
  *   3. ServiceLoader discovery finds the JVM backend provider.
  */
class PlatformBackendSpec extends EruTestSuite {

  test("createFreshBackend returns a working backend") {
    val backend = PlatformBackend.createFreshBackend()

    val effect = Eru.succeed(42)
    val fiber = backend.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("successive createFreshBackend calls return distinct adapters") {
    val b1 = PlatformBackend.createFreshBackend()
    val b2 = PlatformBackend.createFreshBackend()

    assert(
      b1 ne b2,
      "Successive createFreshBackend() calls MUST return distinct adapter instances — " +
        "each EruRuntime owns its own rootFibers queue and timer wheel."
    )
  }

  test("ServiceLoader discovery finds the JVM backend provider") {
    val provider = PlatformBackend.discoveredProviderForTests

    assert(Option(provider).isDefined, "Provider discovery must not return null")

    val _: ConcurrencyBackend = provider.backend

    provider match {
      case _: BackendFactory => ()
      case other =>
        munit.Assertions.fail(
          s"Discovered provider should implement BackendFactory (got ${other.getClass.getName}); " +
            "the META-INF/services registration is missing."
        )
    }
  }

  test("concurrent createFreshBackend calls all produce working backends") {
    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.Duration

    val futures = (1 to 5).map(_ => Future(PlatformBackend.createFreshBackend()))
    val backends = Await.result(Future.sequence(futures), Duration("5s"))

    backends.foreach { backend =>
      assert(Option(backend).isDefined, "All fresh backends should be created successfully")

      val effect = Eru.succeed(123)
      val fiber = backend.fork(effect).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()

      exit match {
        case Exit.Success(value) => assertEquals(value, 123)
        case other => munit.Assertions.fail(s"Expected Success(123), got: $other")
      }
    }
  }

  test("fresh backend supports fork operations") {
    val backend = PlatformBackend.createFreshBackend()

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

  test("fresh backend supports error handling") {
    val backend = PlatformBackend.createFreshBackend()

    val successEffect = Eru.succeed("success")
    val failureEffect = Eru.fail("typed-error")
    val exceptionEffect = Eru.effect(throw new RuntimeException("exception-error"))

    val successFiber = backend.fork(successEffect).unsafeRunSync()
    successFiber.await.unsafeRunSync() match {
      case Exit.Success(value) => assertEquals(value, "success")
      case other => munit.Assertions.fail(s"Expected Success('success'), got: $other")
    }

    val failureFiber = backend.fork(failureEffect).unsafeRunSync()
    failureFiber.await.unsafeRunSync() match {
      case Exit.Failure(error) => assertEquals(error, "typed-error")
      case other => munit.Assertions.fail(s"Expected Failure('typed-error'), got: $other")
    }

    val exceptionFiber = backend.fork(exceptionEffect).unsafeRunSync()
    exceptionFiber.await.unsafeRunSync() match {
      case Exit.Die(throwable) => assert(throwable.getMessage.contains("exception-error"))
      case other => munit.Assertions.fail(s"Expected Die with RuntimeException, got: $other")
    }
  }

  test("fresh backend integrates with observers") {
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val backend = PlatformBackend.createFreshBackend()
    val observer = new TestObserver
    val effect = Eru.succeed(42)

    val fiber = backend.fork(effect, Some(observer)).unsafeRunSync()
    fiber.await.unsafeRunSync()

    val fiberEvents = observer.events.collect {
      case e: EruEvent.FiberStarted => e
      case e: EruEvent.FiberCompleted => e
    }

    assert(fiberEvents.nonEmpty, "Should capture fiber lifecycle events")
  }

  test("fresh backend handles complex nested computations") {
    val backend = PlatformBackend.createFreshBackend()

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
    fiber.await.unsafeRunSync() match {
      case Exit.Success(value) => assertEquals(value, 84)
      case other => munit.Assertions.fail(s"Expected Success(84), got: $other")
    }
  }

  test("fresh backend runs finalizers") {
    val backend = PlatformBackend.createFreshBackend()
    var cleanupCalled = false

    val effect = Eru
      .succeed("resource")
      .ensure(Eru.effect {
        cleanupCalled = true
        ()
      })

    val fiber = backend.fork(effect).unsafeRunSync()
    fiber.await.unsafeRunSync() match {
      case Exit.Success(value) => assertEquals(value, "resource")
      case other => munit.Assertions.fail(s"Expected Success('resource'), got: $other")
    }

    assert(cleanupCalled, "Resource cleanup should have been called")
  }

  test("two fresh backends execute independent effects without interference") {
    val backend1 = PlatformBackend.createFreshBackend()
    val backend2 = PlatformBackend.createFreshBackend()

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

    fiber1.await.unsafeRunSync() match {
      case Exit.Success(value) => assertEquals(value, "backend1")
      case other => munit.Assertions.fail(s"Backend1 failed: $other")
    }

    fiber2.await.unsafeRunSync() match {
      case Exit.Success(value) => assertEquals(value, "backend2")
      case other => munit.Assertions.fail(s"Backend2 failed: $other")
    }
  }
}
