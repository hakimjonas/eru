package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Comprehensive tests for the complete fiber lifecycle.
  *
  * Tests the fundamental fiber operations (fork, await) across success, failure, and interruption
  * scenarios to ensure correct behavior in the unified fiber runtime.
  */
class FiberLifecycleSpec extends TestWithSharedRuntime {

  /** Validates that fiber fork creates new fibers with unique identifiers.
    *
    * Tests that each forked fiber receives a unique ID that distinguishes it from other fibers in
    * the runtime system.
    */
  test("fiber fork creates new fiber with unique ID") {
    val effect = Eru.succeed(42)
    val fiber = runtime.fork(effect).unsafeRunSync()

    assertNotEquals(fiber.id.toLong, 0L, "Fiber should have non-zero ID")
    assertNotEquals(fiber.id, FiberId.fresh(), "Each fiber should have unique ID")
  }

  /** Validates that fiber await returns Exit.Success for successful computations.
    *
    * Tests that when a fiber completes successfully, await returns the result wrapped in an
    * Exit.Success value.
    */
  test("fiber await returns Exit.Success for successful computation") {
    val value = 42
    val effect = Eru.succeed(value)

    val fiber = runtime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(value))
  }

  /** Validates that fiber await returns Exit.Failure for typed errors.
    *
    * Tests that when a fiber fails with a typed error, await returns the error wrapped in an
    * Exit.Failure value.
    */
  test("fiber await returns Exit.Failure for typed error") {
    val error = "test error"
    val effect = Eru.fail(error)

    val fiber = runtime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Failure(error))
  }

  /** Validates that fiber await returns Exit.Die for thrown exceptions.
    *
    * Tests that when a fiber throws an untyped exception, await returns the exception wrapped in an
    * Exit.Die value.
    */
  test("fiber await returns Exit.Die for thrown exception") {
    val exception = new RuntimeException("test exception")
    val effect = Eru.effect(throw exception)

    val fiber = runtime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t.getMessage, "test exception")
      case other => fail(s"Expected Die but got $other")
    }
  }

  /** Validates that fiber await is referentially transparent.
    *
    * Tests that multiple await calls on the same fiber return identical results, demonstrating
    * referential transparency and idempotency.
    */
  test("fiber await is referentially transparent - multiple awaits return same result") {
    val value = 42
    val effect = Eru.succeed(value)
    val fiber = runtime.fork(effect).unsafeRunSync()

    val exit1 = fiber.await.unsafeRunSync()
    val exit2 = fiber.await.unsafeRunSync()
    val exit3 = fiber.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit2, exit3)
    assertEquals(exit1, Exit.Success(value))
  }

  /** Validates that fibers execute complex computation chains correctly.
    *
    * Tests that multi-step computations with flatMap chaining work correctly when executed within a
    * fiber context.
    */
  test("fiber with complex computation chain executes correctly") {
    val computation = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.effect(a + b + 12)
    } yield c

    val fiber = runtime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(42))
  }

  /** Validates that fibers handle error recovery chains correctly.
    *
    * Tests that error recovery using recoverWith works properly within fiber execution, allowing
    * graceful error handling.
    */
  test("fiber with error recovery chain handles errors correctly") {
    val computation = Eru
      .fail("initial error")
      .recoverWith(_ => Eru.succeed(42))

    val fiber = runtime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(42))
  }

  /** Validates that fibers propagate unrecovered errors correctly.
    *
    * Tests that when error recovery fails to handle an error, the original failure is properly
    * propagated through the fiber result.
    */
  test("fiber with unrecovered error propagates failure") {
    val error = "unhandled error"
    val computation = Eru.fail(error).recoverWith {
      case "different error" => Eru.succeed(42)
      case _ => Eru.fail(error)
    }

    val fiber = runtime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Failure(error))
  }

  /** Validates that fiber interrupt method creates an effect.
    *
    * Tests the current placeholder implementation of fiber interruption, ensuring it returns a
    * valid Unit effect.
    */
  test("fiber interrupt method creates effect (placeholder implementation)") {
    val fiber = runtime.fork(Eru.succeed(42)).unsafeRunSync()
    val interruptEffect = fiber.interrupt(InterruptCause.Cancelled(Some("test")))

    val result = interruptEffect.unsafeRunSync()
    assertEquals(result, ())
  }

  /** Validates that nested fiber fork and await operations work correctly.
    *
    * Tests that fibers can fork child fibers and await their results, demonstrating proper nested
    * concurrency support.
    */
  test("nested fiber fork and await works correctly") {
    val innerComputation = Eru.succeed("inner")
    val outerComputation = for {
      innerFiber <- runtime.fork(innerComputation)
      innerExit <- innerFiber.await
      innerResult <- Eru.fromExit(innerExit)
      result <- Eru.succeed(s"outer-$innerResult")
    } yield result

    val outerFiber = runtime.fork(outerComputation).unsafeRunSync()
    val outerExit = outerFiber.await.unsafeRunSync()

    assertEquals(outerExit, Exit.Success("outer-inner"))
  }

  /** Validates that fiber types preserve variance correctly.
    *
    * Tests that fiber type parameters maintain proper covariance relationships, allowing safe
    * subtype substitution in both error and success types.
    */
  test("fiber types preserve variance correctly") {
    val stringFiber: Fiber[String, String] = runtime.fork(Eru.succeed("test")).unsafeRunSync()
    val anyFiber: Fiber[Any, Any] = stringFiber

    val exit = anyFiber.await.unsafeRunSync()
    assertEquals(exit, Exit.Success("test"))
  }
}
