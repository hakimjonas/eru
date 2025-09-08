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

    // Create a computation that should be interrupted during sleep
    val computation = for {
      fiber <- EruRuntime.fork {
        // Sleep with finalizer - this is the exact pattern from failing test
        import java.time.Duration
        EruRuntime
          .sleep(Duration.ofMillis(100))
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("SLEEP FINALIZER EXECUTED")
          })
      }
      // Wait a moment then interrupt during sleep
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Sleep interruption")))
      exit <- fiber.await
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"Sleep fiber exit: $result")
    println(s"Sleep finalizer executed: ${finalizerExecuted.get()}")

    // Mathematical property: finalizers should execute during sleep interruption
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
