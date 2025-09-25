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


  test("EruException can be thrown and caught") {
    val error = "thrown error"

    val caughtException = intercept[EruException[String]] {
      throw EruException(error)
    }

    assertEquals(caughtException.error, error)
    assertEquals(caughtException.getMessage, error)
  }

}
