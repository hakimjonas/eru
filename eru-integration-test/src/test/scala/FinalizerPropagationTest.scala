package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Test to verify finalizer propagation during different interrupt scenarios */
class FinalizerPropagationTest extends FunSuite {

  test("HYPOTHESIS: Nested ensure finalizers - outer vs inner") {
    val outerFinalizerExecuted = new AtomicBoolean(false)
    val innerFinalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        // NESTED STRUCTURE: Outer ensure wrapping inner ensure + sleep
        (runtime
          .sleep(Duration.ofSeconds(10))
          .ensure(Eru.effect {
            innerFinalizerExecuted.set(true)
            println("NESTED: Inner finalizer executed!")
          }))
          .ensure(Eru.effect {
            outerFinalizerExecuted.set(true)
            println("NESTED: Outer finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Nested test")))
      exit <- fiber.await
    } yield exit

    val result = computation.runIsolatedExit

    println(s"NESTED: Outer finalizer executed = ${outerFinalizerExecuted.get()}")
    println(s"NESTED: Inner finalizer executed = ${innerFinalizerExecuted.get()}")
    println(s"NESTED: Result = $result")

    // Hypothesis: Outer finalizer might execute, inner might not
    // This would confirm that the issue is with finalizers "inside" the interrupted operation
  }

  test("DIRECT: Direct executeWithFinalizers on sleep with ensure") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = runtime
      .sleep(Duration.ofSeconds(10))
      .ensure(Eru.effect {
        finalizerExecuted.set(true)
        println("DIRECT: Finalizer executed!")
      })

    // Start the computation in a separate thread and interrupt it
    val thread = Thread.startVirtualThread { () =>
      try {
        Thread.sleep(50) // Give main computation time to start
        Thread.currentThread().interrupt() // Self-interrupt to simulate
      } catch {
        case _: InterruptedException => ()
      }
    }

    val (exit, finalizers) =
      try {
        Eru.executeWithFinalizers(computation)
      } catch {
        case ex: Exception =>
          println(s"DIRECT: Exception caught: $ex")
          (Exit.Die(ex), List.empty)
      }

    // Execute the returned finalizers manually
    finalizers.foreach { finalizer =>
      try {
        finalizer().unsafeRunSync()
      } catch {
        case ex: Exception =>
          println(s"DIRECT: Finalizer execution failed: $ex")
      }
    }

    println(s"DIRECT: Finalizer executed = ${finalizerExecuted.get()}")
    println(s"DIRECT: Exit = $exit")
    println(s"DIRECT: Finalizers count = ${finalizers.length}")

    thread.join() // Wait for helper thread
  }

  test("STACK: Deep finalizer stack to test propagation") {
    val finalizers = (1 to 5).map(_ => new AtomicBoolean(false))

    val computation = for {
      fiber <- runtime.fork {
        // Build a deep stack of ensure operations
        finalizers.zipWithIndex.foldLeft(runtime.sleep(Duration.ofSeconds(10))) { case (acc, (flag, index)) =>
          acc.ensure(Eru.effect {
            flag.set(true)
            println(s"STACK: Finalizer $index executed!")
          })
        }
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Stack test")))
      exit <- fiber.await
    } yield exit

    val result = computation.runIsolatedExit

    finalizers.zipWithIndex.foreach { case (flag, index) =>
      println(s"STACK: Finalizer $index executed = ${flag.get()}")
    }
    println(s"STACK: Result = $result")

    val executedCount = finalizers.count(_.get())
    println(s"STACK: Total finalizers executed = $executedCount/${finalizers.length}")
  }
}
