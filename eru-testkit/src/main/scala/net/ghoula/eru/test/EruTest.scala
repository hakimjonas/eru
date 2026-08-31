package net.ghoula.eru.test

import java.time.Duration

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Testing utilities for Eru effects with controllable time and assertions.
  *
  * EruTest provides testing utilities for effect-based code: TestClock integration via
  * `withTestClock`/`testRuntime`, and specialized assertions for common effect testing patterns.
  *
  * Key features:
  *   - **Controllable time**: withTestClock provides a TestClock for time control
  *   - **Effect assertions**: assertCompletes, assertFails, assertInterrupts
  *
  * '''Design Principles:'''
  *   - **Composable**: Utilities work together and with existing test frameworks
  *   - **Type-safe**: Precise error type checking and effect result validation
  *
  * @example
  *   {{{
  * import net.ghoula.eru.test.EruTest
  *
  * // Test a failing effect
  * EruTest.withTestClock { clock =>
  *   given runtime: EruRuntime = EruTest.testRuntime(clock)
  *
  *   val failing = Eru.fail("boom")
  *   EruTest.assertFails(failing, "boom")
  * }
  *   }}}
  */
object EruTest {

  /** Executes a test function with a controllable TestClock.
    *
    * Creates a fresh TestClock, passes it to the test function, and completes all pending clock
    * operations when the test returns. It does not install a runtime: pair it with
    * `testRuntime(clock)` (TestClockBackend) to run effects against the clock.
    *
    * @param test
    *   the test function to execute, receiving a TestClock instance
    * @tparam A
    *   the result type of the test function
    * @return
    *   the result of the test function
    *
    * @example
    *   {{{
    * EruTest.withTestClock { clock =>
    *   given runtime: EruRuntime = EruTest.testRuntime(clock)
    *
    *   val effect = Eru.succeed("done")
    *   val fiber = effect.fork.unsafeRunSync()
    *
    *   assert(fiber.await.unsafeRunSync() == Exit.Success("done"))
    * }
    *   }}}
    */
  def withTestClock[A](test: TestClock => A): A = {
    val clock = TestClock.create()
    try {
      test(clock)
    } finally {
      clock.completeAll
    }
  }

  /** Executes a test function with a TestClock starting at the specified time.
    *
    * Similar to withTestClock() but allows specifying the initial logical time. Useful for tests
    * that need to start at specific timestamps.
    *
    * @param startTime
    *   the initial logical time for the TestClock
    * @param test
    *   the test function to execute
    * @tparam A
    *   the result type of the test function
    * @return
    *   the result of the test function
    */
  def withTestClock[A](startTime: java.time.Instant)(test: TestClock => A): A = {
    val clock = TestClock.create(startTime)
    try {
      test(clock)
    } finally {
      clock.completeAll
    }
  }

  /** Asserts that an effect completes successfully.
    *
    * This assertion verifies that:
    *   1. The effect completes (doesn't hang indefinitely)
    *   2. The effect succeeds (doesn't fail or throw)
    *
    * If the effect throws, the assertion reports a timeout when wall-clock elapsed time has passed
    * `timeout`, and an unexpected exception otherwise. A hanging effect is not interrupted by this
    * assertion.
    *
    * @param effect
    *   the effect to test for completion
    * @param timeout
    *   maximum wall-clock time to wait for completion
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @return
    *   the successful result of the effect
    * @throws java.lang.AssertionError
    *   if the effect fails, throws, or times out
    *
    * @example
    *   {{{
    * val effect = Eru.succeed(42).map(_ * 2)
    * val result = EruTest.assertCompletes(effect, Duration.ofSeconds(1))
    * assert(result == 84)
    *   }}}
    */
  def assertCompletes[E, A](effect: Eru[E, A], timeout: Duration): A = {
    val deadline = java.time.Instant.now().plus(timeout)

    try {
      val exit = effect.runExit()

      exit match {
        case Exit.Success(value) => value
        case Exit.Failure(error) =>
          throw new AssertionError(s"Effect failed with typed error: $error")
        case Exit.Die(throwable) =>
          throw new AssertionError(s"Effect died with throwable: ${throwable.getMessage}", throwable)
        case Exit.Interrupt(fiberId, cause) =>
          throw new AssertionError(s"Effect was interrupted: fiber=$fiberId, cause=$cause")
      }
    } catch {
      case ae: AssertionError => throw ae
      case t: Throwable =>
        if (java.time.Instant.now().isAfter(deadline)) {
          throw new AssertionError(s"Effect timed out after $timeout", t)
        } else {
          throw new AssertionError(s"Effect threw unexpected exception: ${t.getMessage}", t)
        }
    }
  }

  /** Asserts that an effect fails with the expected error.
    *
    * This assertion verifies that:
    *   1. The effect completes (doesn't hang)
    *   2. The effect fails with a typed error (not a defect/throwable)
    *   3. The error matches the expected value
    *
    * @param effect
    *   the effect to test for failure
    * @param expected
    *   the expected error value
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @throws java.lang.AssertionError
    *   if the effect succeeds, throws, or fails with a different error
    *
    * @example
    *   {{{
    * val effect = Eru.fail("boom").map(_ => "success")
    * EruTest.assertFails(effect, "boom")
    *   }}}
    */
  def assertFails[E, A](effect: Eru[E, A], expected: E): Unit = {
    val exit = effect.runExit()

    exit match {
      case Exit.Failure(error) =>
        if (error != expected) {
          throw new AssertionError(s"Effect failed with wrong error. Expected: $expected, Actual: $error")
        }
      case Exit.Success(value) =>
        throw new AssertionError(s"Effect succeeded unexpectedly with value: $value")
      case Exit.Die(throwable) =>
        throw new AssertionError(
          s"Effect died with throwable instead of typed error: ${throwable.getMessage}",
          throwable
        )
      case Exit.Interrupt(fiberId, cause) =>
        throw new AssertionError(s"Effect was interrupted instead of failing: fiber=$fiberId, cause=$cause")
    }
  }

