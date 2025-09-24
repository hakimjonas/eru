package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the EruException wrapper class.
  *
  * Validates all functionality of EruException including construction, error wrapping, string
  * representation, message handling, and companion object factory methods. EruException serves as
  * the bridge between typed errors and the JVM exception system.
  */
class EruExceptionSpec extends munit.FunSuite {

  test("EruException constructor wraps error correctly") {
    val error = "test error"
    val exception = new EruException(error)

    assertEquals(exception.error, error)
  }

  test("EruException.apply factory method creates instance") {
    val error = 42
    val exception = EruException(error)

    assertEquals(exception.error, error)
    // Type is verified at compile time - EruException[Int]
    val _: EruException[Int] = exception
  }

  test("EruException toString includes wrapped error") {
    val error = "network failure"
    val exception = EruException(error)

    assertEquals(exception.toString, "EruException(network failure)")
  }

  test("EruException toString handles complex error types") {
    val error = List("error1", "error2", "error3")
    val exception = EruException(error)

    assertEquals(exception.toString, "EruException(List(error1, error2, error3))")
  }

  test("EruException getMessage returns error string representation") {
    val error = "validation failed"
    val exception = EruException(error)

    assertEquals(exception.getMessage, "validation failed")
  }

  test("EruException getMessage handles None as error") {
    val exception = new EruException[Option[String]](None)

    assertEquals(exception.getMessage, "None")
  }

  test("EruException getMessage handles complex error objects") {
    case class CustomError(code: Int, message: String)
    val error = CustomError(404, "Not Found")
    val exception = EruException(error)

    assertEquals(exception.getMessage, "CustomError(404,Not Found)")
  }

  test("EruException is a RuntimeException") {
    val exception = EruException("test")

    // Type hierarchy is verified at compile time
    val _: RuntimeException = exception
    val _: Exception = exception
    val _: Throwable = exception

    // Runtime verification through successful catch blocks
    val caught1 =
      try {
        throw exception
      } catch {
        case _: RuntimeException => true
      }
    assert(caught1, "Should be caught as RuntimeException")
  }

  test("EruException maintains type information") {
    val stringException = EruException("string error")
    val intException = EruException(42)
    val listException = EruException(List(1, 2, 3))

    // Type information is preserved at compile time
    val stringError: String = stringException.error
    val intError: Int = intException.error
    val listError: List[Int] = listException.error

    assertEquals(stringError, "string error")
    assertEquals(intError, 42)
    assertEquals(listError, List(1, 2, 3))
  }

  test("EruException with unit error") {
    val exception = EruException(())

    assertEquals(exception.error, ())
    assertEquals(exception.getMessage, "()")
    assertEquals(exception.toString, "EruException(())")
  }

  test("EruException supports pattern matching on error") {
    sealed trait AppError
    case class ValidationError(field: String) extends AppError
    case class NetworkError(code: Int) extends AppError

    val validationException = EruException(ValidationError("email"))
    val networkException = EruException(NetworkError(500))

    validationException.error match {
      case ValidationError(field) => assertEquals(field, "email")
    }

    networkException.error match {
      case NetworkError(code) => assertEquals(code, 500)
    }
  }

  test("EruException can be thrown and caught") {
    val error = "thrown error"

    val caughtException = intercept[EruException[String]] {
      throw EruException(error)
    }

    assertEquals(caughtException.error, error)
    assertEquals(caughtException.getMessage, error)
  }

  test("EruException stack trace behavior") {
    val exception = EruException("stack trace test")

    // Should have stack trace like any other exception
    val stackTrace = Option(exception.getStackTrace)
    assert(stackTrace.isDefined, "Stack trace should be present")
    assert(stackTrace.exists(_.nonEmpty), "Stack trace should not be empty")
  }

  test("EruException equality based on error content") {
    val error1 = "same error"
    val error2 = "same error"
    val error3 = "different error"

    val exception1 = EruException(error1)
    val exception2 = EruException(error2)
    val exception3 = EruException(error3)

    // Note: Exception equality is identity-based, not content-based
    // This test verifies that the errors themselves are equal
    assertEquals(exception1.error, exception2.error)
    assertNotEquals(exception1.error, exception3.error)
  }
}
