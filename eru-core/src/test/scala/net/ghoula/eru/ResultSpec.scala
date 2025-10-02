package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the Result data type functionality.
  *
  * Validates all fundamental operations of Result[E, A] including construction, transformation,
  * error handling, and pattern matching semantics. The Result type serves as the core value-level
  * representation of computation outcomes in the Eru effect system, providing both success and
  * failure modeling with complete type safety.
  */
class ResultSpec extends munit.FunSuite {

  /** Validates that Result.succeed creates a Success with the given value.
    *
    * Tests that the succeed constructor properly creates a Success variant containing the provided
    * value with correct type and state.
    */
  test("Result.succeed creates a Success with the given value") {
    val result = Result.succeed(42)
    assertEquals(result, Result.Success(42))
    assert(result.isSuccess)
    assert(!result.isFailure)
  }

  /** Validates that Result.fail creates a Failure with the given error.
    *
    * Tests that the fail constructor properly creates a Failure variant containing the provided
    * error with correct type and state.
    */
  test("Result.fail creates a Failure with the given error") {
    val result = Result.fail("error")
    assertEquals(result, Result.Failure("error"))
    assert(result.isFailure)
    assert(!result.isSuccess)
  }

  /** Validates that map transforms Success values correctly.
    *
    * Tests that mapping over Success values applies the transformation function and produces a new
    * Success with the transformed value.
    */
  test("map transforms Success values") {
    val result = Result.succeed(5)
    val mapped = result.map(_ * 2)
    assertEquals(mapped, Result.Success(10))
  }

  /** Validates that map preserves Failure values unchanged.
    *
    * Tests that mapping over Failure values leaves them unchanged, preserving the error without
    * applying the transformation.
    */
  test("map preserves Failure values unchanged") {
    val result = Result.fail("error")
    val mapped = result.map((x: Int) => x * 2)
    assertEquals(mapped, Result.Failure("error"))
  }

  /** Validates that flatMap chains successful computations correctly.
    *
    * Tests that flatMap on Success values properly chains to the next computation and produces the
    * expected result.
    */
  test("flatMap chains successful computations") {
    val result = Result.succeed(5)
    val chained = result.flatMap(x => Result.succeed(x * 2))
    assertEquals(chained, Result.Success(10))
  }

  /** Validates that flatMap propagates the first failure in a chain.
    *
    * Tests that when the first Result in a flatMap chain is a failure, the failure is propagated
    * without executing the chaining function.
    */
  test("flatMap chains and propagates first failure") {
    val result = Result.fail("first error")
    val chained = result.flatMap((x: Int) => Result.succeed(x * 2))
    assertEquals(chained, Result.Failure("first error"))
  }

  /** Validates that flatMap propagates the second failure in a chain.
    *
    * Tests that when the chaining function in flatMap returns a failure, that failure is properly
    * propagated as the result.
    */
  test("flatMap chains and propagates second failure") {
    val result = Result.succeed(5)
    val chained = result.flatMap(_ => Result.fail("second error"))
    assertEquals(chained, Result.Failure("second error"))
  }

  /** Validates that fold applies the success function to Success values.
    *
    * Tests that fold operations correctly apply the ifSuccess function when the Result is a Success
    * variant.
    */
  test("fold applies ifSuccess function to Success values") {
    val result = Result.succeed(5)
    val folded = result.fold(_ => "failure", x => s"success: $x")
    assertEquals(folded, "success: 5")
  }

  /** Validates that fold applies the failure function to Failure values.
    *
    * Tests that fold operations correctly apply the ifFailure function when the Result is a Failure
    * variant.
    */
  test("fold applies ifFailure function to Failure values") {
    val result = Result.fail("error")
    val folded = result.fold(e => s"failure: $e", (x: Int) => s"success: $x")
    assertEquals(folded, "failure: error")
  }

  /** Validates that isSuccess returns true for Success values.
    *
    * Tests that the isSuccess predicate correctly identifies Success variants and returns the
    * appropriate boolean value.
    */
  test("isSuccess returns true for Success") {
    val result = Result.succeed("value")
    assert(result.isSuccess)
  }

  /** Validates that isSuccess returns false for Failure values.
    *
    * Tests that the isSuccess predicate correctly identifies Failure variants and returns the
    * appropriate boolean value.
    */
  test("isSuccess returns false for Failure") {
    val result = Result.fail("error")
    assert(!result.isSuccess)
  }

  /** Validates that isFailure returns true for Failure values.
    *
    * Tests that the isFailure predicate correctly identifies Failure variants and returns the
    * appropriate boolean value.
    */
  test("isFailure returns true for Failure") {
    val result = Result.fail("error")
    assert(result.isFailure)
  }

  /** Validates that isFailure returns false for Success values.
    *
    * Tests that the isFailure predicate correctly identifies Success variants and returns the
    * appropriate boolean value.
    */
  test("isFailure returns false for Success") {
    val result = Result.succeed("value")
    assert(!result.isFailure)
  }

  test("flatMap unifies error types correctly") {
    val result1: Result[String, Int] = Result.succeed(5)
    val result2: Result[RuntimeException, String] = Result.succeed("hello")

    val chained: Result[String | RuntimeException, String] = result1.flatMap(_ => result2)
    assertEquals(chained, Result.Success("hello"))
  }

