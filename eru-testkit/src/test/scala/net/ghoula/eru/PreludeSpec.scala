package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.test.EruTestSuite

/** Comprehensive test suite for the unified Prelude.
  *
  * Validates that the prelude correctly re-exports all public APIs from eru-core and eru-runtime,
  * providing a single canonical import path. Tests ensure that all types, values, and extensions
  * are accessible through the prelude without requiring users to understand internal package
  * structure.
  *
  * A plain `import net.ghoula.eru.prelude.*` in a top-level object (outside the suite-level given
  * scope) must resolve both `EruRuntime` and `Monotonic` without any `import given` ceremony.
  * `Monotonic`'s default given lives in its companion, so it is in implicit scope wherever the type
  * is referenced — no prelude given declaration is required.
  */
class PreludeSpec extends EruTestSuite {

  test("prelude exports core effect types") {
    import net.ghoula.eru.prelude.*

    val effect: Eru[Nothing, Int] = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)

    val failing: Eru[String, Int] = Eru.fail("error")
    val exception = intercept[EruException[String]] {
      failing.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("prelude exports Result and Exit types") {
    import net.ghoula.eru.prelude.*

    val success = Result.Success(42)
    val failure = Result.Failure("error")

    assertEquals(success.map(_ * 2), Result.Success(84))
    assertEquals(failure.map((x: Int) => x * 2), Result.Failure("error"))

    val successExit = Exit.Success(42)
    val failureExit = Exit.Failure("error")

    assertEquals(successExit, Exit.Success(42))
    assertEquals(failureExit, Exit.Failure("error"))
  }

  test("prelude exports EruObserver and events") {
    import net.ghoula.eru.prelude.*

    class TestObserver extends EruObserver {
      private var _events: List[EruEvent] = Nil
      def events: List[EruEvent] = _events.reverse
      def onEvent(event: EruEvent): Unit = _events = event :: _events
    }

    val observer = new TestObserver
    val result = Eru.succeed(42).unsafeRunSyncWith(observer)
    assertEquals(result, 42)
    assert(observer.events.nonEmpty, "Observer should capture events")

    val noopObserver = EruObserver.noop
    val consoleObserver = EruObserver.console
    assert(Option(noopObserver).isDefined)
    assert(Option(consoleObserver).isDefined)
  }

  test("prelude exports EruRuntime and provides default instance") {
    import net.ghoula.eru.prelude.*

    val runtime: EruRuntime = summon[EruRuntime]
    assert(Option(runtime).isDefined)

    val customRuntime = EruRuntime.create()
    val sharedRuntime = EruRuntime.shared

    assert(Option(customRuntime).isDefined)
    assert(Option(sharedRuntime).isDefined)

    customRuntime.cleanup()
  }

  test("prelude exports concurrent coordination types") {
    import net.ghoula.eru.prelude.*

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
  }

  test("prelude exports RuntimeExtensions for concurrent operations") {
    import net.ghoula.eru.prelude.*

    val effect1 = Eru.succeed(10)
    val effect2 = Eru.succeed(20)

    val parallel = effect1.zipPar(effect2)
    val (result1, result2) = parallel.unsafeRunSync()
    assertEquals((result1, result2), (10, 20))

    val raced = effect1.race(effect2)
    val raceResult = raced.unsafeRunSync()
    assert(raceResult.isLeft || raceResult.isRight, "Race should return Either")
  }

  test("prelude supports timeout operations") {
    import net.ghoula.eru.prelude.*

    val effect = Eru.succeed(42)
    val timed = effect.timeout(Duration.ofSeconds(1))
    val result = timed.unsafeRunSync()
    assertEquals(result, 42)

    val timedWithFallback = effect.timeoutTo(Duration.ofSeconds(1), -1)
    val fallbackResult = timedWithFallback.unsafeRunSync()
    assertEquals(fallbackResult, 42)
  }

  test("prelude provides fork and fiber operations") {
    import net.ghoula.eru.prelude.*

    val effect = Eru.succeed(42)

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

    val withCleanup = effect.ensure(Eru.effect { cleaned = true })
    val result = withCleanup.unsafeRunSync()

    assertEquals(result, 42)
    assert(cleaned, "Cleanup should have been called")
  }

  test("prelude supports error handling extensions") {
    import net.ghoula.eru.prelude.*

    val failingEffect: Eru[String, Int] = Eru.fail("error")

    val recovered = failingEffect.recover {
      case "error" => 99
      case _ => 0
    }
    assertEquals(recovered.unsafeRunSync(), 99)

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

    assertEquals(success.map(_ * 2), Result.Success(84))
    assert(success.isSuccess)
    assert(!success.isFailure)

    assert(!failure.isSuccess)
    assert(failure.isFailure)

    val toEruResult = success.toEru
    val toExitResult = success.toExit
    assertEquals(toEruResult.unsafeRunSync(), 42)
    assertEquals(toExitResult, Exit.Success(42))
  }

  test("prelude supports debugging and tracing") {
    import net.ghoula.eru.prelude.*

    val effect = Eru.succeed(42)

    val debugged = effect.debug("test step")
    assertEquals(debugged.unsafeRunSync(), 42)

    val traced = effect.traced("test-operation")
    assertEquals(traced.unsafeRunSync(), 42)
  }

  test("prelude example code from documentation works") {
    import net.ghoula.eru.prelude.*
    import java.time.Duration

    val hello: Eru[Nothing, String] = Eru.succeed("hello")
    val value: String = hello.unsafeRunSync()
    assertEquals(value, "hello")

    val a = Eru.succeed(1)
    val b = Eru.succeed(2)
    val par: Eru[Throwable, (Int, Int)] = a.zipPar(b)
    val raced: Eru[Throwable, Either[Int, Int]] = a.race(b)

    val (result1, result2) = par.unsafeRunSync()
    assertEquals((result1, result2), (1, 2))

    val raceResult = raced.unsafeRunSync()
    assert(raceResult.isLeft || raceResult.isRight)

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

    def testVariance(): Unit = {
      val fiber: Option[Fiber[Nothing, Int]] = None
      val _: Option[Fiber[Any, Any]] = fiber

      val promise: Option[Promise[String, Int]] = None
      assert(promise.isEmpty)
    }

    testVariance()
  }

  object PreludeWildcardProbe {
    import net.ghoula.eru.prelude.*
    val resolvedRuntime: EruRuntime = summon[EruRuntime]
    val resolvedMonotonic: Monotonic = summon[Monotonic]
  }

  test("prelude wildcard import propagates EruRuntime and Monotonic givens (no `, given` needed)") {
    assert(Option(PreludeWildcardProbe.resolvedRuntime).isDefined)
    assert(Option(PreludeWildcardProbe.resolvedMonotonic).isDefined)
  }

  test("prelude Monotonic.sleep advances by at least the requested duration") {
    import net.ghoula.eru.prelude.*

    val prog = for {
      t0 <- summon[Monotonic].monotonicNow
      _ <- summon[Monotonic].sleep(Duration.ofMillis(5))
      t1 <- summon[Monotonic].monotonicNow
    } yield t0.until(t1)
    val elapsed = prog.unsafeRunSync()
    assert(
      elapsed.toMillis >= 5L,
      s"prelude Monotonic.sleep returned early: elapsed=${elapsed.toMillis}ms < 5ms"
    )
  }

  test("prelude imports work without conflicts") {
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

    assert(Option(effect).isDefined)
  }
}
