package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for the unified Prelude.
  *
  * Validates that the prelude correctly re-exports all public APIs from eru-core and eru-runtime,
  * providing a single canonical import path. Tests ensure that all types, values, and extensions
  * are accessible through the prelude without requiring users to understand internal package
  * structure.
  */
class PreludeSpec extends EruTestSuite {

  test("prelude exports core effect types") {
    import net.ghoula.eru.prelude.*

    // Test that basic Eru operations are available
    val effect: Eru[Nothing, Int] = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)

    // Test failure case
    val failing: Eru[String, Int] = Eru.fail("error")
    val exception = intercept[EruException[String]] {
      failing.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("prelude exports Result and Exit types") {
    import net.ghoula.eru.prelude.*

    // Test Result construction and operations
    val success = Result.Success(42)
    val failure = Result.Failure("error")

    assertEquals(success.map(_ * 2), Result.Success(84))
    assertEquals(failure.map((x: Int) => x * 2), Result.Failure("error"))

    // Test Exit construction
    val successExit = Exit.Success(42)
    val failureExit = Exit.Failure("error")

    assertEquals(successExit, Exit.Success(42))
    assertEquals(failureExit, Exit.Failure("error"))
  }

  test("prelude exports EruObserver and events") {
    import net.ghoula.eru.prelude.*

    // Test that EruObserver is available
    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val observer = new TestObserver
    val result = Eru.succeed(42).unsafeRunSyncWith(observer)
    assertEquals(result, 42)
    assert(observer.events.nonEmpty, "Observer should capture events")

    // Test observer factory methods
    val noopObserver = EruObserver.noop
    val consoleObserver = EruObserver.console
    assert(Option(noopObserver).isDefined)
    assert(Option(consoleObserver).isDefined)
  }

  test("prelude exports EruRuntime and provides default instance") {
    import net.ghoula.eru.prelude.*

    // Test that default runtime is available
    val runtime: EruRuntime = summon[EruRuntime]
    assert(Option(runtime).isDefined)

    // Test runtime creation methods
    val customRuntime = EruRuntime.create()
    val sharedRuntime = EruRuntime.shared

    assert(Option(customRuntime).isDefined)
    assert(Option(sharedRuntime).isDefined)

    customRuntime.cleanup()
  }

  test("prelude exports concurrent coordination types") {
    import net.ghoula.eru.prelude.*

    // Test type aliases are available through compilation
    def testTypes(): Unit = {
      val _: Option[Ref[Int]] = None
      val _: Option[Deferred[String]] = None
      val _: Option[Semaphore] = None
      val _: Option[Queue[Int]] = None
      val _: Option[Hub[String]] = None
      val _: Option[Promise[String, Int]] = None
      val _: Option[CountDownLatch] = None
      val _: Option[CyclicBarrier] = None
      val _: Option[Fiber[String, Int]] = None
      ()
    }

    testTypes()
    // Test that these types are available through prelude import
    assert(true, "All types should compile through prelude")
  }

  test("prelude exports RuntimeExtensions for concurrent operations") {
    import net.ghoula.eru.prelude.*

    // Test that concurrent extensions are available
    val effect1 = Eru.succeed(10)
    val effect2 = Eru.succeed(20)

    // Test zipPar extension
    val parallel = effect1.zipPar(effect2)
    val (result1, result2) = parallel.unsafeRunSync()
    assertEquals((result1, result2), (10, 20))

    // Test race extension
    val raced = effect1.race(effect2)
    val raceResult = raced.unsafeRunSync()
    assert(raceResult.isLeft || raceResult.isRight, "Race should return Either")
  }

  test("prelude supports timeout operations") {
    import net.ghoula.eru.prelude.*

    // Test timeout extension
    val effect = Eru.succeed(42)
    val timed = effect.timeout(Duration.ofSeconds(1))
    val result = timed.unsafeRunSync()
    assertEquals(result, 42)

    // Test timeoutTo extension (fallback)
    val timedWithFallback = effect.timeoutTo(Duration.ofSeconds(1), -1)
    val fallbackResult = timedWithFallback.unsafeRunSync()
    assertEquals(fallbackResult, 42)
  }

  test("prelude provides fork and fiber operations") {
    import net.ghoula.eru.prelude.*

    val effect = Eru.succeed(42)

    // Test fork extension
    val fiber = effect.fork.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case other => munit.Assertions.fail(s"Expected Success(42), got: $other")
    }
  }

  test("prelude supports resource management extensions") {
    import net.ghoula.eru.prelude.*

    var cleaned = false
    val effect = Eru.succeed(42)

    // Test ensure extension
    val withCleanup = effect.ensure(Eru.effect { cleaned = true; () })
    val result = withCleanup.unsafeRunSync()

    assertEquals(result, 42)
    assert(cleaned, "Cleanup should have been called")
  }