  test("flatMap unifies error types with failure") {
    val result1: Result[String, Int] = Result.succeed(5)
    val error = new RuntimeException("runtime error")

    val chainedWithFailure: Result[String | RuntimeException, String] = result1.flatMap(_ => Result.fail(error))
    assertEquals(chainedWithFailure, Result.Failure(error))
  }

  test("complex chaining with map and flatMap") {
    val result = Result
      .succeed(10)
      .map(_ * 2)
      .flatMap(x => Result.succeed(x + 5))
      .map(_.toString)

    assertEquals(result, Result.Success("25"))
  }

  test("complex chaining with early failure") {
    val result = Result
      .succeed(10)
      .map(_ * 2)
      .flatMap(_ => Result.fail("computation failed"))
      .map((_: Int).toString)

    assertEquals(result, Result.Failure("computation failed"))
  }

  test("fold can return different types") {
    val successResult: Result[String, Int] = Result.succeed(42)
    val failureResult: Result[String, Int] = Result.fail("error")

    val successFolded: Boolean = successResult.fold(_ => false, _ => true)
    val failureFolded: Boolean = failureResult.fold(_ => false, _ => true)

    assert(successFolded)
    assert(!failureFolded)
  }

  test("factory methods work with Unit type") {
    val unitSuccess = Result.succeed(())
    val unitFailure = Result.fail(())

    assertEquals(unitSuccess, Result.Success(()))
    assertEquals(unitFailure, Result.Failure(()))
  }

  test("Result.fold transforms Success to output type") {
    val successResult = Result.Success(42)
    val folded = Result.fold(successResult)(e => s"Error: $e", a => s"Success: $a")
    assertEquals(folded, "Success: 42")
  }

  test("Result.fold transforms Failure to output type") {
    val failureResult = Result.Failure("bad input")
    val folded = Result.fold(failureResult)(e => s"Error: $e", a => s"Success: $a")
    assertEquals(folded, "Error: bad input")
  }

  test("Result.fold handles different input and output types") {
    val intResult: Result[String, Int] = Result.Success(100)
    val stringResult: Result[String, Int] = Result.Failure("overflow")

    val intFolded: Boolean = Result.fold(intResult)(_ => false, _ > 50)
    val stringFolded: Boolean = Result.fold(stringResult)(_ => false, _ > 50)

    assert(intFolded)
    assert(!stringFolded)
  }

  test("Result.toEru converts Success to successful Eru") {
    val result = Result.Success(42)
    val eru = Result.toEru(result)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Result.toEru converts Failure to failed Eru") {
    val result = Result.Failure("error message")
    val eru = Result.toEru(result)

    interceptMessage[EruException[String]]("error message") {
      eru.unsafeRunSync()
    }
  }

  test("Result.toEru preserves error and success types") {
    val stringError: Result[String, Int] = Result.Failure("string error")
    val intSuccess: Result[String, Int] = Result.Success(123)

    val eruFromError: Eru[String, Int] = Result.toEru(stringError)
    val eruFromSuccess: Eru[String, Int] = Result.toEru(intSuccess)

    assertEquals(eruFromSuccess.unsafeRunSync(), 123)
    interceptMessage[EruException[String]]("string error") {
      eruFromError.unsafeRunSync()
    }
  }

  test("Result.toExit converts Success to Exit.Success") {
    val result = Result.Success("value")
    val exit = Result.toExit(result)
    assertEquals(exit, Exit.Success("value"))
  }

  test("Result.toExit converts Throwable Failure to Exit.Die") {
    val throwable = new RuntimeException("runtime error")
    val result = Result.Failure(throwable)
    val exit = Result.toExit(result)
    assertEquals(exit, Exit.Die(throwable))
  }

  test("Result.toExit converts typed Failure to Exit.Failure") {
    val result = Result.Failure("typed error")
    val exit = Result.toExit(result)
    assertEquals(exit, Exit.Failure("typed error"))
  }

  test("Result.toExit handles mixed error types correctly") {
    val typedError: Result[String, Int] = Result.Failure("business error")
    val exception = new IllegalArgumentException("invalid")
    val throwableError: Result[Throwable, Int] = Result.Failure(exception)
    val success: Result[String, Int] = Result.Success(42)

    val typedExit = Result.toExit(typedError)
    val throwableExit = Result.toExit(throwableError)
    val successExit = Result.toExit(success)

    assertEquals(typedExit, Exit.Failure("business error"))
    assertEquals(throwableExit, Exit.Die(exception))
    assertEquals(successExit, Exit.Success(42))
  }

  test("Result extension method toEru converts Success to successful Eru") {
    val result = Result.succeed(42)
    val eru = result.toEru
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Result extension method toEru converts Failure to failed Eru") {
    val result = Result.fail("error")
    val eru = result.toEru
    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("Result extension method toExit converts Success to Exit.Success") {
    val result = Result.succeed(42)
    val exit = result.toExit
    assertEquals(exit, Exit.Success(42))
  }

  test("Result extension method toExit converts typed Failure to Exit.Failure") {
    val result = Result.fail("error")
    val exit = result.toExit
    assertEquals(exit, Exit.Failure("error"))
  }

  test("Result extension method toExit converts Throwable Failure to Exit.Die") {
    val throwable = new RuntimeException("boom")
    val result = Result.fail(throwable)
    val exit = result.toExit
    assertEquals(exit, Exit.Die(throwable))
  }
}
