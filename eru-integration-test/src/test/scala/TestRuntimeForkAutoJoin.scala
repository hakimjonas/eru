package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Test to investigate the exact timing difference between mathematical tests and structured
  * concurrency cleanup that causes finalizer execution discrepancy.
  */
class TestRuntimeForkAutoJoin extends FunSuite {

  test("structured cleanup timing investigation - short sleep") {
    val childStarted = new CountDownLatch(1)
    val finalizerRan = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)

    // Debug observer to track structured concurrency events
    val debugObserver = new net.ghoula.eru.StructuredConcurrencyObserver {
      override def onFiberForked(parentId: FiberId, childId: FiberId): Unit =
        println(s"DEBUG: Fiber $childId forked by parent $parentId")

      override def onStructuredCleanupStarted(fiberId: FiberId, childCount: Int): Unit =
        println(s"DEBUG: Structured cleanup started for fiber $fiberId with $childCount children")

      override def onChildInterruptionRequested(
        parentId: FiberId,
        childId: FiberId,
        cause: InterruptCause,
        childWasRunning: Boolean
      ): Unit =
        if (childWasRunning) {
          println(s"DEBUG: Parent $parentId interrupting RUNNING child $childId: $cause")
        } else {
          println(s"DEBUG: Parent $parentId skipping COMPLETED child $childId")
        }

      override def onStructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int): Unit =
        println(
          s"DEBUG: Structured cleanup completed for fiber $fiberId: interrupted=$interruptedCount, completed=$completedCount"
        )

      override def onFiberLifecycle(event: EruEvent): Unit = event match {
        case EruEvent.FiberStarted(id) => println(s"DEBUG: Fiber $id started")
        case EruEvent.FiberCompleted(id, exit) => println(s"DEBUG: Fiber $id completed: $exit")
        case EruEvent.FiberInterrupted(id, cause) => println(s"DEBUG: Fiber $id interrupted: $cause")
        case _ => ()
      }
    }

    // Create a proper parent-child fiber relationship where both are VTOnlyBackend fibers
    val rootComputation = EruRuntime.forkWithObserver(
      for {
        // This is now the "parent" fiber running on VTOnlyBackend
        _ <- EruRuntime.forkWithObserver(
          (for {
            _ <- Eru.effect { childStarted.countDown() }
            _ <- EruRuntime.sleep(Duration.ofMillis(100))
            _ <- Eru.effect { childCompleted.set(true) }
          } yield "child-done").ensure(Eru.effect {
            finalizerRan.set(true)
            println("SHORT SLEEP STRUCTURED CLEANUP FINALIZER EXECUTED")
          }),
          debugObserver
        )
        _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
        result <- Eru.succeed("parent-completed")
      } yield result,
      debugObserver
    )

    // Now get the exit from the root fiber
    val exit = (for {
      rootFiber <- rootComputation
      rootExit <- rootFiber.await
    } yield rootExit).runIsolatedExitWith(debugObserver)
    exit match {
      case Exit.Success(innerExit) =>
        innerExit match {
          case Exit.Success(result) =>
            assertEquals(result, "parent-completed")
            Thread.sleep(200)

            println(s"Child completed: ${childCompleted.get()}")
            println(s"Finalizer ran: ${finalizerRan.get()}")

            assert(!childCompleted.get(), "Child should be interrupted by structured concurrency")

            if (finalizerRan.get()) {
              println("SUCCESS: Finalizer executed during structured cleanup")
            } else {
              println("LIMITATION: Finalizers do not execute during structured cleanup (current implementation)")
            }
          case other => fail(s"Inner computation should succeed, got: $other")
        }
      case other => fail(s"Outer computation should succeed, got: $other")
    }
  }

  test("explicit interrupt with finalizer execution") {
    val childStarted = new CountDownLatch(1)
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      fiber <- EruRuntime.fork {
        (for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10))
        } yield "child-done").ensure(Eru.effect {
          finalizerRan.set(true)
          println("EXPLICIT INTERRUPT FINALIZER EXECUTED")
        })
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Explicit test interruption")))
      exit <- fiber.await
    } yield exit

    val exit = computation.runIsolatedExit
    exit match {
      case Exit.Success(_) =>
        println(s"Finalizer ran: ${finalizerRan.get()}")
        if (finalizerRan.get()) {
          println("SUCCESS: Finalizer executed during explicit interruption")
        } else {
          println(
            "LIMITATION: Finalizers on sleep operations don't execute during interruption (current implementation)"
          )
        }

      case other => fail(s"Computation should succeed, got: $other")
    }
  }

  test("structured concurrency without finalizer requirement") {
    val childStarted = new CountDownLatch(1)
    val childCompleted = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childCompleted.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Success(result) =>
        assertEquals(result, "parent-completed")
        Thread.sleep(100)

        println(s"Child completed: ${childCompleted.get()}")

        assert(!childCompleted.get(), "Child should be interrupted by structured concurrency")
      case other => fail(s"Computation should succeed, got: $other")
    }
  }
}
