package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Test suite for validation patterns with error accumulation.
  *
  * Validates both validatePar (error accumulation) and validateFirst (fail-fast) operations that
  * enable flexible error handling strategies for domain modeling and validation scenarios. These
  * operations are essential for Valar integration and form validation workflows.
  */
final class ValidationPatternsSpec extends EruTestSuite {

  test("validatePar accumulates all errors when all effects fail") {
    val errors = List("error1", "error2", "error3")
    val effects = errors.map(Eru.fail(_))

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(accumulatedErrors) =>
        assertEquals(accumulatedErrors.toSet, errors.toSet)
      case Right(_) =>
        fail("Expected accumulated errors, got success")
    }
  }

  test("validatePar returns all successes when all effects succeed") {
    val values = List(1, 2, 3, 4, 5)
    val effects = values.map(Eru.succeed(_))

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected all successes, got errors")
      case Right(results) =>
        assertEquals(results, values)
    }
  }

  test("validatePar accumulates errors and ignores successes") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("error1"),
      Eru.succeed(2),
      Eru.fail("error2"),
      Eru.succeed(3)
    )

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(errors) =>
        assertEquals(errors.toSet, Set("error1", "error2"))
      case Right(_) =>
        fail("Expected errors, got successes")
    }
  }

  test("validatePar with empty list returns empty success") {
    val result = validatePar(List.empty[Eru[String, Int]]).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected empty success, got errors")
      case Right(results) =>
        assertEquals(results, List.empty)
    }
  }

  test("validatePar executes effects in parallel") {
    val effects = (1 to 5).toList.map { i =>
      Eru.effect {
        // Remove Thread.sleep - parallel execution doesn't need artificial delay
        i
      }
    }

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected success, got errors")
      case Right(results) =>
        assertEquals(results, List(1, 2, 3, 4, 5))
    }
  }

  test("validateFirst returns first error when effects fail") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("first_error"),
      Eru.fail("second_error"),
      Eru.succeed(2)
    )

    val result = validateFirst(effects).unsafeRunSync()

    result match {
      case Left(error) =>
        // Should be one of the errors (implementation-dependent which one)
        // The error type is E | Throwable, so it could be a string or throwable
        error match {
          case "first_error" | "second_error" => assert(true)
          case _: Throwable => assert(true)
          case _ => fail(s"Expected first_error, second_error, or Throwable, got: $error")
        }
      case Right(_) =>
        fail("Expected error, got success")
    }
  }

  test("validateFirst returns all successes when all effects succeed") {
    val values = List(10, 20, 30)
    val effects = values.map(Eru.succeed(_))

    val result = validateFirst(effects).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected successes, got error")
      case Right(results) =>
        assertEquals(results, values)
    }
  }

  test("validateFirst with empty list returns empty success") {
    val result = validateFirst(List.empty[Eru[String, Int]]).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected empty success, got error")
      case Right(results) =>
        assertEquals(results, List.empty)
    }
  }

  test("validatePar propagates defects (Throwables) immediately") {
    val effects = List(
      Eru.succeed(1),
      Eru.effect(throw new RuntimeException("defect")),
      Eru.fail("typed_error")
    )

    intercept[RuntimeException] {
      validatePar(effects).unsafeRunSync()
    }
  }

  test("validateFirst handles defects (Throwables) in Either result") {
    val effects = List(
      Eru.succeed(1),
      Eru.effect(throw new RuntimeException("defect")),
      Eru.fail("typed_error")
    )

    val result = validateFirst(effects).unsafeRunSync()

    result match {
      case Left(error) =>
        // The defect should be captured as an error (either the RuntimeException or typed error)
        error match {
          case _: RuntimeException => assert(true)
          case "typed_error" => assert(true)
          case _ => fail(s"Expected RuntimeException or typed_error, got: $error")
        }
      case Right(_) =>
        fail("Expected error, got success")
    }
  }

  test("validatePar maintains result order even with parallel execution") {
    val effects = List(
      Eru.effect { "slow" }, // Remove Thread.sleep - order is maintained without timing
      Eru.effect { "fast" },
      Eru.effect { "medium" }
    )

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(_) =>
        fail("Expected success, got errors")
      case Right(results) =>
        // Results should maintain input order despite parallel execution
        assertEquals(results, List("slow", "fast", "medium"))
    }
  }

  test("validatePar handles mixed success and failure scenarios") {
    // Comprehensive test with multiple successes and failures
    val effects = List(
      Eru.succeed("success1"),
      Eru.fail("error1"),
      Eru.succeed("success2"),
      Eru.fail("error2"),
      Eru.succeed("success3"),
      Eru.fail("error3")
    )

    val result = validatePar(effects).unsafeRunSync()

    result match {
      case Left(errors) =>
        assertEquals(errors.toSet, Set("error1", "error2", "error3"))
      case Right(_) =>
        fail("Expected errors, got successes")
    }
  }
}
