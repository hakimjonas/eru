package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

/** Advanced test suite for Phase 2 fiber operations and resource management.
  *
  * Validates sophisticated fiber lifecycle scenarios including auto-join behavior, finalizer
  * leak prevention, and advanced resource cleanup patterns. These tests ensure that the fiber
  * system maintains resource safety even in complex concurrent scenarios and prevents common
  * concurrency pitfalls like resource leaks and improper cleanup ordering.
  */
class FiberPhase2Spec extends FunSuite {

  test("fork without await prevents finalizer leaks via auto-join") {
    var finalizerExecuted = false

    val computation = Eru
      .succeed(42)
      .ensure(Eru.effect {
        finalizerExecuted = true
      })

    val program = Eru.fork(computation).map(_ => "main result")

    val result = program.unsafeRunSync()

    // Main computation should succeed
    assertEquals(result, "main result")

    // Finalizer should have been executed due to auto-join
    assert(finalizerExecuted, "Finalizer should have executed via auto-join")
  }

  test("multiple await operations work (allows double-await for now)") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)

    // First await should work
    val firstResult = Eru.await(fiber).unsafeRunSync()
    assertEquals(firstResult, Exit.Success(42))

    // Second await should also work (double-await is allowed for now)
    val secondResult = Eru.await(fiber).unsafeRunSync()
    assertEquals(secondResult, Exit.Success(42))
  }

  test("fiber lifecycle events are emitted") {
    var events = List.empty[EruEvent]
    val observer = new EruObserver {
      def onEvent(event: EruEvent): Unit = events = event :: events
    }

    val computation = Eru.succeed(42)
    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSyncWith(observer)

    // Check that we got the expected events
    val reversedEvents = events.reverse

    // Should have ProgramStart, FiberStarted, FiberCompleted, ProgramEnd
    assertEquals(reversedEvents.length, 4)

    reversedEvents match {
      case EruEvent.ProgramStart(_) ::
          EruEvent.FiberStarted(_) ::
          EruEvent.FiberCompleted(_, Exit.Success(42)) ::
          EruEvent.ProgramEnd(_, Outcome.Success) :: Nil =>
      // Perfect!
      case _ =>
        fail(s"Unexpected event sequence: ${reversedEvents.map(_.getClass.getSimpleName)}")
    }

    assertEquals(result, Exit.Success(42))
  }

  test("nested fork finalizer ordering") {
    var executionOrder = List.empty[String]

    val innerComputation = Eru
      .succeed("inner")
      .ensure(Eru.effect {
        executionOrder = "inner-finalizer" :: executionOrder
      })

    val outerComputation = Eru
      .fork(innerComputation)
      .flatMap(innerFiber =>
        Eru
          .await(innerFiber)
          .map(exit =>
            s"outer-${exit match {
                case Exit.Success(value) => value
                case _ => "failed"
              }}"
          )
      )
      .ensure(Eru.effect {
        executionOrder = "outer-finalizer" :: executionOrder
      })

    val result = outerComputation.unsafeRunSync()

    assertEquals(result, "outer-inner")

    // Finalizers should execute in FILO order (inner executes first, outer executes last)
    assertEquals(executionOrder, List("inner-finalizer", "outer-finalizer"))
  }

  test("auto-join works with multiple unawaited fibers") {
    var fiber1Executed = false
    var fiber2Executed = false
    var fiber3Executed = false

    val computation1 = Eru.succeed(1).ensure(Eru.effect { fiber1Executed = true })
    val computation2 = Eru.succeed(2).ensure(Eru.effect { fiber2Executed = true })
    val computation3 = Eru.succeed(3).ensure(Eru.effect { fiber3Executed = true })

    val program = for {
      _ <- Eru.fork(computation1) // Not awaited
      _ <- Eru.fork(computation2) // Not awaited
      fiber3 <- Eru.fork(computation3)
      result <- Eru.await(fiber3) // Only this one is awaited
    } yield result

    val finalResult = program.unsafeRunSync()

    assertEquals(finalResult, Exit.Success(3))

    // All finalizers should have executed due to auto-join
    assert(fiber1Executed, "Fiber 1 finalizer should have executed")
    assert(fiber2Executed, "Fiber 2 finalizer should have executed")
    assert(fiber3Executed, "Fiber 3 finalizer should have executed")
  }

  test("fiber error handling with eager evaluation") {
    val computation = Eru.fail("fiber-error")

    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSync()

    assertEquals(result, Exit.Failure("fiber-error"))
  }

  test("fiber finalizers execute even on error") {
    var finalizerExecuted = false

    val computation = Eru
      .fail("test-error")
      .ensure(Eru.effect {
        finalizerExecuted = true
      })

    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSync()

    assertEquals(result, Exit.Failure("test-error"))
    assert(finalizerExecuted, "Finalizer should execute even on error")
  }

  test("basic fiber await functionality") {
    val fiber = EruFiber.completed(Exit.Success(123), Nil)

    // Await should return the exit value
    val result = Eru.await(fiber).unsafeRunSync()
    assertEquals(result, Exit.Success(123))
  }
}