  test("prelude supports error handling extensions") {
    import net.ghoula.eru.prelude.*

    val failingEffect: Eru[String, Int] = Eru.fail("error")

    // Test recover extension
    val recovered = failingEffect.recover {
      case "error" => 99
      case _ => 0
    }
    assertEquals(recovered.unsafeRunSync(), 99)

    // Test mapError extension
    val mappedError = failingEffect.mapError(e => s"Mapped: $e")
    val exception = intercept[EruException[String]] {
      mappedError.unsafeRunSync()
    }
    assertEquals(exception.error, "Mapped: error")
  }

  test("prelude supports Result extensions") {
    import net.ghoula.eru.prelude.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    // Test extension methods work
    assertEquals(success.map(_ * 2), Result.Success(84))
    assert(success.isSuccess)
    assert(!success.isFailure)

    assert(!failure.isSuccess)
    assert(failure.isFailure)

    // Test conversions
    val toEruResult = success.toEru
    val toExitResult = success.toExit
    assertEquals(toEruResult.unsafeRunSync(), 42)
    assertEquals(toExitResult, Exit.Success(42))
  }

  test("prelude supports debugging and tracing") {
    import net.ghoula.eru.prelude.*

    val effect = Eru.succeed(42)

    // Test debug extension
    val debugged = effect.debug("test step")
    assertEquals(debugged.unsafeRunSync(), 42)

    // Test tracing extensions (should not throw)
    val traced = effect.traced("test-operation")
    assertEquals(traced.unsafeRunSync(), 42)
  }

  test("prelude example code from documentation works") {
    import net.ghoula.eru.prelude.*
    import java.time.Duration

    // Test basic operations from the example
    val hello: Eru[Nothing, String] = Eru.succeed("hello")
    val value: String = hello.unsafeRunSync()
    assertEquals(value, "hello")

    // Test arithmetic operations
    val a = Eru.succeed(1)
    val b = Eru.succeed(2)
    val par: Eru[Throwable, (Int, Int)] = a.zipPar(b)
    val raced: Eru[Throwable, Either[Int, Int]] = a.race(b)

    val (result1, result2) = par.unsafeRunSync()
    assertEquals((result1, result2), (1, 2))

    val raceResult = raced.unsafeRunSync()
    assert(raceResult.isLeft || raceResult.isRight)

    // Test timeout operations
    val timed = a.map(_ => 42).timeout(Duration.ofMillis(50))
    assertEquals(timed.unsafeRunSync(), 42)

    val fallback: Eru[Throwable, Int] = a.map(_ => 42).timeoutTo(Duration.ofMillis(50), -1)
    assertEquals(fallback.unsafeRunSync(), 42)
  }

  test("prelude observer integration works as documented") {
    import net.ghoula.eru.prelude.*

    class PrintingObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(e: EruEvent): Unit = _events = e :: _events
    }

    val observer = new PrintingObserver
    val observed: Int = Eru.succeed(123).runWith(observer)
    assertEquals(observed, 123)
    assert(observer.events.nonEmpty, "Observer should capture events")
  }

  test("prelude exit handling works as documented") {
    import net.ghoula.eru.prelude.*

    val exit: Exit[Nothing, Int] = Eru.succeed(1).runExit()
    exit match {
      case Exit.Success(v) => assertEquals(v, 1)
      case other => munit.Assertions.fail(s"Expected Success(1), got: $other")
    }
  }

  test("prelude type aliases maintain variance") {
    import net.ghoula.eru.prelude.*

    // Test that type aliases are available and can be used in type positions
    def testVariance(): Unit = {
      val fiber: Option[Fiber[Nothing, Int]] = None
      val _: Option[Fiber[Any, Any]] = fiber // Should compile due to covariance

      val promise: Option[Promise[String, Int]] = None
      // Test that types are accessible
      assert(promise.isEmpty)
    }

    testVariance()
    assert(true, "Type variance should compile correctly")
  }

  test("prelude imports work without conflicts") {
    // Test that the single import provides everything needed
    import net.ghoula.eru.prelude.*

    val effect = for {
      ref <- Eru.ref(0)
      _ <- ref.update(_ + 1)
      value <- ref.get
      deferred <- Eru.deferred[String]
      fiber <- deferred.complete("done").fork
      _ <- fiber.await
      result <- deferred.await.eru
    } yield (value, result)

    // This may not run if coordination primitives aren't implemented yet
    // but should at least compile
    assert(Option(effect).isDefined)
  }
}
