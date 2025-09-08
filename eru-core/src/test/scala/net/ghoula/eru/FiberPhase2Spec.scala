package net.ghoula.eru

import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.CorePrelude.*

/** Advanced test suite for Phase 2 fiber operations and resource management.
  *
  * Validates sophisticated fiber lifecycle scenarios including auto-join behavior, finalizer leak
  * prevention, and advanced resource cleanup patterns. These tests ensure that the fiber system
  * maintains resource safety even in complex concurrent scenarios and prevents common concurrency
  * pitfalls like resource leaks and improper cleanup ordering.
  */
class FiberPhase2Spec extends FunSuite {

  test("fork without await prevents finalizer leaks via auto-join") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = Eru
      .succeed(42)
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
      })

    val program = Eru.fork(computation).map(_ => "main result")

    val result = program.unsafeRunSync()

    // Main computation should succeed
    assertEquals(result, "main result")

    // Finalizer should have been executed due to auto-join
    assert(finalizerExecuted.get(), "Finalizer should have executed via auto-join")
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
    val events = new java.util.concurrent.ConcurrentLinkedQueue[EruEvent]()
    val observer = new EruObserver {
      def onEvent(event: EruEvent): Unit = events.add(event)
    }

    val computation = Eru.succeed(42)
    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSyncWith(observer)

    // Check that we got the expected events
    import scala.jdk.CollectionConverters.*
    val eventList = events.asScala.toList

    // Should have ProgramStart, FiberStarted, FiberCompleted, ProgramEnd
    assertEquals(eventList.length, 4)

    eventList match {
      case EruEvent.ProgramStart(_) ::
          EruEvent.FiberStarted(_) ::
          EruEvent.FiberCompleted(_, Exit.Success(42)) ::
          EruEvent.ProgramEnd(_, Outcome.Success) :: Nil =>
      // Perfect!
      case _ =>
        fail(s"Unexpected event sequence: ${eventList.map(_.getClass.getSimpleName)}")
    }

    assertEquals(result, Exit.Success(42))
  }

  test("nested fork finalizer ordering") {
    val executionOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val innerComputation = Eru
      .succeed("inner")
      .ensure(Eru.effect {
        executionOrder.add("inner-finalizer")
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
        executionOrder.add("outer-finalizer")
      })

    val result = outerComputation.unsafeRunSync()

    assertEquals(result, "outer-inner")

    // Structured concurrency: child finalizers execute before parent finalizers
    // This ensures proper resource cleanup dependency ordering
    import scala.jdk.CollectionConverters.*
    assertEquals(executionOrder.asScala.toList, List("inner-finalizer", "outer-finalizer"))
  }

  test("auto-join works with multiple unawaited fibers") {
    val fiber1Executed = new AtomicBoolean(false)
    val fiber2Executed = new AtomicBoolean(false)
    val fiber3Executed = new AtomicBoolean(false)

    val computation1 = Eru.succeed(1).ensure(Eru.effect { fiber1Executed.set(true) })
    val computation2 = Eru.succeed(2).ensure(Eru.effect { fiber2Executed.set(true) })
    val computation3 = Eru.succeed(3).ensure(Eru.effect { fiber3Executed.set(true) })

    val program = for {
      _ <- Eru.fork(computation1) // Not awaited
      _ <- Eru.fork(computation2) // Not awaited
      fiber3 <- Eru.fork(computation3)
      result <- Eru.await(fiber3) // Only this one is awaited
    } yield result

    val finalResult = program.unsafeRunSync()

    assertEquals(finalResult, Exit.Success(3))

    // All finalizers should have executed due to auto-join
    assert(fiber1Executed.get(), "Fiber 1 finalizer should have executed")
    assert(fiber2Executed.get(), "Fiber 2 finalizer should have executed")
    assert(fiber3Executed.get(), "Fiber 3 finalizer should have executed")
  }

  test("fiber error handling with eager evaluation") {
    val computation = Eru.fail("fiber-error")

    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSync()

    assertEquals(result, Exit.Failure("fiber-error"))
  }

  test("fiber finalizers execute even on error") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = Eru
      .fail("test-error")
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
      })

    val program = Eru.fork(computation).flatMap(fiber => Eru.await(fiber))

    val result = program.unsafeRunSync()

    assertEquals(result, Exit.Failure("test-error"))
    assert(finalizerExecuted.get(), "Finalizer should execute even on error")
  }

  test("basic fiber await functionality") {
    val fiber = EruFiber.completed(Exit.Success(123), Nil)

    // Await should return the exit value
    val result = Eru.await(fiber).unsafeRunSync()
    assertEquals(result, Exit.Success(123))
  }
}
