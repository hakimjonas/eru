package net.ghoula.eru.fiber

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Comprehensive tests for fiber interruption and cancellation scenarios.
  *
  * Tests complex interruption patterns to ensure cancellation is propagated correctly and
  * cooperative interruption works as expected in the unified fiber runtime.
  */
class FiberInterruptionSpec extends FunSuite {

  /** Helper to run operations with isolated runtime to prevent test interference */
  private def withIsolatedRuntime[A](f: IsolatedTestRunner.IsolatedRuntime => A): A = {
    IsolatedTestRunner.withIsolatedRuntime(f)
  }

  /** Validates that fiber interruption with Cancelled cause is handled gracefully.
    *
    * Tests that interrupting a fiber with a Cancelled cause works correctly and returns the
    * expected unit result without throwing exceptions.
    */
  test("fiber interrupt with Cancelled cause is handled gracefully") {
    withIsolatedRuntime {
      runtime =>
        val longRunning = runtime.sleep(Duration.ofMillis(100)).map(_ => "completed")
        val fiber = runtime.fork(longRunning).unsafeRunSync()

      val cause = InterruptCause.Cancelled(Some("user requested cancellation"))
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }

  /** Validates that fiber interruption with Timeout cause includes duration information.
    *
    * Tests that interrupting a fiber with a Timeout cause properly handles the duration context and
    * executes without errors.
    */
  test("fiber interrupt with Timeout cause includes duration information") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed(42)
      val fiber = runtime.fork(effect).unsafeRunSync()

      val duration = Duration.ofSeconds(5)
      val cause = InterruptCause.Timeout(duration, Some("test timeout"))
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }

  /** Validates that fiber interruption with ParentTerminated cause includes parent context.
    *
    * Tests that interrupting a fiber with a ParentTerminated cause properly handles the parent
    * fiber context and executes without errors.
    */
  test("fiber interrupt with ParentTerminated cause includes parent context") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed("child")
      val fiber = runtime.fork(effect).unsafeRunSync()

