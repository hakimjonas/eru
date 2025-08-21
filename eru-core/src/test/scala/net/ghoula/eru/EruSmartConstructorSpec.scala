package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Tests for smart constructor optimizations in Eru effect construction. These tests verify that
  * construction-time optimizations work correctly while maintaining identical semantics.
  */
class EruSmartConstructorSpec extends FunSuite {

  test("fromEither optimization creates direct Succeed/Fail") {
    val rightValue = 42
    val leftValue = "error"

    val rightEither = Right(rightValue)
    val leftEither = Left(leftValue)

    val succeedEru = Eru.fromEither(rightEither)
    val failEru = Eru.fromEither(leftEither)

    // Verify behavior is identical to manual construction
    assertEquals(succeedEru.unsafeRunSync(), rightValue)

    val caught = intercept[EruException[String]] {
      failEru.unsafeRunSync()
    }
    assertEquals(caught.error, leftValue)
  }

  test("enhanced flatMap chain composition preserves semantics") {
    val base = Eru.succeed(10)
    val f1 = (x: Int) => Eru.succeed(x * 2)
    val f2 = (x: Int) => Eru.succeed(x + 5)
    val f3 = (x: Int) => Eru.succeed(x.toString)

    // Chain multiple flatMap operations
    val chained = base.flatMap(f1).flatMap(f2).flatMap(f3)

    // Should produce the same result as manual composition
    val expected = ((10 * 2) + 5).toString
    assertEquals(chained.unsafeRunSync(), expected)
  }

  test("map chain fusion with succeed values") {
    val f1 = (x: Int) => x * 2
    val f2 = (x: Int) => x + 10
    val f3 = (x: Int) => x.toString

    // Chain multiple map operations on a succeed value
    val chained = Eru.succeed(5).map(f1).map(f2).map(f3)

    // Should produce the same result as manual composition
    val expected = ((5 * 2) + 10).toString
    assertEquals(chained.unsafeRunSync(), expected)
  }

  test("complex chain optimization preserves error handling") {
    val error = "test error"
    val base: Eru[String, Int] = Eru.fail(error)

    // Chain operations on a failed computation
    val chained = base
      .map(_ * 2)
      .flatMap(x => Eru.succeed(x + 1))
      .map(_.toString)

    // Should preserve the original error
    val caught = intercept[EruException[String]] {
      chained.unsafeRunSync()
    }
    assertEquals(caught.error, error)
  }

  test("mixed map and flatMap chains") {
    val base = Eru.succeed(1)

    val result = base
      .map(_ + 1) // 2
      .flatMap(x => Eru.succeed(x * 3)) // 6
      .map(_ + 4) // 10
      .flatMap(x => Eru.succeed(x.toString)) // "10"

    assertEquals(result.unsafeRunSync(), "10")
  }

  test("optimizations preserve stack safety") {
    // Create a deep chain to test stack safety
    def buildDeepChain(depth: Int): Eru[String, Int] = {
      if (depth <= 0) {
        Eru.succeed(0)
      } else {
        buildDeepChain(depth - 1).flatMap(x => Eru.succeed(x + 1))
      }
    }

    // This should not cause a stack overflow
    val deepChain = buildDeepChain(1000)
    assertEquals(deepChain.unsafeRunSync(), 1000)
  }

  test("fromEither with nested Either operations") {
    val nestedRight: Either[String, Either[Int, String]] = Right(Right("success"))
    val nestedLeft: Either[String, Either[Int, String]] = Right(Left(42))
    val outerLeft: Either[String, Either[Int, String]] = Left("outer error")

    val result1 = Eru.fromEither(nestedRight).flatMap(Eru.fromEither(_))
    val result2 = Eru.fromEither(nestedLeft).flatMap(Eru.fromEither(_))
    val result3 = Eru.fromEither(outerLeft).flatMap(Eru.fromEither(_))

    assertEquals(result1.unsafeRunSync(), "success")

    val caught2 = intercept[EruException[Int]] {
      result2.unsafeRunSync()
    }
    assertEquals(caught2.error, 42)

    val caught3 = intercept[EruException[String]] {
      result3.unsafeRunSync()
    }
    assertEquals(caught3.error, "outer error")
  }
}
