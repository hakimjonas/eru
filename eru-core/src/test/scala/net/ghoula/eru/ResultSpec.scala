package net.ghoula.eru

import munit.FunSuite

class ResultSpec extends FunSuite {

  test("Result.succeed creates a Success with the given value") {
    val result = Result.succeed(42)
    assertEquals(result, Result.Success(42))
    assert(result.isSuccess)
    assert(!result.isFailure)
  }

  test("Result.fail creates a Failure with the given error") {
    val result = Result.fail("error")
    assertEquals(result, Result.Failure("error"))
    assert(result.isFailure)
    assert(!result.isSuccess)
  }

  test("map transforms Success values") {
    val result = Result.succeed(5)
    val mapped = result.map(_ * 2)
    assertEquals(mapped, Result.Success(10))
  }

  test("map preserves Failure values unchanged") {
    val result = Result.fail("error")
    val mapped = result.map((x: Int) => x * 2)
    assertEquals(mapped, Result.Failure("error"))
  }

  test("flatMap chains successful computations") {
    val result = Result.succeed(5)
    val chained = result.flatMap(x => Result.succeed(x * 2))
    assertEquals(chained, Result.Success(10))
  }

  test("flatMap chains and propagates first failure") {
    val result = Result.fail("first error")
    val chained = result.flatMap((x: Int) => Result.succeed(x * 2))
    assertEquals(chained, Result.Failure("first error"))
  }

  test("flatMap chains and propagates second failure") {
    val result = Result.succeed(5)
    val chained = result.flatMap(_ => Result.fail("second error"))
    assertEquals(chained, Result.Failure("second error"))
  }

  test("fold applies ifSuccess function to Success values") {
    val result = Result.succeed(5)
    val folded = result.fold(_ => "failure", x => s"success: $x")
    assertEquals(folded, "success: 5")
  }

  test("fold applies ifFailure function to Failure values") {
    val result = Result.fail("error")
    val folded = result.fold(e => s"failure: $e", (x: Int) => s"success: $x")
    assertEquals(folded, "failure: error")
  }

  test("isSuccess returns true for Success") {
    val result = Result.succeed("value")
    assert(result.isSuccess)
  }

  test("isSuccess returns false for Failure") {
    val result = Result.fail("error")
    assert(!result.isSuccess)
  }

  test("isFailure returns true for Failure") {
    val result = Result.fail("error")
    assert(result.isFailure)
  }

  test("isFailure returns false for Success") {
    val result = Result.succeed("value")
    assert(!result.isFailure)
  }

  test("Result is covariant in error type") {
    val stringError: Result[String, Int] = Result.fail("error")
    val anyError: Result[Any, Int] = stringError
    assertEquals(anyError, Result.Failure("error"))
  }

  test("Result is covariant in success type") {
    val stringValue: Result[String, String] = Result.succeed("value")
    val anyValue: Result[String, Any] = stringValue
    assertEquals(anyValue, Result.Success("value"))
  }

  test("map preserves error type covariance") {
    val result: Result[String, Int] = Result.succeed(5)
    val mapped: Result[String, String] = result.map(_.toString)
    assertEquals(mapped, Result.Success("5"))
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
}
