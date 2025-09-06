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
  * Additionally verifies functor laws and other algebraic properties to ensure mathematical
  * correctness of the effect system.
  */
final class EruMonadLawsSpec extends FunSuite {

  private val testValue = 42
  private val testError = "test error"
  private val f: Int => Eru[String, String] = x => Eru.succeed(s"f($x)")
  private val g: String => Eru[String, Int] = s => Eru.succeed(s.length)
  private val h: Int => Int = _ * 2

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

  test("Functor Identity: eru.map(identity) == eru") {
    val originalEffect = Eru.succeed(testValue)
    val left = originalEffect.map(identity).unsafeRunSync()
    val right = originalEffect.unsafeRunSync()

    assertEquals(left, right)
  }

  test("Functor Composition: eru.map(f).map(g) == eru.map(f.andThen(g))") {
    val f = (x: Int) => x.toString
    val g = (s: String) => s.length

    val originalEffect = Eru.succeed(testValue)
    val left = originalEffect.map(f).map(g).unsafeRunSync()
    val right = originalEffect.map(f.andThen(g)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("Functor laws hold for failing effects") {
    val failingEffect = Eru.fail(testError)

    // Identity
    interceptMessage[EruException[String]](testError) {
      failingEffect.map(identity).unsafeRunSync()
    }

    // Composition
    interceptMessage[EruException[String]](testError) {
      failingEffect.map(h).map(_ + 1).unsafeRunSync()
    }
  }

  test("Applicative Identity: pure(identity) <*> v = v") {
    val effect = Eru.succeed(testValue)
    val identity = Eru.succeed((x: Int) => x)

    val left = identity.zip(effect).map { case (f, x) => f(x) }.unsafeRunSync()
    val right = effect.unsafeRunSync()

    assertEquals(left, right)
  }

  test("Applicative Composition: demonstrates function composition through zip") {
    val stringifier = Eru.succeed((x: Int) => x.toString)
    val lengthGetter = Eru.succeed((s: String) => s.length)
    val value = Eru.succeed(testValue)

    // Compose functions and apply to value
    val composed = stringifier
      .zip(lengthGetter)
      .zip(value)
      .map { case ((f, g), x) => g(f(x)) }
      .unsafeRunSync()

    // Apply functions sequentially
    val sequential = value
      .zip(stringifier)
      .map { case (x, f) => f(x) }
      .zip(lengthGetter)
      .map { case (intermediate, g) => g(intermediate) }
      .unsafeRunSync()

    assertEquals(composed, sequential)
    assertEquals(composed, testValue.toString.length)
  }

  test("map/flatMap coherence: eru.map(f) == eru.flatMap(f.andThen(Eru.succeed))") {
    val originalEffect = Eru.succeed(testValue)
    val f = (x: Int) => x.toString

    val left = originalEffect.map(f).unsafeRunSync()
    val right = originalEffect.flatMap(f.andThen(Eru.succeed)).unsafeRunSync()

    assertEquals(left, right)
  }

  test("flatMap/join coherence: eru.flatMap(f) == eru.map(f).join") {
    // Since Eru doesn't have a join method, we'll verify this through flatten behavior
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

  test("stack safety for deep flatMap chains") {
    // Platform-aware stack test depth for ARM architecture compatibility
    val stackTestDepth = {
      val arch = System.getProperty("os.arch")
      if (arch.startsWith("aarch64") || arch.startsWith("arm")) 5000 else 10000
    }

    def deepChain(n: Int): Eru[Nothing, Int] = {
      if (n <= 0) Eru.succeed(0)
      else Eru.succeed(n).flatMap(_ => deepChain(n - 1))
    }

    // This should not stack overflow
    val result = deepChain(stackTestDepth).unsafeRunSync()
    assertEquals(result, 0)
  }

  test("stack safety for deep map chains") {
    val stackTestDepth =
      if (System.getProperty("os.arch").startsWith("aarch64") || System.getProperty("os.arch").startsWith("arm")) 5000
      else 10000

    def deepMap(n: Int): Eru[Nothing, Int] =
      (0 until n).foldLeft(Eru.succeed(0)) { (acc, _) => acc.map(_ + 1) }

    val result = deepMap(stackTestDepth).unsafeRunSync()
    assertEquals(result, stackTestDepth)
  }
}
