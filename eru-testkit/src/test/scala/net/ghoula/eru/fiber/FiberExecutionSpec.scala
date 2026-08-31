package net.ghoula.eru.fiber

import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*
import net.ghoula.eru.test.EruTestSuite

/** Test suite for fiber execution, lifecycle, and resource management.
  *
  * Validates fiber execution scenarios including auto-join behavior, finalizer leak prevention, and
  * advanced resource cleanup patterns. These tests ensure that the fiber system maintains resource
  * safety even in complex concurrent scenarios and prevents common concurrency pitfalls like
  * resource leaks and improper cleanup ordering.
  */
class FiberExecutionSpec extends EruTestSuite {

  test("fork without await prevents finalizer leaks via auto-join") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = Eru
      .succeed(42)
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
      })

    val program = Eru.fork(computation).map(_ => "main result")

    val result = program.unsafeRunSync()

    assertEquals(result, "main result")
    assert(finalizerExecuted.get(), "Finalizer should have executed via auto-join")
  }

  test("multiple await operations work (allows double-await for now)") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)

    val firstResult = Eru.await(fiber).unsafeRunSync()
    assertEquals(firstResult, Exit.Success(42))

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

    import scala.jdk.CollectionConverters.*
    val eventList = events.asScala.toList

    assertEquals(eventList.length, 4)

    eventList match {
      case EruEvent.ProgramStart(_) ::
          EruEvent.FiberStarted(_) ::
          EruEvent.FiberCompleted(_, Exit.Success(42)) ::
          EruEvent.ProgramEnd(_, Outcome.Success) :: Nil =>
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
      _ <- Eru.fork(computation1)
      _ <- Eru.fork(computation2)
      fiber3 <- Eru.fork(computation3)
      result <- Eru.await(fiber3)
    } yield result

    val finalResult = program.unsafeRunSync()

    assertEquals(finalResult, Exit.Success(3))

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

    val result = Eru.await(fiber).unsafeRunSync()
    assertEquals(result, Exit.Success(123))
  }
}
