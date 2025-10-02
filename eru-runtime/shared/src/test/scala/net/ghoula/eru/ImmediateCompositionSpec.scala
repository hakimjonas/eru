package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Comprehensive test suite for Immediate composition methods.
  *
  * Verifies that Immediate values compose correctly through map, flatMap, and other combinators
  * while preserving the immediate (non-suspending) property. Tests ensure monad laws hold and that
  * the API provides ergonomic composition without requiring unwrapping to Eru.
  */
class ImmediateCompositionSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("map transforms the success value") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(10).unsafeRunSync()

    val doubled: Immediate[Nothing, Option[Int]] = queue.tryTake.map(_.map(_ * 2))

    assertEquals(doubled.unsafeRunSync(), Some(20))
  }

  test("map preserves Immediate wrapper") {
    val ref = Eru.ref("hello").unsafeRunSync()
    val upperCased = ref.get.map(_.toUpperCase)

    // Should compile - Immediate has unsafeRunSync
    val result: String = upperCased.unsafeRunSync()
    assertEquals(result, "HELLO")
  }

  test("flatMap chains computations") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    val program: Immediate[Nothing, Boolean] =
      queue.tryPut(5).flatMap(_ => queue.tryPut(10).eru)

    val result = program.unsafeRunSync()
    assertEquals(result, true)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(5))
  }

  test("flatMap composes with for-comprehension") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(10).unsafeRunSync()
    queue.tryPut(20).unsafeRunSync()

    val program: Eru[Nothing, Int] = for {
      a <- queue.tryTake.map(_.getOrElse(0)).eru
      b <- queue.tryTake.map(_.getOrElse(0)).eru
      _ <- queue.tryPut(a + b).eru
    } yield a + b

    val result = program.unsafeRunSync()
    assertEquals(result, 30)
    assertEquals(queue.tryTake.unsafeRunSync(), Some(30))
  }

  test("zip combines two immediate computations") {
    val ref1 = Eru.ref(10).unsafeRunSync()
    val ref2 = Eru.ref(20).unsafeRunSync()

    val combined = ref1.get.zip(ref2.get)
    val (a, b) = combined.unsafeRunSync()

    assertEquals(a, 10)
    assertEquals(b, 20)
  }

  test("orElse provides fallback on failure") {
    val failing: Immediate[String, Int] = new Immediate(Eru.fail("error"))
    val fallback: Immediate[String, Int] = new Immediate(Eru.succeed(42))

    val result = failing.orElse(fallback).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("orElse returns first value on success") {
    val success: Immediate[String, Int] = new Immediate(Eru.succeed(10))
    val fallback: Immediate[String, Int] = new Immediate(Eru.succeed(42))

    val result = success.orElse(fallback).unsafeRunSync()
    assertEquals(result, 10)
  }

  test("recover handles typed errors") {
    val failing: Immediate[String, Int] = new Immediate(Eru.fail("oops"))
    val recovered = failing.recover { case "oops" => 99 }

    assertEquals(recovered.unsafeRunSync(), 99)
  }

  test("recover leaves unmatched errors") {
    val failing: Immediate[String, Int] = new Immediate(Eru.fail("oops"))
    val recovered = failing.recover { case "different" => 99 }

    interceptMessage[EruException[String]]("oops") {
      recovered.unsafeRunSync()
    }
  }

  test("recoverWith handles errors with effects") {
    val ref = Eru.ref(0).unsafeRunSync()
    val failing: Immediate[String, Int] = new Immediate(Eru.fail("error"))

    val recovered = failing.recoverWith { case "error" =>
      ref.update(_ + 1).map(_ => 42)
    }

    assertEquals(recovered.unsafeRunSync(), 42)
    assertEquals(ref.get.unsafeRunSync(), 1)
  }

  test("attempt converts errors to Result") {
    val success: Immediate[String, Int] = new Immediate(Eru.succeed(42))
    val successResult = success.attempt.unsafeRunSync()
    assertEquals(successResult, Result.Success(42))

    val failure: Immediate[String, Int] = new Immediate(Eru.fail("error"))
    val failureResult = failure.attempt.unsafeRunSync()
    assertEquals(failureResult, Result.Failure("error"))
  }

  test("attempt eliminates error channel") {
    val failing: Immediate[String, Int] = new Immediate(Eru.fail("error"))
    val attempted: Immediate[Nothing, Result[String, Int]] = failing.attempt

    // Should not throw - error is captured in Result
    val result = attempted.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("suspending conversion widens to Suspending") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val immediate: Immediate[Nothing, Option[Int]] = queue.tryTake
    val _: Suspending[Nothing, Option[Int]] = immediate.suspending

    // Cannot call unsafeRunSync on Suspending - must use timeout or fork
    // This verifies the type safety property
  }

  test("Functor law: map identity") {
    val ref = Eru.ref(42).unsafeRunSync()
    val original = ref.get

    val mapped = original.map(identity)
    assertEquals(mapped.unsafeRunSync(), original.unsafeRunSync())
  }

  test("Functor law: map composition") {
    val ref = Eru.ref(10).unsafeRunSync()
    val original = ref.get

    val f: Int => Int = _ * 2
    val g: Int => Int = _ + 5

    val composed = original.map(f.andThen(g))
    val sequential = original.map(f).map(g)

    assertEquals(composed.unsafeRunSync(), sequential.unsafeRunSync())
  }

  test("Monad law: left identity") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    val a = 42
    val f: Int => Immediate[Nothing, Boolean] = x => queue.tryPut(x * 2)

    val left = new Immediate(Eru.succeed(a)).flatMap(f.andThen(_.eru))
    val right = f(a)

    assertEquals(left.unsafeRunSync(), right.unsafeRunSync())
  }

  test("Monad law: right identity") {
    val ref = Eru.ref(42).unsafeRunSync()
    val original = ref.get

    val leftSide = original.flatMap(a => new Immediate(Eru.succeed(a)).eru)
    assertEquals(leftSide.unsafeRunSync(), original.unsafeRunSync())
  }

  test("Monad law: associativity") {
    val ref = Eru.ref(5).unsafeRunSync()
    val m = ref.get
    val f: Int => Eru[Nothing, Int] = x => ref.update(_ + x).map(_ => x * 2)
    val g: Int => Eru[Nothing, Int] = x => ref.update(_ + x).map(_ => x + 10)

    // Reset for first test
    ref.set(5).unsafeRunSync()
    val left = m.flatMap(f).flatMap(g).unsafeRunSync()

    // Reset for second test
    ref.set(5).unsafeRunSync()
    val right = m.flatMap(a => new Immediate(f(a)).flatMap(g).eru).unsafeRunSync()

    assertEquals(left, right)
  }
}
