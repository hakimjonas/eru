package net.ghoula.eru

import munit.FunSuite

class EruSpec extends FunSuite {

  test("Eru.succeed creates a Succeed with the given value") {
    val eru = Eru.succeed(42)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.effect creates an Effect with the given computation") {
    val eru = Eru.effect(42)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.succeed is eager - it evaluates its argument immediately") {
    var counter = 0
    val eru = Eru.succeed {
      counter += 1
      42
    }
    assertEquals(counter, 1, "Value should be evaluated immediately for Eru.succeed")
    assertEquals(eru.unsafeRunSync(), 42)
    assertEquals(counter, 1, "Value should only be evaluated once")
  }

  test("Eru.effect is lazy - does not execute computation immediately") {
    var counter = 0
    val eru = Eru.effect {
      counter += 1
      42
    }
    assertEquals(counter, 0, "Computation should not be executed when creating Eru.effect")
    assertEquals(eru.unsafeRunSync(), 42)
    assertEquals(counter, 1, "Computation should be executed exactly once when running")
  }

  test("map transforms values lazily") {
    var mapCounter = 0
    var effectCounter = 0

    val eru = Eru.effect {
      effectCounter += 1
      10
    }.map { x =>
      mapCounter += 1
      x * 2
    }

    assertEquals(effectCounter, 0, "Original effect should not be executed when mapping")
    assertEquals(mapCounter, 0, "Map function should not be executed when mapping")

    val result = eru.unsafeRunSync()
    assertEquals(result, 20)
    assertEquals(effectCounter, 1, "Original effect should be executed exactly once")
    assertEquals(mapCounter, 1, "Map function should be executed exactly once")
  }

  test("map on Succeed transforms values correctly") {
    val eru = Eru.succeed(5).map(_ * 3)
    assertEquals(eru.unsafeRunSync(), 15)
  }

  test("flatMap chains computations lazily") {
    var firstCounter = 0
    var secondCounter = 0
    var flatMapCounter = 0

    val eru = Eru.effect {
      firstCounter += 1
      10
    }.flatMap { x =>
      flatMapCounter += 1
      Eru.effect {
        secondCounter += 1
        x * 2
      }
    }

    assertEquals(firstCounter, 0, "First computation should not be executed when chaining")
    assertEquals(secondCounter, 0, "Second computation should not be executed when chaining")
    assertEquals(flatMapCounter, 0, "FlatMap function should not be executed when chaining")

    val result = eru.unsafeRunSync()
    assertEquals(result, 20)
    assertEquals(firstCounter, 1, "First computation should be executed exactly once")
    assertEquals(secondCounter, 1, "Second computation should be executed exactly once")
    assertEquals(flatMapCounter, 1, "FlatMap function should be executed exactly once")
  }

  test("flatMap with Succeed chains correctly") {
    val eru = Eru.succeed(5).flatMap(x => Eru.effect(x * 2))
    assertEquals(eru.unsafeRunSync(), 10)
  }

  test("complex chaining with map and flatMap") {
    val eru = Eru
      .succeed(5)
      .map(_ * 2)
      .flatMap(x => Eru.succeed(x + 3))
      .map(_ * 2)

    assertEquals(eru.unsafeRunSync(), 26)
  }

  test("complex chaining with Effects") {
    val eru = Eru
      .effect(5)
      .map(_ * 2)
      .flatMap(x => Eru.effect(x + 3))
      .map(_ * 2)

    assertEquals(eru.unsafeRunSync(), 26)
  }

