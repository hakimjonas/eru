package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Comprehensive tests for error and defect propagation between parent and child fibers.
  *
  * Tests that errors and defects are correctly propagated through the fiber hierarchy and that
  * error handling works correctly across fiber boundaries. This test suite focuses on correctness
  * rather than timing to ensure reliable behavior across different execution environments.
  */
class FiberErrorPropagationSpec extends FunSuite {

  /** Helper to run operations with isolated runtime to prevent test interference */
  private def withIsolatedRuntime[A](f: IsolatedTestRunner.IsolatedRuntime => A): A = {
    IsolatedTestRunner.withIsolatedRuntime(f)
  }

  /** Helper method to create a detailed assertion message for Exit comparisons */
  private def assertExitEquals[E, A](actual: Exit[E, A], expected: Exit[E, A], context: String): Unit = {
    assertEquals(
      actual,
      expected,
      s"$context - Expected: $expected, but got: $actual"
    )
  }

  /** Helper method to create a detailed assertion message for Result comparisons */
  private def assertResultEquals[E, A](actual: Result[E, A], expected: Result[E, A], context: String): Unit = {
    assertEquals(
      actual,
      expected,
      s"$context - Expected: $expected, but got: $actual"
    )
  }

  /** Helper method to verify that a Die exit contains the expected exception type and message */
  private def assertDieWithException[E](
    exit: Exit[E, Any],
    expectedType: Class[?],
    expectedMessage: String,
    context: String
  ): Unit = {
    exit match {
      case Exit.Die(throwable) =>
        assertEquals(
          throwable.getClass,
          expectedType,
          s"$context - Expected exception type $expectedType but got ${throwable.getClass}"
        )
        assertEquals(
          throwable.getMessage,
          expectedMessage,
          s"$context - Expected exception message '$expectedMessage' but got '${throwable.getMessage}'"
        )
      case other =>
        fail(s"$context - Expected Die exit but got: $other")
    }
  }

  test("typed error in child fiber is accessible via await") {
    val childError = "child computation failed"
    val childEffect = Eru.fail(childError)

    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
    } yield childExit

