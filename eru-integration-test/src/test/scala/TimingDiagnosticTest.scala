package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

class TimingDiagnosticTest extends FunSuite {

  test("investigate timing of thread interruption during finalizer execution") {
    val childStarted = new CountDownLatch(1)
    val finalizerStarted = new CountDownLatch(1)
    val interruptSent = new CountDownLatch(1)
    val finalizerCompleted = new AtomicBoolean(false)

    // Test with controlled timing to understand where interruption occurs
    val computation = for {
      fiber <- runtime.fork {
        (for {
          _ <- Eru.effect {
            childStarted.countDown()
            println("TIMING: Child started, waiting for interrupt signal...")
          }
          // Wait for parent to send interrupt before proceeding
          _ <- Eru.effect {
            interruptSent.await(5, TimeUnit.SECONDS)
            println("TIMING: Child received signal, now sleeping...")
          }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { println("TIMING: Child completed normally (should NOT happen)") }
        } yield "done").ensure(Eru.effect {
          println("TIMING: FINALIZER STARTING!")
          finalizerStarted.countDown()
          try {
            // Finalizer does some work
            Thread.sleep(100) // Give time for timing observations
            finalizerCompleted.set(true)
            println("TIMING: FINALIZER COMPLETED SUCCESSFULLY!")
          } catch {
            case t: Throwable =>
              println(s"TIMING: FINALIZER INTERRUPTED: $t")
              throw t
          }
        })
      }
      _ <- Eru.effect {
        childStarted.await(1, TimeUnit.SECONDS)
        println("TIMING: Parent - child started, sending interrupt signal...")
        interruptSent.countDown()
        // Give child a moment to start processing
        Thread.sleep(50)
        println("TIMING: Parent - now interrupting fiber...")
      }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Controlled timing test")))
      _ <- Eru.effect { println("TIMING: Parent - interrupt completed") }
      exit <- fiber.await
      _ <- Eru.effect { println(s"TIMING: Parent - child final exit: $exit") }
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"TIMING: Final result: $result")
    println(s"TIMING: Finalizer started: ${finalizerStarted.getCount == 0}")
    println(s"TIMING: Finalizer completed: ${finalizerCompleted.get()}")

    // Give some time for any async activity
    Thread.sleep(200)
    println(s"TIMING: After delay - Finalizer completed: ${finalizerCompleted.get()}")
  }
}
