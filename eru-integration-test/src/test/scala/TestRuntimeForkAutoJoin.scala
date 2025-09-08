package userland

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Test to investigate the exact timing difference between mathematical tests and structured
  * concurrency cleanup that causes finalizer execution discrepancy.
  */
class TestRuntimeForkAutoJoin extends FunSuite {

  test("structured cleanup timing investigation - short sleep") {
    val finalizerRan = new AtomicBoolean(false)
    val childStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
          _ <- EruRuntime
            .sleep(Duration.ofMillis(100)) // SHORT sleep like mathematical test
            .ensure(Eru.effect { finalizerRan.set(true); println("STRUCTURED CLEANUP FINALIZER EXECUTED"); () })
        } yield "child-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val result = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    // Give time for cleanup to happen - same as original test
    Thread.sleep(200)

    println(s"Child started: ${childStarted.get()}")
    println(s"Finalizer ran: ${finalizerRan.get()}")

    assert(childStarted.get(), "Child should have started")
    assert(finalizerRan.get(), "Finalizer should execute during structured cleanup")
  }

  test("structured cleanup timing investigation - LONG sleep with explicit cleanup") {
    val finalizerRan = new AtomicBoolean(false)
    val childStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      childFiber <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
          _ <- EruRuntime
            .sleep(Duration.ofSeconds(10)) // LONG sleep like failing test
            .ensure(Eru.effect {
              finalizerRan.set(true); println("LONG SLEEP STRUCTURED CLEANUP FINALIZER EXECUTED"); ()
            })
        } yield "child-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) }

      // EXPLICIT structured concurrency: Parent should clean up children before completing
      // This is what should happen automatically, but we'll do it manually to test the mechanism
      _ <- childFiber.interrupt(InterruptCause.ParentTerminated(FiberId.fresh(), Exit.Success(())))
      childExit <- childFiber.await

      result <- Eru.succeed("parent-completed")
    } yield (result, childExit)

    val (result, childExit) = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    println(s"Child started: ${childStarted.get()}")
    println(s"Finalizer ran: ${finalizerRan.get()}")
    println(s"Child exit: $childExit")

    assert(childStarted.get(), "Child should have started")
    assert(finalizerRan.get(), "Finalizer should execute during explicit structured cleanup - LONG SLEEP")
  }

  test("direct comparison with mathematical test pattern in structured context") {
    val finalizerRan = new AtomicBoolean(false)
    val childStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    // Use EXACT same pattern as mathematical test but in structured concurrency context
    val parentComputation = for {
      childFiber <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
          _ <- EruRuntime
            .sleep(Duration.ofMillis(100))
            .ensure(Eru.effect {
              finalizerRan.set(true)
              println("DIRECT PATTERN FINALIZER EXECUTED")
            })
        } yield "child-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) }
      _ <- Eru.effect { Thread.sleep(50) } // Wait like mathematical test
      _ <- childFiber.interrupt(
        InterruptCause.Cancelled(Some("Direct interrupt"))
      ) // Explicit interrupt like mathematical test
      exit <- childFiber.await
      result <- Eru.succeed("parent-completed")
    } yield (result, exit)

    val (result, childExit) = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    println(s"Child exit: $childExit")
    println(s"Child started: ${childStarted.get()}")
    println(s"Finalizer ran: ${finalizerRan.get()}")

    assert(childStarted.get(), "Child should have started")
    assert(finalizerRan.get(), "Finalizer should execute with explicit interrupt")
  }
}
