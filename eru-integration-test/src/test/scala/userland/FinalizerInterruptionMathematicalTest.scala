package userland

import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

/** Mathematical test to verify finalizer execution during interruption.
  *
  * This test determines whether the failing finalizer behavior represents a fundamental design
  * limitation or a correctable issue.
  */
class FinalizerInterruptionMathematicalTest extends FunSuite {

  test("mathematical property: ensure finalizers execute during fiber interruption") {
    val finalizerExecuted = new AtomicBoolean(false)

    // Create a computation that should be interrupted
    val computation = for {
      fiber <- EruRuntime.fork {
        // Simple effect that should trigger finalizer when interrupted
        Eru
          .succeed("running")
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("FINALIZER EXECUTED")
          })
      }
      // Wait a moment then interrupt
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      exit <- fiber.await
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"Fiber exit: $result")
    println(s"Finalizer executed: ${finalizerExecuted.get()}")

    // Mathematical property: finalizers should execute during interruption
    assert(finalizerExecuted.get(), "Finalizer should execute during fiber interruption")
  }

  test("mathematical property: ensure finalizers execute during sleep interruption") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiberStarted <- Deferred.make[Unit]
      fiber <- EruRuntime.fork {
        import java.time.Duration
        // Signal that the fiber has started, then sleep for a long time.
        fiberStarted.complete(()).flatMap { _ =>
          EruRuntime.sleep(Duration.ofSeconds(10))
        }.ensure(
          Eru.effect {
            finalizerExecuted.set(true)
          }
        )
      }
      // Poll the deferred until it's completed to ensure the fiber has started.
      _ <- Eru.effect {
        var isStarted = false
        while (!isStarted) {
          fiberStarted.poll.unsafeRunSync() match {
            case Some(_) => isStarted = true
            case None    => Thread.sleep(1) // Yield to avoid a pure busy-spin
          }
        }
      }
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      // Await the fiber's completion to ensure its finalizers have fully run.
      _ <- fiber.await
    } yield ()

    computation.unsafeRunSync()

    assert(finalizerExecuted.get(), "Finalizer should execute during sleep interruption")
  }

  test("control test: ensure finalizers execute during normal completion") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = Eru
      .succeed("done")
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
      })

    computation.unsafeRunSync()

    // Control: this should work
    assert(finalizerExecuted.get(), "Finalizer should execute during normal completion")
  }
}
