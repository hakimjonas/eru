package net.ghoula.eru

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import net.ghoula.eru.CorePrelude.*

/** Comprehensive verification of monad laws for the Eru effect type.
  *
  * This specification ensures that the Eru[E, A] type forms a proper monad by verifying the
  * fundamental monad laws through both example-based and property-based testing:
  *   1. Left Identity: Eru.succeed(a).flatMap(f) == f(a)
  *   2. Right Identity: eru.flatMap(Eru.succeed) == eru
  *   3. Associativity: eru.flatMap(f).flatMap(g) == eru.flatMap(a => f(a).flatMap(g))
  *   4. Functor laws: fmap(id) = id and fmap(f . g) = fmap(f) . fmap(g)
  *
  * Tests both success and failure cases to ensure the laws hold under all conditions, and validates
  * coherence between different combinators like map/flatMap and recover/recoverWith.
  */
final class MonadLawsSpec extends munit.ScalaCheckSuite {

  /** Generator for small positive integers to control test complexity. */
  private val smallInts: Gen[Int] = Gen.choose(1, 100)

  /** Generator for error strings. */
  private val errorStrings: Gen[String] = Gen.oneOf("error1", "error2", "network failure", "timeout")

  /** Generator for successful Eru effects. */
  private val successfulEru: Gen[Eru[String, Int]] = smallInts.map(Eru.succeed)

  /** Generator for failed Eru effects. */
  private val failedEru: Gen[Eru[String, Int]] = errorStrings.map(Eru.fail)

  /** Generator for arbitrary Eru effects. */
  private val arbitraryEru: Gen[Eru[String, Int]] = Gen.oneOf(successfulEru, failedEru)

  /** Generator for pure functions. */
  private val pureFunctions: Gen[Int => Int] = Gen.oneOf(
    Gen.const((x: Int) => x + 1),
    Gen.const((x: Int) => x * 2),
    Gen.const((x: Int) => x - 1)
  )

  /** Generator for functions that return Eru effects. */
  private val kleisliFunctions: Gen[Int => Eru[String, Int]] = Gen.oneOf(
    Gen.const((x: Int) => Eru.succeed(x + 1)),
    Gen.const((x: Int) => Eru.succeed(x * 2)),
    Gen.const((x: Int) => if (x > 50) Eru.fail("too large") else Eru.succeed(x))
  )

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

  // Property-based monad law tests

  property("Functor law: fmap(id) = id") {
    forAll(arbitraryEru) { eru =>
      val mapped = eru.map(identity).attempt.unsafeRunSync()
      val original = eru.attempt.unsafeRunSync()
      mapped == original
    }
  }

  property("Functor law: fmap(f . g) = fmap(f) . fmap(g)") {
    forAll(arbitraryEru, pureFunctions, pureFunctions) { (eru, f, g) =>
      val composed = eru.map(f.andThen(g)).attempt.unsafeRunSync()
      val sequential = eru.map(f).map(g).attempt.unsafeRunSync()
      composed == sequential
    }
  }

  property("Monad law: left identity - pure(a) >>= f = f(a)") {
    forAll(smallInts, kleisliFunctions) { (a, f) =>
      val left = Eru.succeed(a).flatMap(f).attempt.unsafeRunSync()
      val right = f(a).attempt.unsafeRunSync()
      left == right
    }
  }

  property("Monad law: right identity - m >>= pure = m") {
    forAll(arbitraryEru) { eru =>
      val left = eru.flatMap(Eru.succeed).attempt.unsafeRunSync()
      val right = eru.attempt.unsafeRunSync()
      left == right
    }
  }

  property("Monad law: associativity - (m >>= f) >>= g = m >>= (\\x -> f x >>= g)") {
    forAll(arbitraryEru, kleisliFunctions, kleisliFunctions) { (eru, f, g) =>
      val left = eru.flatMap(f).flatMap(g).attempt.unsafeRunSync()
      val right = eru.flatMap(a => f(a).flatMap(g)).attempt.unsafeRunSync()
      left == right
    }
  }

}