  /** Asserts that an effect fails with an error of the specified type.
    *
    * This is useful when testing effects that can fail with throwables or when the exact error
    * value is not predictable but the error type is known.
    *
    * @param effect
    *   the effect to test for failure
    * @param expectedErrorClass
    *   the expected class/type of the error
    * @tparam E
    *   the error type of the effect (a subtype of Throwable)
    * @tparam A
    *   the success type of the effect
    * @throws java.lang.AssertionError
    *   if the effect succeeds or fails with a different error type
    *
    * @example
    *   {{{
    * val effect = Eru.effect(throw new IllegalArgumentException("invalid"))
    * EruTest.assertFails(effect, classOf[IllegalArgumentException])
    *   }}}
    */
  def assertFails[E <: Throwable, A](effect: Eru[E, A], expectedErrorClass: Class[? <: E]): Unit = {
    val exit = effect.runExit()

    exit match {
      case Exit.Failure(error) =>
        if (!expectedErrorClass.isInstance(error)) {
          throw new AssertionError(
            s"Effect failed with wrong error type. Expected: ${expectedErrorClass.getName}, Actual: ${error.getClass.getName}"
          )
        }
      case Exit.Die(throwable) =>
        if (!expectedErrorClass.isInstance(throwable)) {
          throw new AssertionError(
            s"Effect died with wrong throwable type. Expected: ${expectedErrorClass.getName}, Actual: ${throwable.getClass.getName}"
          )
        }
      case Exit.Success(value) =>
        throw new AssertionError(s"Effect succeeded unexpectedly with value: $value")
      case Exit.Interrupt(fiberId, cause) =>
        throw new AssertionError(s"Effect was interrupted instead of failing: fiber=$fiberId, cause=$cause")
    }
  }

  /** Asserts that an effect gets interrupted with the expected cause.
    *
    * This assertion verifies fiber interruption behavior for cancellation, timeout, and resource
    * cleanup scenarios.
    *
    * @param effect
    *   the effect to test for interruption
    * @param expectedCause
    *   the expected interruption cause (optional)
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @throws java.lang.AssertionError
    *   if the effect succeeds, fails, or is interrupted with a different cause
    *
    * @example
    *   {{{
    * val fiber = longRunningEffect.fork.unsafeRunSync()
    * fiber.interrupt(InterruptCause.Cancelled())
    * EruTest.assertInterrupts(fiber.await, Some(InterruptCause.Cancelled()))
    *   }}}
    */
  def assertInterrupts[E, A](effect: Eru[E, A], expectedCause: Option[InterruptCause] = None): Unit = {
    val exit = effect.runExit()

    exit match {
      case Exit.Interrupt(_, cause) =>
        expectedCause match {
          case Some(expected) =>
            if (cause != expected) {
              throw new AssertionError(s"Effect interrupted with wrong cause. Expected: $expected, Actual: $cause")
            }
          case None =>
        }
      case Exit.Success(value) =>
        throw new AssertionError(s"Effect succeeded unexpectedly with value: $value")
      case Exit.Failure(error) =>
        throw new AssertionError(s"Effect failed with typed error instead of being interrupted: $error")
      case Exit.Die(throwable) =>
        throw new AssertionError(
          s"Effect died with throwable instead of being interrupted: ${throwable.getMessage}",
          throwable
        )
    }
  }

  /** Asserts that an effect completes successfully and returns the expected value.
    *
    * Combines completion and value checking in one assertion for convenience.
    *
    * @param effect
    *   the effect to test
    * @param expected
    *   the expected result value
    * @param timeout
    *   maximum time to wait for completion
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @throws java.lang.AssertionError
    *   if the effect fails, throws, times out, or returns a different value
    */
  def assertSucceedsWith[E, A](effect: Eru[E, A], expected: A, timeout: Duration = Duration.ofSeconds(5)): Unit = {
    val actual = assertCompletes(effect, timeout)
    if (actual != expected) {
      throw new AssertionError(s"Effect completed with wrong value. Expected: $expected, Actual: $actual")
    }
  }

  /** Creates a runtime configured for testing with the provided TestClock.
    *
    * The runtime uses TestClockBackend.
    *
    * @param clock
    *   the TestClock to use for timing operations
    * @return
    *   a new EruRuntime configured for testing
    */
  def testRuntime(clock: TestClock): EruRuntime =
    EruRuntime.withBackend(TestClockBackend(clock))

  /** Creates a runtime with a fresh TestClock for testing.
    *
    * @return
    *   tuple of (TestClock, EruRuntime) for convenient testing
    */
  def testRuntimeWithClock(): (TestClock, EruRuntime) = {
    val clock = TestClock.create()
    (clock, testRuntime(clock))
  }
}