  test("unsafeRunSync handles nested flatMap correctly") {
    val eru = Eru
      .succeed(1)
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))

    assertEquals(eru.unsafeRunSync(), 4)
  }

  test("stack safety with large number of flatMap chains") {
    val chainSize = 10000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(x => Eru.succeed(x + 1))
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("stack safety with large number of map chains") {
    val chainSize = 10000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.map(_ + 1)
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("stack safety with mixed map and flatMap chains") {
    val chainSize = 5000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, i) =>
      if (i % 2 == 0) {
        acc.map(_ + 1)
      } else {
        acc.flatMap(x => Eru.succeed(x + 1))
      }
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("Eru is covariant in success type") {
    val stringValue: Eru[Nothing, String] = Eru.succeed("value")
    val anyValue: Eru[Nothing, Any] = stringValue
    assertEquals(anyValue.unsafeRunSync(), "value")
  }

  test("map preserves type covariance") {
    val intEru: Eru[Nothing, Int] = Eru.succeed(42)
    val stringEru: Eru[Nothing, String] = intEru.map(_.toString)
    assertEquals(stringEru.unsafeRunSync(), "42")
  }

  test("flatMap maintains type safety") {
    val eru: Eru[Nothing, String] = Eru.succeed(5).flatMap(x => Eru.succeed(x.toString))
    assertEquals(eru.unsafeRunSync(), "5")
  }

  test("effect with side effects executes correctly") {
    var sideEffectCounter = 0
    val eru = Eru.effect {
      sideEffectCounter += 1
      sideEffectCounter
    }

    assertEquals(sideEffectCounter, 0, "Side effect should not execute until run")
    val result = eru.unsafeRunSync()
    assertEquals(result, 1)
    assertEquals(sideEffectCounter, 1, "Side effect should execute exactly once")
  }

  test("multiple runs of the same Eru execute independently") {
    var counter = 0
    val eru = Eru.effect {
      counter += 1
      counter
    }

    assertEquals(eru.unsafeRunSync(), 1)
    assertEquals(eru.unsafeRunSync(), 2)
    assertEquals(eru.unsafeRunSync(), 3)
  }

  test("succeed with Unit type") {
    val eru = Eru.succeed(())
    assertEquals(eru.unsafeRunSync(), ())
  }

  test("effect with Unit type") {
    var executed = false
    val eru = Eru.effect {
      executed = true
    }

    assert(!executed, "Effect should not execute until run")
    eru.unsafeRunSync()
    assert(executed, "Effect should have executed")
  }

  test("deeply nested Chain structure executes correctly") {
    val eru = Eru
      .succeed(0)
      .flatMap(x => Eru.succeed(x + 1).flatMap(y => Eru.succeed(y + 1)))
      .flatMap(x => Eru.succeed(x + 1).flatMap(y => Eru.succeed(y + 1)))

    assertEquals(eru.unsafeRunSync(), 4)
  }

  test("exception in effect computation is thrown on unsafeRunSync") {
    val eru = Eru.effect(throw new RuntimeException("test error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("exception in map function is thrown on unsafeRunSync") {
    val eru = Eru.succeed(42).map(_ => throw new RuntimeException("map error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("exception in flatMap function is thrown on unsafeRunSync") {
    val eru = Eru.succeed(42).flatMap(_ => throw new RuntimeException("flatMap error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("Eru.fail creates a failed Eru") {
    val eru = Eru.fail("error message")

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "error message")
  }

  test("Eru.fail is covariant in error type") {
    val stringError: Eru[String, Nothing] = Eru.fail("error")
    val anyError: Eru[Any, Nothing] = stringError

    val exception = intercept[EruException[Any]] {
      anyError.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("mapError transforms error type on failure") {
    val eru = Eru.fail("original error").mapError(_.length)

    val exception = intercept[EruException[Int]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, 14)
  }

  test("mapError leaves success unchanged") {
    val eru = Eru.succeed(42).mapError((_: String) => 999)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("mapError is lazy - transformation function not called on success") {
    var called = false
    val eru = Eru.succeed(42).mapError { (s: String) =>
      called = true
      s.length
    }
    assertEquals(eru.unsafeRunSync(), 42)
    assert(!called, "mapError function should not be called on success")
  }

  test("recover converts specific errors to success values") {
    val eru = Eru.fail("recoverable error").recover {
      case "recoverable error" => "recovered"
      case _ => "not recovered"
    }
    assertEquals(eru.unsafeRunSync(), "recovered")
  }

  test("recover leaves unmatched errors as failures") {
    val eru = Eru.fail("unmatched error").recover { case "recoverable error" =>
      "recovered"
    }

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "unmatched error")
  }

  test("recover leaves success values unchanged") {
    val eru = Eru.succeed(42).recover { case _ =>
      999
    }
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("recoverWith provides alternative computations for errors") {
    val eru = Eru.fail("recoverable error").recoverWith {
      case "recoverable error" => Eru.succeed("recovered")
      case _ => Eru.fail("alternative error")
    }
    assertEquals(eru.unsafeRunSync(), "recovered")
  }

  test("recoverWith can transform to different error types") {
    val eru = Eru.fail("string error").recoverWith { case "string error" =>
      Eru.fail(404)
    }

    val exception = intercept[EruException[String | Int]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, 404)
  }

  test("recoverWith leaves unmatched errors as failures") {
    val eru = Eru.fail("unmatched error").recoverWith { case "recoverable error" =>
      Eru.succeed("recovered")
    }

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "unmatched error")
  }

  test("recoverWith leaves success values unchanged") {
    val eru = Eru.succeed(42).recoverWith { case _ =>
      Eru.succeed(999)
    }
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("orElse provides fallback computation on failure") {
    val eru = Eru.fail("first error").orElse(Eru.succeed("fallback"))
    assertEquals(eru.unsafeRunSync(), "fallback")
  }

  test("orElse returns original success without evaluating fallback") {
    var fallbackEvaluated = false
    val eru = Eru.succeed(42).orElse {
      fallbackEvaluated = true
      Eru.succeed(999)
    }
    assertEquals(eru.unsafeRunSync(), 42)
    assert(!fallbackEvaluated, "Fallback should not be evaluated on success")
  }

  test("orElse can combine different error types") {
    val eru = Eru.fail("string error").orElse(Eru.fail(404))

    val exception = intercept[EruException[String | Int]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, 404)
  }

  test("orElse is lazy - fallback not evaluated immediately") {
    var evaluated = false
    val eru = Eru.fail("error").orElse {
      evaluated = true
      Eru.succeed("fallback")
    }
    assert(!evaluated, "Fallback should not be evaluated immediately")
    assertEquals(eru.unsafeRunSync(), "fallback")
    assert(evaluated, "Fallback should be evaluated when run")
  }

  test("fromEither creates success from Right") {
    val either: Either[String, Int] = Right(42)
    val eru = Eru.fromEither(either)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("fromEither creates failure from Left") {
    val either: Either[String, Int] = Left("error")
    val eru = Eru.fromEither(either)

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("fromTry creates success from successful Try") {
    import scala.util.{Success, Try}
    val t: Try[Int] = Success(42)
    val eru = Eru.fromTry(t)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("fromTry creates failure from failed Try") {
    import scala.util.{Failure, Try}
    val exception = new RuntimeException("try error")
    val t: Try[Int] = Failure(exception)
    val eru = Eru.fromTry(t)

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("fromTry is lazy - Try evaluation is suspended") {
    var evaluated = false
    val eru = Eru.fromTry {
      evaluated = true
      scala.util.Success(42)
    }
    assert(!evaluated, "Try should not be evaluated immediately")
    assertEquals(eru.unsafeRunSync(), 42)
    assert(evaluated, "Try should be evaluated when run")
  }

  test("zip combines two successful computations into a tuple") {
    val a = Eru.succeed(1)
    val b = Eru.succeed("a")
    val zipped = a.zip(b)
    assertEquals(zipped.unsafeRunSync(), (1, "a"))
  }

  test("zip short-circuits on left failure and does not evaluate right") {
    var rightEvaluated = false
    val left = Eru.fail("left error")
    val right = Eru.effect { rightEvaluated = true; 42 }

    val ex = intercept[EruException[String | Throwable]] {
      left.zip(right).unsafeRunSync()
    }
    assertEquals(ex.error, "left error")
    assert(!rightEvaluated, "Right side should not be evaluated when left fails")
  }

  test("zip propagates right failure when left succeeds") {
    val left = Eru.succeed(1)
    val right = Eru.fail("right error")

    val ex = intercept[EruException[String]] {
      left.zip(right).unsafeRunSync()
    }
    assertEquals(ex.error, "right error")
  }

  test("EruException wraps error correctly") {
    val error = "test error"
    val exception = EruException(error)
    assertEquals(exception.error, error)
  }

  test("EruException toString includes error") {
    val error = "test error"
    val exception = EruException(error)
    assertEquals(exception.toString, "EruException(test error)")
  }

  test("EruException getMessage uses error toString") {
    val error = "test error"
    val exception = EruException(error)
    assertEquals(exception.getMessage, "test error")
  }

  test("EruException handles None error") {
    val exception = EruException(None)
    assertEquals(exception.getMessage, "None")
  }

  test("map preserves errors") {
    val eru = Eru.fail("error").map((_: Int) * 2)

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("flatMap preserves errors from source") {
    val eru = Eru.fail("error").flatMap((_: Int) => Eru.succeed("transformed"))

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("flatMap preserves errors from transformation function") {
    val eru = Eru.succeed(42).flatMap(_ => Eru.fail("transformation error"))

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "transformation error")
  }

  test("chained operations preserve first error") {
    val eru = Eru
      .fail("first error")
      .map((_: Int) * 2)
      .flatMap(x => Eru.succeed(x.toString))
      .map(_.length)

    val exception = intercept[EruException[String]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, "first error")
  }

  test("complex error recovery chain") {
    val eru = Eru.fail("initial error").recover { case "initial error" =>
      throw new RuntimeException("recovery failed")
    }

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("single level recoverWith with error transformation") {
    val eru = Eru.fail("string error").recoverWith { case "string error" =>
      Eru.fail(404)
    }

    val exception = intercept[EruException[String | Int]] {
      eru.unsafeRunSync()
    }
    assertEquals(exception.error, 404)
  }

  test("orElse with immediate success fallback") {
    val eru = Eru.fail("error").orElse(Eru.succeed("fallback"))
    assertEquals(eru.unsafeRunSync(), "fallback")
  }

  test("complex error recovery with mixed operations") {
    val eru = Eru
      .fail("original")
      .mapError(_.toUpperCase)
      .recover { case "ORIGINAL" =>
        "recovered from uppercase"
      }
    assertEquals(eru.unsafeRunSync(), "recovered from uppercase")
  }

  test("flatMap after recoverWith should not cause infinite loop") {
    val eru = Eru
      .fail("error")
      .recoverWith { case "error" => Eru.succeed(42) }
      .flatMap(x => Eru.succeed(x * 2))

    assertEquals(eru.unsafeRunSync(), 84)
  }

  test("flatMap after mapError should not cause infinite loop") {
    val eru = Eru
      .fail("error")
      .mapError(_.toUpperCase)
      .flatMap(_ => Eru.succeed("should not reach here"))
      .recover { case "ERROR" => "recovered" }

    assertEquals(eru.unsafeRunSync(), "recovered")
  }

  test("attempt returns Result.Success on success") {
    val res = Eru.succeed(42).attempt.unsafeRunSync()
    assertEquals(res, Result.Success(42))
  }

  test("attempt returns Result.Failure on typed error") {
    val res = Eru.fail("boom").attempt.unsafeRunSync()
    assertEquals(res, Result.Failure("boom"))
  }

  test("attempt returns Result.Failure on Throwable from effect") {
    val ex = new RuntimeException("boom")
    val res = Eru.effect[Int](throw ex).attempt.unsafeRunSync()
    assertEquals(res, Result.Failure(ex))
  }

  test("attempt is lazy and executes only once") {
    var counter = 0
    val prog = Eru.effect {
      counter += 1
      7
    }.attempt

    assertEquals(counter, 0)
    val r1 = prog.unsafeRunSync()
    assertEquals(r1, Result.Success(7))
    assertEquals(counter, 1)
  }

  test("fromOption succeeds on Some and fails on None lazily") {
    var optEvaluated = 0
    var onNoneEvaluated = 0

    def mkSome(): Option[Int] = { optEvaluated += 1; Some(1) }
    def mkNone(): Option[Int] = { optEvaluated += 1; None }
    def err(): String = { onNoneEvaluated += 1; "none" }

    val success = Eru.fromOption(mkSome(), err())
    assertEquals(optEvaluated, 0)
    assertEquals(onNoneEvaluated, 0)
    assertEquals(success.unsafeRunSync(), 1)
    assertEquals(optEvaluated, 1)
    assertEquals(onNoneEvaluated, 0)

    optEvaluated = 0
    onNoneEvaluated = 0
    val failure = Eru.fromOption(mkNone(), err())
    assertEquals(optEvaluated, 0)
    assertEquals(onNoneEvaluated, 0)
    val ex = intercept[EruException[String]] { failure.unsafeRunSync() }
    assertEquals(ex.error, "none")
    assertEquals(optEvaluated, 1)
    assertEquals(onNoneEvaluated, 1)
  }

  test("unit returns () and composes") {
    val prog = Eru.unit.flatMap(_ => Eru.succeed(123))
    assertEquals(prog.unsafeRunSync(), 123)
  }

  test("unsafeRunSync rethrows Throwable even when typed via fail") {
    val ex = new RuntimeException("typed throwable")
    val prog: Eru[Throwable, Nothing] = Eru.fail(ex)
    intercept[RuntimeException] {
      prog.unsafeRunSync()
    }
  }

  // --- Eru.blocking tests ---
  test("Eru.blocking is lazy - does not execute computation immediately") {
    var counter = 0
    val eru = Eru.blocking {
      counter += 1
      42
    }
    assertEquals(counter, 0, "Computation should not be executed when creating Eru.blocking")
    assertEquals(eru.unsafeRunSync(), 42, "blocking should return computed value")
    assertEquals(counter, 1, "Computation should be executed exactly once when running")
  }

  test("Eru.blocking returns success for pure computation") {
    val eru = Eru.blocking(21 * 2)
    assertEquals(eru.unsafeRunSync(), 42, "blocking should evaluate the pure expression")
  }

  test("Eru.blocking captures NonFatal and rethrows at the edge") {
    val ex = new RuntimeException("boom-blocking")
    val prog: Eru[Throwable, Int] = Eru.blocking[Int](throw ex)
    intercept[RuntimeException] {
      prog.unsafeRunSync()
    }
  }

  // Tests for Pure Construction-Time Optimizations

  test("eager evaluation: succeed().map() evaluates immediately at construction time") {
    var mapCallCount = 0
    val computation = Eru.succeed(42).map { x =>
      mapCallCount += 1
      x * 2
    }
    
    // The map function should be called immediately during construction
    assertEquals(mapCallCount, 1, "Map function should be evaluated at construction time for pure chains")
    
    // Running should not call the map function again
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    assertEquals(mapCallCount, 1, "Map function should only be called once during construction")
  }

  test("eager evaluation: chained maps on succeed() are evaluated immediately") {
    var map1CallCount = 0
    var map2CallCount = 0
    
    val computation = Eru.succeed(10)
      .map { x =>
        map1CallCount += 1
        x * 2
      }
      .map { x =>
        map2CallCount += 1
        x + 5
      }
    
    // Both map functions should be called during construction
    assertEquals(map1CallCount, 1, "First map should be evaluated at construction time")
    assertEquals(map2CallCount, 1, "Second map should be evaluated at construction time")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 25) // (10 * 2) + 5 = 25
    assertEquals(map1CallCount, 1, "First map should only be called once")
    assertEquals(map2CallCount, 1, "Second map should only be called once")
  }

  test("eager evaluation: map on Effect is not evaluated immediately") {
    var effectCallCount = 0
    var mapCallCount = 0
    
    val computation = Eru.effect {
      effectCallCount += 1
      42
    }.map { x =>
      mapCallCount += 1
      x * 2
    }
    
    // Neither the effect nor map should be called during construction
    assertEquals(effectCallCount, 0, "Effect should not be evaluated at construction time")
    assertEquals(mapCallCount, 0, "Map should not be evaluated at construction time for non-pure source")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    assertEquals(effectCallCount, 1, "Effect should be called once during execution")
    assertEquals(mapCallCount, 1, "Map should be called once during execution")
  }

  test("eager evaluation: map exception handling converts to Effect") {
    val computation = Eru.succeed(42).map { _ =>
      throw new RuntimeException("Map function failed")
    }
    
    // The computation should be converted to an Effect that captures the exception
    val caught = intercept[RuntimeException] {
      computation.unsafeRunSync()
    }
    assertEquals(caught.getMessage, "Map function failed")
  }

  test("mixed pure/impure chains behave correctly with selective flatMap optimization") {
    var mapCallCount = 0
    var effectCallCount = 0
    var flatMapCallCount = 0
    
    val computation = Eru.succeed(10)
      .map { x =>
        mapCallCount += 1
        x * 2  // This should be evaluated immediately
      }
      .flatMap { x =>
        flatMapCallCount += 1
        Eru.effect {
          effectCallCount += 1
          x + 5  // This should be deferred (not pure)
        }
      }
    
    // Only the map should be evaluated immediately, flatMap with Effect remains lazy
    assertEquals(mapCallCount, 1, "Map on succeed should be evaluated at construction time")
    assertEquals(flatMapCallCount, 0, "FlatMap with Effect should not be evaluated at construction time")
    assertEquals(effectCallCount, 0, "Effect should not be evaluated at construction time")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 25) // (10 * 2) + 5 = 25
    assertEquals(mapCallCount, 1, "Map should only be called once")
    assertEquals(flatMapCallCount, 1, "FlatMap should be called once during execution")
    assertEquals(effectCallCount, 1, "Effect should be called once during execution")
  }

  // Tests for Safe FlatMap Construction-Time Optimizations

  test("flatMap optimization disabled: succeed().flatMap(pure) remains lazy") {
    var flatMapCallCount = 0
    val computation = Eru.succeed(42).flatMap { x =>
      flatMapCallCount += 1
      Eru.succeed(x * 2)  // Pure continuation
    }
    
    // The flatMap function should NOT be called during construction (optimization disabled)
    assertEquals(flatMapCallCount, 0, "FlatMap function should not be evaluated at construction time (optimization disabled)")
    
    // Running should call the flatMap function
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    assertEquals(flatMapCallCount, 1, "FlatMap function should be called once during execution")
  }

  test("flatMap optimization disabled: chained pure flatMaps remain lazy") {
    var flatMap1CallCount = 0
    var flatMap2CallCount = 0
    
    val computation = Eru.succeed(10)
      .flatMap { x =>
        flatMap1CallCount += 1
        Eru.succeed(x * 2)  // Pure continuation
      }
      .flatMap { x =>
        flatMap2CallCount += 1
        Eru.succeed(x + 5)  // Pure continuation
      }
    
    // Both flatMap functions should NOT be called during construction (optimization disabled)
    assertEquals(flatMap1CallCount, 0, "First flatMap should not be evaluated at construction time (optimization disabled)")
    assertEquals(flatMap2CallCount, 0, "Second flatMap should not be evaluated at construction time (optimization disabled)")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 25) // (10 * 2) + 5 = 25
    assertEquals(flatMap1CallCount, 1, "First flatMap should be called once during execution")
    assertEquals(flatMap2CallCount, 1, "Second flatMap should be called once during execution")
  }

  test("eager evaluation: flatMap on Effect is not evaluated immediately") {
    var effectCallCount = 0
    var flatMapCallCount = 0
    
    val computation = Eru.effect {
      effectCallCount += 1
      42
    }.flatMap { x =>
      flatMapCallCount += 1
      Eru.succeed(x * 2)  // Pure continuation but impure source
    }
    
    // Neither the effect nor flatMap should be called during construction
    assertEquals(effectCallCount, 0, "Effect should not be evaluated at construction time")
    assertEquals(flatMapCallCount, 0, "FlatMap should not be evaluated at construction time for non-pure source")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    assertEquals(effectCallCount, 1, "Effect should be called once during execution")
    assertEquals(flatMapCallCount, 1, "FlatMap should be called once during execution")
  }

  test("eager evaluation: flatMap with non-pure continuation falls back to Chain") {
    var flatMapCallCount = 0
    var effectCallCount = 0
    
    val computation = Eru.succeed(42).flatMap { x =>
      flatMapCallCount += 1
      Eru.effect {  // Non-pure continuation
        effectCallCount += 1
        x * 2
      }
    }
    
    // FlatMap should not be evaluated at construction time due to non-pure continuation
    assertEquals(flatMapCallCount, 0, "FlatMap with non-pure continuation should not be evaluated at construction time")
    assertEquals(effectCallCount, 0, "Effect should not be evaluated at construction time")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    assertEquals(flatMapCallCount, 1, "FlatMap should be called once during execution")
    assertEquals(effectCallCount, 1, "Effect should be called once during execution")
  }

  test("flatMap optimization disabled: exception handling works correctly") {
    val computation = Eru.succeed(42).flatMap { _ =>
      throw new RuntimeException("FlatMap function failed")
    }
    
    // The exception should be thrown during execution (not construction since optimization is disabled)
    val caught = intercept[RuntimeException] {
      computation.unsafeRunSync()
    }
    assertEquals(caught.getMessage, "FlatMap function failed")
  }

  test("flatMap optimization disabled: side effects only occur during execution") {
    var sideEffectCount = 0
    val computation = Eru.succeed(42).flatMap { x =>
      sideEffectCount += 1
      Eru.effect(x * 2)  // Non-pure continuation
    }
    
    // No side effects during construction since optimization is disabled
    assertEquals(sideEffectCount, 0, "Side effect should not occur during construction (optimization disabled)")
    
    val result = computation.unsafeRunSync()
    assertEquals(result, 84)
    // Side effect should occur once during execution
    assertEquals(sideEffectCount, 1, "Side effect should occur once during execution")
  }

  test("construction-time optimizations preserve correctness for complex chains") {
    // Test that optimized and non-optimized paths produce the same results
    val pureChain = Eru.succeed(5)
      .map(_ * 2)
      .flatMap(x => Eru.succeed(x + 3))
      .map(_ * 2)
    
    val mixedChain = Eru.succeed(5)
      .map(_ * 2)
      .flatMap(x => Eru.effect(x + 3))
      .map(_ * 2)
    
    assertEquals(pureChain.unsafeRunSync(), 26)
    assertEquals(mixedChain.unsafeRunSync(), 26)
  }
}
