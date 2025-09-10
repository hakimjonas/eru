package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

class ExactReplicaTest extends FunSuite {

  test("exact replica of working mathematical test") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        runtime
          .sleep(Duration.ofSeconds(10))
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("EXACT REPLICA: Finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      exit <- fiber.await
    } yield exit

    val result = computation.runIsolatedExit

    println(s"EXACT REPLICA: Result=$result, Finalizer ran=${finalizerExecuted.get()}")
    assert(finalizerExecuted.get(), "Exact replica should execute finalizer")
  }
}
