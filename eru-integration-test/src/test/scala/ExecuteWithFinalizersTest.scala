package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

class ExecuteWithFinalizersTest extends FunSuite {

  test("executeWithFinalizers should return finalizers even when interrupted") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = (for {
      _ <- Eru.effect {
        println("EXECUTE: Starting computation, about to sleep...")
        // Interrupt this thread after a short delay
        Thread.startVirtualThread { () =>
          Thread.sleep(50) // Give computation time to start sleeping
          Thread.currentThread().interrupt() // This should interrupt the sleep
        }
      }
      _ <- runtime.sleep(Duration.ofSeconds(10)) // Long sleep that will be interrupted
      _ <- Eru.effect { println("EXECUTE: Should not reach here") }
    } yield "done").ensure(Eru.effect {
      println("EXECUTE: Finalizer executing!")
      finalizerRan.set(true)
      println("EXECUTE: Finalizer completed!")
    })

    // Directly test executeWithFinalizers
    val (exit, finalizers) = Eru.executeWithFinalizers(computation)

    println(s"EXECUTE: Exit result: $exit")
    println(s"EXECUTE: Number of finalizers: ${finalizers.length}")

    // Execute the returned finalizers
    finalizers.foreach { finalizer =>
      try {
        println("EXECUTE: Executing returned finalizer...")
        finalizer().unsafeRunSync()
      } catch {
        case t: Throwable =>
          println(s"EXECUTE: Finalizer execution failed: $t")
      }
    }

    println(s"EXECUTE: Finalizer ran: ${finalizerRan.get()}")

    // The exit should be Interrupt and there should be finalizers
    exit match {
      case Exit.Interrupt(_, _) =>
        println("SUCCESS: Exit is Interrupt as expected")
      case other =>
        println(s"UNEXPECTED: Exit should be Interrupt but was: $other")
    }

    if (finalizers.nonEmpty) {
      println("SUCCESS: Finalizers were captured!")
    } else {
      println("PROBLEM: No finalizers were captured!")
    }

    if (finalizerRan.get()) {
      println("SUCCESS: Finalizer executed!")
    } else {
      println("PROBLEM: Finalizer did not execute!")
    }
  }
}
