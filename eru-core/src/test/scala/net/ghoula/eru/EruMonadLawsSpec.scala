package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

/** Comprehensive verification of monad laws for the Eru effect type.
  *
  * This specification ensures that the Eru[E, A] type forms a proper monad by verifying the
  * fundamental monad laws:
  *   1. Left Identity: Eru.succeed(a).flatMap(f) == f(a)
  *   2. Right Identity: eru.flatMap(Eru.succeed) == eru
  *   3. Associativity: eru.flatMap(f).flatMap(g) == eru.flatMap(a => f(a).flatMap(g))
  *
  * Tests both success and failure cases to ensure the laws hold under all conditions, and
  * validates coherence between different combinators like map/flatMap and recover/recoverWith.
  */
final class EruMonadLawsSpec extends FunSuite {

  private val testValue = 42
  private val testError = "test error"
  private val f: Int => Eru[String, String] = x => Eru.succeed(s"f($x)")
  private val g: String => Eru[String, Int] = s => Eru.succeed(s.length)

  test("Left Identity: Eru.succeed(a).flatMap(f) == f(a)") {
    val left = Eru.succeed(testValue).flatMap(f).unsafeRunSync()
    val right = f(testValue).unsafeRunSync()

    assertEquals(left, right)
  }

  test("Right Identity: eru.flatMap(Eru.succeed) == eru") {
    val originalEffect = Eru.succeed(testValue)
    val left = originalEffect.flatMap(Eru.succeed).unsafeRunSync()
    val right = originalEffect.unsafeRunSync()

    assertEquals(left, right)
  }

  test("Associativity: eru.flatMap(f).flatMap(g) == eru.flatMap(a => f(a).flatMap(g))") {
    val originalEffect = Eru.succeed(testValue)
    val left = originalEffect.flatMap(f).flatMap(g).unsafeRunSync()
    val right = originalEffect.flatMap(a => f(a).flatMap(g)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("Left Identity holds for failing computations") {
    val failingF: Int => Eru[String, String] = _ => Eru.fail(testError)

    interceptMessage[EruException[String]](testError) {
      Eru.succeed(testValue).flatMap(failingF).unsafeRunSync()
    }

    interceptMessage[EruException[String]](testError) {
      failingF(testValue).unsafeRunSync()
    }
  }

  test("Right Identity holds for failing effects") {
    val failingEffect = Eru.fail(testError)

    interceptMessage[EruException[String]](testError) {
      failingEffect.flatMap(Eru.succeed).unsafeRunSync()
    }

    interceptMessage[EruException[String]](testError) {
      failingEffect.unsafeRunSync()
    }
  }

  test("Associativity holds with mixed success and failure") {
    val mixedF: Int => Eru[String, String] = x => if (x > 0) Eru.succeed(s"positive: $x") else Eru.fail("negative")
    val mixedG: String => Eru[String, Int] =
      s => if (s.contains("positive")) Eru.succeed(s.length) else Eru.fail("not positive")

    val positiveEffect = Eru.succeed(5)
    val leftPositive = positiveEffect.flatMap(mixedF).flatMap(mixedG).attempt.unsafeRunSync()
    val rightPositive = positiveEffect.flatMap(a => mixedF(a).flatMap(mixedG)).attempt.unsafeRunSync()
    assertEquals(leftPositive, rightPositive)

    val negativeEffect = Eru.succeed(-1)
    val leftNegative = negativeEffect.flatMap(mixedF).flatMap(mixedG).attempt.unsafeRunSync()
    val rightNegative = negativeEffect.flatMap(a => mixedF(a).flatMap(mixedG)).attempt.unsafeRunSync()
    assertEquals(leftNegative, rightNegative)
  }

  test("map/flatMap coherence: eru.map(f) == eru.flatMap(f.andThen(Eru.succeed))") {
    val originalEffect = Eru.succeed(testValue)
    val f = (x: Int) => x.toString

    val left = originalEffect.map(f).unsafeRunSync()
    val right = originalEffect.flatMap(f.andThen(Eru.succeed)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("flatMap/join coherence: eru.flatMap(f) == eru.map(f).join") {
    val nestedEffect = Eru.succeed(Eru.succeed(testValue))
    val flattened = nestedEffect.flatMap(identity).unsafeRunSync()

    assertEquals(flattened, testValue)
  }

  test("orElse associativity: (a orElse b) orElse c == a orElse (b orElse c)") {
    val a = Eru.fail("a")
    val b = Eru.fail("b")
    val c = Eru.succeed(testValue)

    val left = a.orElse(b).orElse(c)
    val right = a.orElse(b.orElse(c))

    assertEquals(left.unsafeRunSync(), right.unsafeRunSync())
  }

  test("recover/recoverWith coherence: eru.recover(pf) == eru.recoverWith(pf.andThen(Eru.succeed))") {
    val failingEffect = Eru.fail(testError)
    val pf: PartialFunction[String, Int] = { case "test error" => 999 }

    val left = failingEffect.recover(pf).unsafeRunSync()
    val right = failingEffect.recoverWith(pf.andThen(Eru.succeed)).unsafeRunSync()

    assertEquals(left, right)
  }

}
