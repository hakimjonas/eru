package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Comprehensive tests for the complete fiber lifecycle.
  *
  * Tests the fundamental fiber operations (fork, await) across success, failure, and interruption
  * scenarios to ensure correct behavior in the unified fiber runtime.
  */
class FiberLifecycleSpec extends FunSuite {

  test("fiber fork creates new fiber with unique ID") {
    val effect = Eru.succeed(42)
    val fiber = EruRuntime.fork(effect).unsafeRunSync()

    assertNotEquals(fiber.id.toLong, 0L, "Fiber should have non-zero ID")
    assertNotEquals(fiber.id, FiberId.fresh(), "Each fiber should have unique ID")
  }

  test("fiber await returns Exit.Success for successful computation") {
    val value = 42
    val effect = Eru.succeed(value)

    val fiber = EruRuntime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(value))
  }

  test("fiber await returns Exit.Failure for typed error") {
    val error = "test error"
    val effect = Eru.fail(error)

    val fiber = EruRuntime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Failure(error))
  }

  test("fiber await returns Exit.Die for thrown exception") {
    val exception = new RuntimeException("test exception")
    val effect = Eru.effect(throw exception)

    val fiber = EruRuntime.fork(effect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t.getMessage, "test exception")
      case other => fail(s"Expected Die but got $other")
    }
  }

  test("fiber await is referentially transparent - multiple awaits return same result") {
    val value = 42
    val effect = Eru.succeed(value)
    val fiber = EruRuntime.fork(effect).unsafeRunSync()

    val exit1 = fiber.await.unsafeRunSync()
    val exit2 = fiber.await.unsafeRunSync()
    val exit3 = fiber.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit2, exit3)
    assertEquals(exit1, Exit.Success(value))
  }

  test("fiber with complex computation chain executes correctly") {
    val computation = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.effect(a + b + 12) // Total: 42
    } yield c

    val fiber = EruRuntime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(42))
  }

  test("fiber with error recovery chain handles errors correctly") {
    val computation = Eru
      .fail("initial error")
      .recoverWith(_ => Eru.succeed(42))

    val fiber = EruRuntime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success(42))
  }

  test("fiber with unrecovered error propagates failure") {
    val error = "unhandled error"
    val computation = Eru.fail(error).recoverWith {
      case "different error" => Eru.succeed(42)
      case _ => Eru.fail(error) // Re-fail with same error
    }

    val fiber = EruRuntime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Failure(error))
  }

  test("fiber interrupt method creates effect (placeholder implementation)") {
    val fiber = EruRuntime.fork(Eru.succeed(42)).unsafeRunSync()
    val interruptEffect = fiber.interrupt(InterruptCause.Cancelled(Some("test")))

    // In Phase 2, interrupt is placeholder - just verify it returns Unit
    val result = interruptEffect.unsafeRunSync()
    assertEquals(result, ())
  }

  test("nested fiber fork and await works correctly") {
    val innerComputation = Eru.succeed("inner")
    val outerComputation = for {
      innerFiber <- EruRuntime.fork(innerComputation)
      innerExit <- innerFiber.await
      innerResult <- Eru.fromExit(innerExit)
      result <- Eru.succeed(s"outer-$innerResult")
    } yield result

    val outerFiber = EruRuntime.fork(outerComputation).unsafeRunSync()
    val outerExit = outerFiber.await.unsafeRunSync()

    assertEquals(outerExit, Exit.Success("outer-inner"))
  }

  test("fiber types preserve variance correctly") {
    // Test covariance in both error and success types
    val stringFiber: Fiber[String, String] = EruRuntime.fork(Eru.succeed("test")).unsafeRunSync()
    val anyFiber: Fiber[Any, Any] = stringFiber

    val exit = anyFiber.await.unsafeRunSync()
    assertEquals(exit, Exit.Success("test"))
  }
}
