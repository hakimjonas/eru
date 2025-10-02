package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Test suite for the Fiber interface and core fiber operations.
  *
  * Validates the fundamental contracts of the Fiber trait including await operations, interruption
  * behavior, and fiber identity management. Tests cover the core fiber abstraction that enables
  * concurrent execution in the Eru effect system, ensuring proper lifecycle management and resource
  * cleanup semantics.
  */
class FiberSpec extends munit.FunSuite {

  private final class TestFiber[E, A](val id: FiberId, exit: Exit[E, A]) extends Fiber[E, A] {
    var interrupted: Option[InterruptCause] = None
    def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit)
    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
      interrupted = Some(cause)
      Eru.unit
    }
  }

  /** Validates that await returns Exit.Success for successful fiber.
    *
    * Tests that fiber await operations correctly return success exits with proper value extraction
    * and fiber ID preservation.
    */
  test("await returns Exit.Success for successful fiber") {
    val fid = FiberId.fresh()
    val fib = new TestFiber[Nothing, Int](fid, Exit.Success(7))
    val out = fib.await.unsafeRunSync()
    out match {
      case Exit.Success(v) => assertEquals(v, 7)
      case other => fail(s"Expected Success, got $other")
    }
    assertEquals(fib.id, fid)
  }

  /** Validates that await returns Exit.Failure with typed error.
    *
    * Tests that fiber await operations correctly return failure exits with proper error
    * preservation for typed failures.
    */
  test("await returns Exit.Failure with typed error") {
    val fib = new TestFiber[String, Nothing](FiberId.fresh(), Exit.Failure("boom"))
    fib.await.unsafeRunSync() match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case other => fail(s"Expected Failure, got $other")
    }
  }

  /** Validates that interrupt records cause and returns unit.
    *
    * Tests that fiber interruption correctly records the interrupt cause and returns a unit effect
    * for completion tracking.
    */
  test("interrupt records cause and returns unit") {
    val fib = new TestFiber[Nothing, Int](FiberId.fresh(), Exit.Success(1))
    fib.interrupt(InterruptCause.Cancelled()).unsafeRunSync()
    assertEquals(fib.interrupted, Some(InterruptCause.Cancelled()))
  }

  test("fiber correctly handles different Exit types") {
    val fiberId = FiberId.fresh()

    // Test Exit.Die
    val dieException = new RuntimeException("defect")
    val dieFiber = new TestFiber[Nothing, Nothing](fiberId, Exit.Die(dieException))
    dieFiber.await.unsafeRunSync() match {
      case Exit.Die(t) => assertEquals(t, dieException)
      case other => fail(s"Expected Die, got $other")
    }

    // Test Exit.Interrupt
    val interruptCause = InterruptCause.Timeout(java.time.Duration.ofSeconds(30))
    val interruptFiber = new TestFiber[Nothing, Nothing](fiberId, Exit.Interrupt(fiberId, interruptCause))
    interruptFiber.await.unsafeRunSync() match {
      case Exit.Interrupt(id, cause) =>
        assertEquals(id, fiberId)
        assertEquals(cause, interruptCause)
      case other => fail(s"Expected Interrupt, got $other")
    }
  }

  test("fiber interrupt with different causes") {
    val fib = new TestFiber[String, Int](FiberId.fresh(), Exit.Success(42))

    // Test various interrupt causes
    val timeoutCause = InterruptCause.Timeout(java.time.Duration.ofMillis(100), Some("test operation"))
    fib.interrupt(timeoutCause).unsafeRunSync()
    assertEquals(fib.interrupted, Some(timeoutCause))

    val resourceCause = InterruptCause.ResourceExhausted("memory", Some("heap exhausted"))
    val fib2 = new TestFiber[String, Int](FiberId.fresh(), Exit.Success(42))
    fib2.interrupt(resourceCause).unsafeRunSync()
    assertEquals(fib2.interrupted, Some(resourceCause))

    val parentCause = InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Die(new RuntimeException("parent died")))
    val fib3 = new TestFiber[String, Int](FiberId.fresh(), Exit.Success(42))
    fib3.interrupt(parentCause).unsafeRunSync()
    assertEquals(fib3.interrupted, Some(parentCause))
  }

  test("fiber identity is preserved across operations") {
    val originalId = FiberId.fresh()
    val fib = new TestFiber[String, Int](originalId, Exit.Success(99))

    // ID should remain constant through operations
    assertEquals(fib.id, originalId)
    fib.interrupt(InterruptCause.Cancelled()).unsafeRunSync()
    assertEquals(fib.id, originalId)
    fib.await.unsafeRunSync()
    assertEquals(fib.id, originalId)
  }

  test("fiber await preserves error type information") {
    case class CustomError(code: Int, message: String)
    val error = CustomError(404, "Not Found")

    val fib = new TestFiber[CustomError, Nothing](FiberId.fresh(), Exit.Failure(error))
    fib.await.unsafeRunSync() match {
      case Exit.Failure(e) =>
        assertEquals(e.code, 404)
        assertEquals(e.message, "Not Found")
      case other => fail(s"Expected Failure with CustomError, got $other")
    }
  }

  test("multiple interrupt calls update the recorded cause") {
    val fib = new TestFiber[Nothing, String](FiberId.fresh(), Exit.Success("result"))

    // First interrupt
    val firstCause = InterruptCause.Cancelled(Some("user action"))
    fib.interrupt(firstCause).unsafeRunSync()
    assertEquals(fib.interrupted, Some(firstCause))

    // Second interrupt should update the cause
    val secondCause = InterruptCause.Timeout(java.time.Duration.ofSeconds(5))
    fib.interrupt(secondCause).unsafeRunSync()
    assertEquals(fib.interrupted, Some(secondCause))
  }

  test("fiber operations are referentially transparent") {
    val fib = new TestFiber[String, Int](FiberId.fresh(), Exit.Success(100))

    // Multiple await calls should return the same result
    val result1 = fib.await.unsafeRunSync()
    val result2 = fib.await.unsafeRunSync()
    assertEquals(result1, result2)

    // Multiple interrupt calls with same cause should be idempotent in effect
    val cause = InterruptCause.ResourceExhausted("cpu")
    val interrupt1 = fib.interrupt(cause).unsafeRunSync()
    val interrupt2 = fib.interrupt(cause).unsafeRunSync()
    assertEquals(interrupt1, interrupt2)
  }

  test("fiber correctly handles complex error types") {
    sealed trait AppError
    case class ValidationError(field: String, message: String) extends AppError

    val validationError = ValidationError("email", "invalid format")
    val fib = new TestFiber[AppError, Nothing](FiberId.fresh(), Exit.Failure(validationError))

    fib.await.unsafeRunSync() match {
      case Exit.Failure(ValidationError(field, message)) =>
        assertEquals(field, "email")
        assertEquals(message, "invalid format")
      case other => fail(s"Expected ValidationError, got $other")
    }
  }
}
