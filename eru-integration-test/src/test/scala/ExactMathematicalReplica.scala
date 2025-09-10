package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

/** Exact line-by-line replica of the passing mathematical test */
class ExactMathematicalReplica extends FunSuite {

  /** EXACT COPY of the first mathematical test that claims to pass */
  test("mathematical property: ensure finalizers execute during fiber interruption") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        Eru
          .succeed("running")
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("MATHEMATICAL REPLICA: Finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      exit <- fiber.await
    } yield exit

    val _ = computation.runIsolatedExit

    println(s"MATHEMATICAL REPLICA: Finalizer executed = ${finalizerExecuted.get()}")
    assert(finalizerExecuted.get(), "Finalizer should execute during fiber interruption")
  }
}