    val result = parentEffect.unsafeRunSync()
    assertExitEquals(
      result,
      Exit.Failure(childError),
      "Child fiber with typed error should produce Failure exit"
    )
  }

  test("defect (exception) in child fiber is captured in Die exit") {
    val exception = new IllegalArgumentException("invalid input")
    val childEffect = Eru.effect(throw exception)

    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
    } yield childExit

    val result = parentEffect.unsafeRunSync()
    assertDieWithException(
      result,
      classOf[IllegalArgumentException],
      "invalid input",
      "Child fiber throwing exception should produce Die exit"
    )
  }

  test("parent can recover from child typed error using fromExit") {
    val childError = "recoverable error"
    val childEffect = Eru.fail(childError)
    val recoveredValue = "recovered successfully"

    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
      result <- childExit match {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      } recoverWith {
        case "recoverable error" => Eru.succeed(recoveredValue)
        case other => Eru.fail(s"unhandled error: $other")
      }
    } yield result

    val result = parentEffect.unsafeRunSync()
    assertEquals(
      result,
      recoveredValue,
      s"Parent should recover from child error and produce '$recoveredValue'"
    )
  }

  test("parent can handle child defect using fromExit error handling") {
    val exception = new RuntimeException("child died")
    val childEffect = Eru.effect(throw exception)

    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
      result <- (childExit match {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }).attempt.map {
        case Result.Success(value) => s"unexpected success: $value"
        case Result.Failure(throwable) => s"caught defect: ${throwable.getMessage}"
      }
    } yield result

    val result = parentEffect.unsafeRunSync()
    assertEquals(
      result,
      "caught defect: child died",
      "Parent should handle child defect and produce descriptive error message"
    )
  }

  test("multiple child fiber errors are handled independently") {
    val child1Effect = Eru.fail("error1")
    val child2Effect = Eru.fail("error2")
    val child3Effect = Eru.succeed("success")

    val parentEffect = for {
      fiber1 <- EruRuntime.fork(child1Effect)
      fiber2 <- EruRuntime.fork(child2Effect)
      fiber3 <- EruRuntime.fork(child3Effect)
      exit1 <- fiber1.await
      exit2 <- fiber2.await
      exit3 <- fiber3.await
    } yield (exit1, exit2, exit3)

    val (exit1, exit2, exit3) = parentEffect.unsafeRunSync()

    assertExitEquals(exit1, Exit.Failure("error1"), "First fiber should fail with 'error1'")
    assertExitEquals(exit2, Exit.Failure("error2"), "Second fiber should fail with 'error2'")
    assertExitEquals(exit3, Exit.Success("success"), "Third fiber should succeed with 'success'")
  }

  test("zipPar propagates first error encountered with left-bias preference") {
    val leftError = "left failed"
    val rightError = "right failed"

    val leftEffect = Eru.fail(leftError)
    val rightEffect = Eru.fail(rightError)

    val result = EruRuntime.zipPar(leftEffect, rightEffect).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error) =>
        assertEquals(
          error,
          leftError,
          "zipPar should be left-biased in error reporting and propagate the left error"
        )
      case Result.Success(value) =>
        fail(s"Expected zipPar to fail with left error '$leftError' but got success: $value")
    }
  }

  test("zipPar demonstrates error-first completion behavior") {
    val fastFail = Eru.fail("fast failure")
    val slowSuccess = EruRuntime.sleep(java.time.Duration.ofMillis(100)).map(_ => "slow success")

    val result = EruRuntime.zipPar(fastFail, slowSuccess).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error) =>
        assertEquals(
          error,
          "fast failure",
          "zipPar should propagate the fast failure without waiting for slow success"
        )
      case Result.Success(value) =>
        fail(s"Expected zipPar to fail with 'fast failure' but got success: $value")
    }
  }

  test("parSequence fails on first error without waiting for all effects") {
    withIsolatedRuntime { runtime =>
      val effects = List(
        Eru.succeed("success1"),
        Eru.fail("failure"),
        runtime.sleep(java.time.Duration.ofMillis(100)).map(_ => "slow success")
      )

      val result = runtime.parSequence(effects).attempt.unsafeRunSync()

      result match {
        case Result.Failure(error) =>
          assertEquals(
            error,
            "failure",
            "parSequence should fail fast with the first error encountered"
          )
        case Result.Success(value) =>
          fail(s"Expected parSequence to fail with 'failure' but got success: $value")
      }
    }
  }

  test("error in parent fiber does not affect already-forked children") {
    val childEffect = Eru.succeed("child completed")

    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      _ <- Eru.fail("parent error")
    } yield childFiber

    val parentResult = parentEffect.attempt.unsafeRunSync()

    parentResult match {
      case Result.Failure(error) =>
        assertEquals(
          error,
          "parent error",
          "Parent should fail with its own error, independent of child success"
        )
      case Result.Success(fiber) =>
        fail(s"Expected parent to fail with 'parent error' but got fiber: $fiber")
    }
  }

  test("nested error propagation through multiple fiber levels") {
    withIsolatedRuntime { runtime =>
      val deepError = "deep nested error"
      val deepEffect = Eru.fail(deepError)

      val middleEffect = for {
        deepFiber <- runtime.fork(deepEffect)
        deepExit <- deepFiber.await
        deepResult <- deepExit match {
          case Exit.Success(value) => Eru.succeed(value)
          case Exit.Failure(error) => Eru.fail(error)
          case Exit.Die(t) => Eru.effect(throw t)
          case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
        }
      } yield s"middle processed: $deepResult"

      val topEffect = for {
        middleFiber <- runtime.fork(middleEffect)
        middleExit <- middleFiber.await
      } yield middleExit

      val result = topEffect.unsafeRunSync()

      result match {
        case Exit.Failure(error) =>
          assertEquals(
            error,
            deepError,
            "Error should propagate through multiple fiber levels unchanged"
          )
        case other =>
          fail(s"Expected nested error propagation to produce Failure($deepError) but got: $other")
      }
    }
  }

  test("error recovery at different fiber levels preserves recovery semantics") {
    val originalError = "original error"
    val recoveredValue = "recovered at middle level"

    val deepEffect = Eru.fail(originalError)

    val middleEffect = for {
      deepFiber <- EruRuntime.fork(deepEffect)
      deepExit <- deepFiber.await
      result <- (deepExit match {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }).recoverWith {
        case "original error" => Eru.succeed(recoveredValue)
        case other => Eru.fail(s"unhandled: $other")
      }
    } yield result

    val topEffect = for {
      middleFiber <- EruRuntime.fork(middleEffect)
      middleExit <- middleFiber.await
      result <- middleExit match {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }
    } yield result

    val result = topEffect.unsafeRunSync()
    assertEquals(
      result,
      recoveredValue,
      "Error recovery should work correctly across fiber boundaries"
    )
  }

  test("mixed success and error results in concurrent operations show deterministic error preference") {
    def createEffect(id: Int): Eru[String, String] = {
      if (id % 2 == 0) Eru.succeed(s"success-$id")
      else Eru.fail(s"error-$id")
    }

    val effects = (1 to 5).map(createEffect).toList

    val result = EruRuntime.parTraverse(effects)(identity).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error) =>
        error match {
          case errorString: String =>
            assert(
              errorString.startsWith("error-"),
              s"parTraverse should fail with one of the error cases, but got: $error"
            )
          case throwable: Throwable =>
            fail(s"Expected String error but got Throwable: $throwable")
        }
      case Result.Success(values) =>
        fail(s"Expected parTraverse to fail due to odd-numbered errors but got success: $values")
    }
  }

  test("fromExit correctly handles Exit cases except interruptions") {
    val testValue = 42
    val typedError = "typed error"
    val runtimeException = new RuntimeException("defect")

    val successExit: Exit[String, Int] = Exit.Success(testValue)
    val failureExit: Exit[String, Int] = Exit.Failure(typedError)
    val dieExit: Exit[String, Int] = Exit.Die(runtimeException)

    val successResult: Eru[String | Throwable, Int] = Eru.fromExit(successExit)
    val failureResult: Eru[String | Throwable, Int] = Eru.fromExit(failureExit)
    val dieResult: Eru[String | Throwable, Int] = Eru.fromExit(dieExit)

    assertEquals(
      successResult.unsafeRunSync(),
      testValue,
      "fromExit should preserve success values"
    )

    assertResultEquals(
      failureResult.attempt.unsafeRunSync(),
      Result.Failure(typedError),
      "fromExit should preserve typed failures"
    )

    dieResult.attempt.unsafeRunSync() match {
      case Result.Failure(throwable: Throwable) =>
        assertEquals(
          throwable,
          runtimeException,
          "fromExit should re-throw the original exception for Die cases"
        )
      case other =>
        fail(s"Expected fromExit to propagate Die as throwable failure but got: $other")
    }

    // Interruptions should be handled through pattern matching, not fromExit
    val fiberId = FiberId.fresh()
    val interruptCause = InterruptCause.Cancelled(Some("test cancellation"))
    val interruptExit: Exit[String, Int] = Exit.Interrupt(fiberId, interruptCause)

    val interruptHandled = interruptExit match {
      case Exit.Interrupt(_, _) => "interrupted"
      case other => s"unexpected: $other"
    }
    assertEquals(interruptHandled, "interrupted", "Interruptions should be handled through pattern matching")
  }

  test("fiber error isolation - errors in one fiber do not corrupt others") {
    val errorEffect = Eru.fail("isolated error")
    val successEffect = Eru.succeed("isolated success")
    val defectEffect = Eru.effect(throw new RuntimeException("isolated defect"))

    val compositeEffect = for {
      errorFiber <- EruRuntime.fork(errorEffect)
      successFiber <- EruRuntime.fork(successEffect)
      defectFiber <- EruRuntime.fork(defectEffect)
      errorExit <- errorFiber.await
      successExit <- successFiber.await
      defectExit <- defectFiber.await
    } yield (errorExit, successExit, defectExit)

    val (errorExit, successExit, defectExit) = compositeEffect.unsafeRunSync()

    assertExitEquals(
      errorExit,
      Exit.Failure("isolated error"),
      "Error fiber should complete with typed failure"
    )
    assertExitEquals(
      successExit,
      Exit.Success("isolated success"),
      "Success fiber should complete with success value"
    )
    assertDieWithException(
      defectExit,
      classOf[RuntimeException],
      "isolated defect",
      "Defect fiber should complete with Die exit"
    )
  }

  test("deep fiber nesting preserves error propagation semantics") {
    val baseError = "base error"
    val nestingDepth = 5

    def createNestedFiber(depth: Int): Eru[String, String] = {
      if (depth <= 0) {
        Eru.fail(baseError)
      } else {
        for {
          childFiber <- EruRuntime.fork(createNestedFiber(depth - 1))
          childExit <- childFiber.await
          result <- {
            (childExit match {
              case Exit.Success(value) => Eru.succeed(value)
              case Exit.Failure(error) => Eru.fail(error)
              case Exit.Die(t) => Eru.effect(throw t)
              case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
            }).attempt.flatMap {
              case Result.Success(value) => Eru.succeed(value)
              case Result.Failure(error) =>
                error match {
                  case stringError: String => Eru.fail(stringError)
                  case throwable: Throwable => Eru.fail(throwable.getMessage)
                }
            }
          }
        } yield s"level-$depth: $result"
      }
    }

    val result = createNestedFiber(nestingDepth).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error) =>
        assertEquals(
          error,
          baseError,
          s"Deep nesting should preserve error propagation through $nestingDepth levels"
        )
      case Result.Success(value) =>
        fail(s"Expected deep nested fiber to propagate error '$baseError' but got success: $value")
    }
  }
}
