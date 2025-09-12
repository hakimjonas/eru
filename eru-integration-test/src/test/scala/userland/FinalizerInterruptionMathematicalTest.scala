package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

/** Mathematical test to verify finalizer execution during interruption.
  *
  * This test determines whether the failing finalizer behavior represents a fundamental design
  * limitation or a correctable issue.
  */
class FinalizerInterruptionMathematicalTest extends TestProgressReporter {

  /** Tests the mathematical property that finalizers execute during fiber interruption.
    *
    * Validates that ensure finalizers are properly executed when a fiber is interrupted, which is a
    * critical correctness property for resource safety.
    */
  test("mathematical property: ensure finalizers execute during fiber interruption") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        Eru
          .succeed("running")
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
          })
      }
      _ <- Eru.effect { Thread.sleep(10) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      exit <- fiber.await
    } yield exit

    val _ = computation.runIsolatedExit

    assert(finalizerExecuted.get(), "Finalizer should execute during fiber interruption")
  }

  /** Tests the mathematical property that finalizers execute during sleep interruption.
    *
    * Validates finalizer execution when interrupting sleeping computations, which may reveal
    * implementation limitations in the current runtime.
    */
  test("mathematical property: ensure finalizers execute during sleep interruption") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        import java.time.Duration
        runtime
          .sleep(Duration.ofMillis(20))
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("SLEEP FINALIZER EXECUTED")
          })
      }
      _ <- Eru.effect { Thread.sleep(10) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      exit <- fiber.await
    } yield exit

    val _ = computation.runIsolatedExit

    assert(finalizerExecuted.get(), "Finalizer should execute during sleep interruption")
  }

  /** Tests that finalizers execute during normal completion as a control case.
    *
    * Validates that ensure finalizers work correctly in the baseline case of normal computation
    * completion, serving as a control for interruption tests.
    */
  test("control test: ensure finalizers execute during normal completion") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = Eru
      .succeed("done")
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
      })

    val _ = computation.runIsolatedExit

    assert(finalizerExecuted.get(), "Finalizer should execute during normal completion")
  }
}
