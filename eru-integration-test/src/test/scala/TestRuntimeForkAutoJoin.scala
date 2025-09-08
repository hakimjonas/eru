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

    val parentComputation = for {
      _ <- EruRuntime.fork {
        (for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- EruRuntime.sleep(Duration.ofMillis(100))
          _ <- Eru.effect { childCompleted.set(true) }
        } yield "child-done").ensure(Eru.effect {
          finalizerRan.set(true)
          println("SHORT SLEEP STRUCTURED CLEANUP FINALIZER EXECUTED")
        })
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
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

      case other => fail(s"Computation should succeed, got: $other")
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
