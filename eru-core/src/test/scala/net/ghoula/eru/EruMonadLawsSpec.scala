package net.ghoula.eru

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import net.ghoula.eru.prelude.*

final class EruMonadLawsSpec extends ScalaCheckSuite {

  property("Monad law - left identity: Eru.succeed(a).flatMap(f) == f(a)") {
    forAll { (a: Int) =>
      val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x + 1)
      val lhs = Eru.succeed(a).flatMap(f).unsafeRunSync()
      val rhs = f(a).unsafeRunSync()
      lhs == rhs
    }
  }

  property("Monad law - right identity: eru.flatMap(Eru.succeed) == eru") {
    forAll { (a: Int) =>
      val eru: Eru[Nothing, Int] = Eru.succeed(a)
      val lhs = eru.flatMap(Eru.succeed).unsafeRunSync()
      val rhs = eru.unsafeRunSync()
      lhs == rhs
    }
  }

  property("Monad law - associativity: (m flatMap f) flatMap g == m flatMap (x => f(x) flatMap g)") {
    forAll { (a: Int) =>
      val m: Eru[Nothing, Int] = Eru.succeed(a)
      val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x + 1)
      val g: Int => Eru[Nothing, Int] = x => Eru.succeed(x * 2)

      val lhs = m.flatMap(f).flatMap(g).unsafeRunSync()
      val rhs = m.flatMap(x => f(x).flatMap(g)).unsafeRunSync()
      lhs == rhs
    }
  }
}