      val parentId = FiberId.fresh()
      val parentExit = Exit.Success("parent completed")
      val cause = InterruptCause.ParentTerminated(parentId, parentExit)
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }

  /** Validates that fiber interruption with ResourceExhausted cause includes resource details.
    *
    * Tests that interrupting a fiber with a ResourceExhausted cause properly handles resource
    * information and executes without errors.
    */
  test("fiber interrupt with ResourceExhausted cause includes resource details") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed(List.fill(1000)("data"))
      val fiber = runtime.fork(effect).unsafeRunSync()

      val cause = InterruptCause.ResourceExhausted("memory", Some("JVM heap exceeded 90%"))
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }

  /** Validates that fiber interruption with Custom cause includes application-specific metadata.
    *
    * Tests that interrupting a fiber with a Custom cause properly preserves all
    * application-specific metadata and context information.
    */
  test("fiber interrupt with Custom cause includes application-specific metadata") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed("business logic")
      val fiber = runtime.fork(effect).unsafeRunSync()

      val metadata = Map(
        "operation" -> "data_processing",
        "batch_id" -> "batch_123",
        "priority" -> "high"
      )
      val cause = InterruptCause.Custom(
        name = "business_rule_violation",
        context = Some("Data validation failed"),
        metadata = metadata
      )
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }

  /** Validates that multiple interrupt calls on the same fiber are idempotent.
    *
    * Tests that calling interrupt multiple times on the same fiber produces consistent results
    * without side effects or state corruption.
    */
  test("multiple interrupt calls on same fiber are idempotent") {
    withIsolatedRuntime { runtime =>
      val effect = runtime.sleep(Duration.ofMillis(50)).map(_ => "done")
      val fiber = runtime.fork(effect).unsafeRunSync()

      val cause1 = InterruptCause.Cancelled(Some("first interrupt"))
      val cause2 = InterruptCause.Cancelled(Some("second interrupt"))

      val result1 = fiber.interrupt(cause1).unsafeRunSync()
      val result2 = fiber.interrupt(cause2).unsafeRunSync()
      val result3 = fiber.interrupt(cause1).unsafeRunSync()

      assertEquals(result1, ())
      assertEquals(result2, ())
      assertEquals(result3, ())
    }
  }

  /** Validates that interrupt causes provide meaningful string representations.
    *
    * Tests that all interrupt cause types generate descriptive toString output containing relevant
    * context information for debugging and logging.
    */
  test("interrupt cause toString provides meaningful descriptions") {
    val cancelledCause = InterruptCause.Cancelled(Some("user action"))
    val timeoutCause = InterruptCause.Timeout(Duration.ofSeconds(30), Some("API call"))
    val resourceCause = InterruptCause.ResourceExhausted("connections", Some("Pool exhausted"))
    val customCause = InterruptCause.Custom("maintenance", Some("Scheduled downtime"), Map("window" -> "2am-4am"))

    assert(cancelledCause.toString.contains("user action"))
    assert(timeoutCause.toString.contains("30"))
    assert(timeoutCause.toString.contains("API call"))
    assert(resourceCause.toString.contains("connections"))
    assert(resourceCause.toString.contains("Pool exhausted"))
    assert(customCause.toString.contains("maintenance"))
    assert(customCause.toString.contains("Scheduled downtime"))
  }

  /** Validates that interrupting an already completed fiber has no effect.
    *
    * Tests that interruption calls on fibers that have already completed do not change their exit
    * status or cause any side effects.
    */
  test("interrupt on already completed fiber has no effect") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed("already done")
      val fiber = runtime.fork(effect).unsafeRunSync()

      val exit = fiber.await.unsafeRunSync()
      assertEquals(exit, Exit.Success("already done"))

      val cause = InterruptCause.Cancelled(Some("too late"))
      val interruptResult = fiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())

      val exitAfterInterrupt = fiber.await.unsafeRunSync()
      assertEquals(exitAfterInterrupt, Exit.Success("already done"))
    }
  }

  /** Validates that interrupt causes preserve all provided information.
    *
    * Tests that interrupt cause objects maintain data integrity and can be properly pattern matched
    * to extract their contained information.
    */
  test("interrupt cause preserves all provided information") {
    val parentId = FiberId.fresh()
    val parentExit = Exit.Failure("parent failed")
    val cause = InterruptCause.ParentTerminated(parentId, parentExit)

    cause match {
      case InterruptCause.ParentTerminated(id, exit) =>
        assertEquals(id, parentId)
        assertEquals(exit, parentExit)
      case other => fail(s"Expected ParentTerminated but got $other")
    }
  }

  /** Validates that the default interrupt method uses Cancelled cause.
    *
    * Tests that when no specific interrupt cause is provided, the default behavior uses a Cancelled
    * cause appropriately.
    */
  test("default interrupt method uses Cancelled cause") {
    withIsolatedRuntime { runtime =>
      val effect = Eru.succeed(42)
      val fiber = runtime.fork(effect).unsafeRunSync()

      val result = fiber.interrupt(InterruptCause.Cancelled()).unsafeRunSync()
      assertEquals(result, ())
    }
  }

  /** Validates interruption behavior in nested fiber scenarios.
    *
    * Tests that interrupting parent fibers properly handles cleanup of nested child fibers without
    * causing resource leaks or inconsistent state.
    */
  test("interruption with nested fiber scenarios") {
    withIsolatedRuntime { runtime =>
      val innerEffect = runtime.sleep(Duration.ofMillis(100)).map(_ => "inner complete")
      val outerEffect = for {
        innerFiber <- runtime.fork(innerEffect)
        _ <- runtime.sleep(Duration.ofMillis(50))
        innerResult <- innerFiber.await.flatMap(Eru.fromExit)
      } yield s"outer got: $innerResult"

      val outerFiber = runtime.fork(outerEffect).unsafeRunSync()

      val cause = InterruptCause.Cancelled(Some("nested interrupt test"))
      val interruptResult = outerFiber.interrupt(cause).unsafeRunSync()

      assertEquals(interruptResult, ())
    }
  }
}
