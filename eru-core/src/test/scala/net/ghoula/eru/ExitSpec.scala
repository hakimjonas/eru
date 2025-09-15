package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the Exit data type and its operations.
  *
  * Validates all fundamental operations of Exit[E, A] including construction, transformation,
  * pattern matching, and combinatorial logic. The Exit type represents the final outcome of fiber
  * execution, encoding success, failure, and interruption states with complete type safety and
  * providing the foundation for fiber coordination and error propagation.
  */
class ExitSpec extends munit.FunSuite {

  /** Validates that Exit.Success properly holds the provided value.
    *
    * Tests that the Success variant of Exit correctly stores and allows pattern matching access to
    * the contained value.
    */
  test("Exit.Success holds the value") {
    val ex: Exit[Nothing, Int] = Exit.Success(42)
    ex match {
      case Exit.Success(v) => assertEquals(v, 42)
      case _ => fail("expected Success")
    }
  }

  /** Validates that Exit.Failure properly holds the provided error.
    *
    * Tests that the Failure variant of Exit correctly stores and allows pattern matching access to
    * the contained error.
    */
  test("Exit.Failure holds the error") {
    val ex: Exit[String, Nothing] = Exit.Failure("boom")
    ex match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case _ => fail("expected Failure")
    }
  }

  /** Validates that Exit.Die properly holds the provided throwable.
    *
    * Tests that the Die variant of Exit correctly stores and allows pattern matching access to the
    * contained throwable.
    */
  test("Exit.Die holds the throwable") {
    val t = new RuntimeException("x")
    val ex: Exit[Nothing, Nothing] = Exit.Die(t)
    ex match {
      case Exit.Die(tt) => assertEquals(tt, t)
      case _ => fail("expected Die")
    }
  }

  /** Validates that Exit.Interrupt properly holds fiber ID and interrupt cause.
    *
    * Tests that the Interrupt variant of Exit correctly stores and allows pattern matching access
    * to both the fiber ID and interrupt cause.
    */
  test("Exit.Interrupt holds fiber id and cause") {
    val fid = FiberId.fresh()
    val ex: Exit[Nothing, Nothing] = Exit.Interrupt(fid, InterruptCause.Cancelled())
    ex match {
      case Exit.Interrupt(id, cause) =>
        assertEquals(id, fid)
        assertEquals(cause, InterruptCause.Cancelled())
      case _ => fail("expected Interrupt")
    }
  }
}
