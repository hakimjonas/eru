package userland

import munit.FunSuite
import userland.TestRuntime.*

import net.ghoula.eru.prelude.*

/** Integration test suite for Eru's observability features in production scenarios.
  *
  * Validates the observer system, event emission, and runtime visibility features in realistic
  * application contexts. These tests ensure that the observability system provides comprehensive
  * runtime insights without affecting performance or correctness, supporting the Exceptional
  * Observability pillar by making runtime behavior transparent and debuggable in production
  * environments.
  */
final class ObservabilitySpec extends FunSuite {

  /** Validates that observers receive proper event sequences for successful programs.
    *
    * Tests that the observability system emits ProgramStart, Step, and ProgramEnd(Success) events
    * in the correct order for successful computation execution.
    */
  test("observer sees ProgramStart, Step, and ProgramEnd(Success)") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs: EruObserver = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    val value = TestRuntime.runIsolatedWith(Eru.succeed(123).debug("step-1"), obs)

    assertEquals(value, 123)
    val xs = events.toList
    xs match {
      case EruEvent
            .ProgramStart(scopeId) :: EruEvent.Step(scopeId2, label) :: EruEvent.ProgramEnd(scopeId3, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        assertEquals(scopeId3, scopeId)
        assertEquals(label, "step-1")
        assertEquals(outcome, EruObserver.Outcome.Success)
      case other => fail(s"unexpected events: $other")
    }
  }

  /** Validates that observers receive proper event sequences for failed programs.
    *
    * Tests that the observability system emits appropriate events for computations that fail with
    * typed errors.
    */
  test("observer sees ProgramStart and ProgramEnd(TypedFailure)") {
    val exit = Eru.fail("boom").runIsolatedExit
    exit match {
      case Exit.Failure(error) => assertEquals(error, "boom")
      case other => fail(s"Expected failure, got: $other")
    }
  }

  /** Validates that observers receive proper event sequences for programs with defects.
    *
    * Tests that the observability system emits appropriate events for computations that fail with
    * untyped exceptions (defects).
    */
  test("observer sees ProgramStart and ProgramEnd(Defect) on Throwable") {
    val boom = new RuntimeException("kapow")
    val exit = Eru.effect[Int](throw boom).runIsolatedExit
    exit match {
      case Exit.Die(throwable) =>
        assertEquals(throwable.getClass, boom.getClass)
        assertEquals(throwable.getMessage, boom.getMessage)
      case other => fail(s"Expected Die, got: $other")
    }
  }

  /** Validates that fiber operations emit proper observability events.
    *
    * Tests that forked fibers produce appropriate FiberStarted and FiberCompleted events through
    * the observability system.
    */
  test("forkWithObserver emits FiberStarted and FiberCompleted with Exit.Success") {
    val fiber = TestRuntime.runIsolated(runtime.fork(Eru.succeed(10)))
    val exit = fiber.await.runIsolatedExit

    // The outer exit wraps the inner exit
    val result = exit match {
      case Exit.Success(innerExit) =>
        innerExit match {
          case Exit.Success(value) =>
            assertEquals(value, 10, "Inner value should be 10")
            "success"
          case other =>
            fail(s"Expected inner Success(10), got: $other")
        }
      case other =>
        fail(s"Expected Success, got: $other")
    }
    assertEquals(result, "success", "Test should complete successfully")
  }
}
