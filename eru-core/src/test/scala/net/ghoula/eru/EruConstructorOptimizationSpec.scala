package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for constructor optimizations in Eru effect construction.
  *
  * Validates that construction-time optimizations maintain identical semantics to manual
  * construction while providing performance benefits through compile-time fusion and chain
  * optimization. Tests cover flatMap chaining, map fusion, error propagation, and stack safety of
  * optimized operations.
  */
class EruConstructorOptimizationSpec extends munit.FunSuite {

  /** Validates that fromEither optimization creates direct Succeed/Fail instances.
    *
    * Tests that Either values are converted to their corresponding Eru effects through optimized
    * construction paths, maintaining semantic equivalence to manual construction.
    */
  test("fromEither optimization creates direct Succeed/Fail") {
    val rightValue = 42
    val leftValue = "error"

    val rightEither = Right(rightValue)
    val leftEither = Left(leftValue)

    val succeedEru = Eru.fromEither(rightEither)
    val failEru = Eru.fromEither(leftEither)
    assertEquals(succeedEru.unsafeRunSync(), rightValue)

    val caught = intercept[EruException[String]] {
      failEru.unsafeRunSync()
    }
    assertEquals(caught.error, leftValue)
  }

  /** Validates that enhanced flatMap chain composition preserves semantics.
    *
    * Tests that multiple flatMap operations can be chained together while maintaining correct
    * computation semantics and producing the expected composed result.
    */
  test("enhanced flatMap chain composition preserves semantics") {
    val base = Eru.succeed(10)
    val f1 = (x: Int) => Eru.succeed(x * 2)
    val f2 = (x: Int) => Eru.succeed(x + 5)
    val f3 = (x: Int) => Eru.succeed(x.toString)

    val chained = base.flatMap(f1).flatMap(f2).flatMap(f3)
    val expected = ((10 * 2) + 5).toString
    assertEquals(chained.unsafeRunSync(), expected)
  }

  /** Validates that map chain fusion optimizes succeed values correctly.
    *
    * Tests that multiple map operations on successful values are properly fused and produce the
    * same result as manual function composition.
    */
  test("map chain fusion with succeed values") {
    val f1 = (x: Int) => x * 2
    val f2 = (x: Int) => x + 10
    val f3 = (x: Int) => x.toString

    val chained = Eru.succeed(5).map(f1).map(f2).map(f3)
    val expected = ((5 * 2) + 10).toString
    assertEquals(chained.unsafeRunSync(), expected)
  }

  /** Validates that complex chain optimization preserves error handling semantics.
    *
    * Tests that optimization does not interfere with proper error propagation when chaining
    * operations on failed computations.
    */
  test("complex chain optimization preserves error handling") {
    val error = "test error"
    val base: Eru[String, Int] = Eru.fail(error)

    val chained = base
      .map(_ * 2)
      .flatMap(x => Eru.succeed(x + 1))
      .map(_.toString)
    val caught = intercept[EruException[String]] {
      chained.unsafeRunSync()
    }
    assertEquals(caught.error, error)
  }

  /** Validates that mixed map and flatMap chains work correctly.
    *
    * Tests that alternating map and flatMap operations in a chain produce the expected composed
    * result through proper optimization.
    */
  test("mixed map and flatMap chains") {
    val base = Eru.succeed(1)

    val result = base
      .map(_ + 1)
      .flatMap(x => Eru.succeed(x * 3))
      .map(_ + 4)
      .flatMap(x => Eru.succeed(x.toString))

    assertEquals(result.unsafeRunSync(), "10")
  }

  /** Validates that optimizations preserve stack safety for deep chains.
    *
    * Tests that optimization does not introduce stack overflow issues when processing deeply nested
    * chains of operations.
    */
  test("optimizations preserve stack safety") {
    def buildDeepChain(depth: Int): Eru[String, Int] = {
      if (depth <= 0) {
        Eru.succeed(0)
      } else {
        buildDeepChain(depth - 1).flatMap(x => Eru.succeed(x + 1))
      }
    }
    val deepChain = buildDeepChain(1000)
    assertEquals(deepChain.unsafeRunSync(), 1000)
  }

  /** Validates that fromEither handles nested Either operations correctly.
    *
    * Tests that complex Either nesting scenarios are properly handled through fromEither
    * optimization while maintaining correct error propagation semantics.
    */
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
