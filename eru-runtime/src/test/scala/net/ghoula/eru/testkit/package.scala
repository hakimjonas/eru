package net.ghoula.eru.testkit

import munit.*
import net.ghoula.eru.prelude.*

/** A test suite trait that provides ergonomic testing for Eru effects.
  *
  * This trait extends MUnit's FunSuite and adds the `testE` method that allows test bodies to
  * return Eru effects directly, removing the need to call `.run()` or `unsafeRunSync()` in test
  * code.
  *
  * Example usage:
  * {{{
  * class MySpec extends EruSuite {
  *   testE("effect-based test") {
  *     for {
  *       result <- Eru.succeed(42)
  *       _      <- Eru.effect(assertEquals(result, 42))
  *     } yield ()
  *   }
  * }
  * }}}
  */
trait EruSuite extends FunSuite {
  
  /** Defines a test case that accepts an Eru effect as its body.
    *
    * This method removes the friction of calling `.run()` or `unsafeRunSync()` in test bodies,
    * making effect-based tests more ergonomic while maintaining the explicit execution boundary
    * in the test framework integration.
    *
    * @param name
    *   the test name
    * @param body
    *   the test body as an Eru effect (by-name for lazy evaluation)
    * @param loc
    *   implicit location information for test reporting
    */
  def testE(name: String)(body: => Eru[Throwable, Any])(using Location): Unit =
    test(name) { body.unsafeRunSync(); () }
}

/** Assertion utilities for testing Eru effects.
  *
  * This trait provides assertion methods specifically designed for testing Eru effects,
  * removing the need to manually execute effects in assertion code.
  *
  * Mix this trait into your test suites alongside Assertions to gain access to effect-specific
  * assertion methods.
  */
trait EruAssertions { self: Assertions =>
  
  /** Asserts that an effect runs and produces a value equal to the expected value.
    *
    * @param eff
    *   the effect to execute and test
    * @param expected
    *   the expected result value
    * @param loc
    *   implicit location information for test reporting
    * @tparam A
    *   the type of the effect's success value
    */
  def assertRunsEquals[A](eff: Eru[Throwable, A], expected: A)(using Location): Unit =
    assertEquals(eff.unsafeRunSync(), expected)

  /** Asserts that an effect runs and produces a value that satisfies the given predicate.
    *
    * @param eff
    *   the effect to execute and test
    * @param predicate
    *   the predicate to test the result against
    * @param clue
    *   optional clue for test failure messages
    * @param loc
    *   implicit location information for test reporting
    * @tparam A
    *   the type of the effect's success value
    */
  def assertRuns[A](eff: Eru[Throwable, A])(predicate: A => Boolean, clue: String = "")(using Location): Unit =
    assert(predicate(eff.unsafeRunSync()), clue)

  /** Intercepts an exception of the specified type when running an effect.
    *
    * This method is useful for testing that effects fail with expected exceptions.
    *
    * @param eff
    *   the effect expected to fail
    * @param m
    *   manifest for the exception type
    * @param loc
    *   implicit location information for test reporting
    * @tparam T
    *   the type of exception to intercept
    * @return
    *   the intercepted exception
    */
  def interceptRun[T <: Throwable](eff: Eru[Throwable, ?])(using m: Manifest[T], loc: Location): T =
    intercept[T](eff.unsafeRunSync())
}