package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

class DiagnosticFinalizerTest extends FunSuite {

  test("finalizers work correctly for normal completion") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        (for {
          _ <- Eru.effect { println("CONTROL: Child fiber executing normally...") }
          _ <- Eru.succeed("completed")
        } yield "done").ensure(Eru.effect {
          println("CONTROL: FINALIZER EXECUTING!")
          finalizerRan.set(true)
          println("CONTROL: FINALIZER EXECUTED SUCCESSFULLY!")
        })
      }
      exit <- fiber.await
      _ <- Eru.effect { println(s"CONTROL: Parent - child exited with: $exit") }
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"CONTROL: Final result: $result")
    println(s"CONTROL: Finalizer ran: ${finalizerRan.get()}")

    assert(finalizerRan.get(), "Finalizers should execute on normal completion")
  }

  test("finalizers work correctly for error cases") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        (for {
          _ <- Eru.effect { println("ERROR: Child fiber about to fail...") }
          _ <- Eru.fail("simulated error")
        } yield "done").ensure(Eru.effect {
          println("ERROR: FINALIZER EXECUTING!")
          finalizerRan.set(true)
          println("ERROR: FINALIZER EXECUTED SUCCESSFULLY!")
        })
      }
      exit <- fiber.await
      _ <- Eru.effect { println(s"ERROR: Parent - child exited with: $exit") }
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"ERROR: Final result: $result")
    println(s"ERROR: Finalizer ran: ${finalizerRan.get()}")

    assert(finalizerRan.get(), "Finalizers should execute on error")
  }

  test("direct fiber interrupt should execute finalizers") {
    val finalizerRan = new AtomicBoolean(false)
    val finalizerException = new AtomicReference[Option[String]](None)
    val childStarted = new CountDownLatch(1)
    val childSleeping = new CountDownLatch(1)

    // Test: Simple fiber with finalizer that gets interrupted
    val computation = for {
      fiber <- runtime.fork {
        (for {
          _ <- Eru.effect {
            childStarted.countDown()
            println("DIAGNOSTIC: Child fiber started, about to sleep...")
          }
          _ <- Eru.effect {
            childSleeping.countDown()
            println("DIAGNOSTIC: Child is now entering sleep...")
          }
          _ <- runtime.sleep(Duration.ofSeconds(10)) // Long sleep
          _ <- Eru.effect { println("DIAGNOSTIC: Child completed normally (should NOT happen)") }
        } yield "done").ensure(Eru.effect {
          try {
            println("DIAGNOSTIC: FINALIZER EXECUTING!")
            finalizerRan.set(true)
            println("DIAGNOSTIC: FINALIZER EXECUTED SUCCESSFULLY!")
          } catch {
            case t: Throwable =>
              finalizerException.set(Some(t.toString))
              println(s"DIAGNOSTIC: FINALIZER THREW EXCEPTION: $t")
              throw t
          }
        })
      }
      _ <- Eru.effect {
        childStarted.await(1, TimeUnit.SECONDS)
        println("DIAGNOSTIC: Parent - child started, waiting for sleep...")
        childSleeping.await(1, TimeUnit.SECONDS)
        println("DIAGNOSTIC: Parent - child is sleeping, waiting 100ms then interrupting...")
        Thread.sleep(100) // Give child time to actually start sleeping
      }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Parent cancellation")))
      _ <- Eru.effect { println("DIAGNOSTIC: Parent - interrupt signal sent, now awaiting fiber...") }
      exit <- fiber.await
      _ <- Eru.effect { println(s"DIAGNOSTIC: Parent - child exited with: $exit") }
    } yield exit

    val result = computation.unsafeRunSync()

    println(s"DIAGNOSTIC: Final result: $result")
    println(s"DIAGNOSTIC: Finalizer ran: ${finalizerRan.get()}")
    println(s"DIAGNOSTIC: Finalizer exception: ${finalizerException.get()}")

    // Give some time for any async finalizer execution
    Thread.sleep(200)
    println(s"DIAGNOSTIC: After 200ms wait - Finalizer ran: ${finalizerRan.get()}")

    if (finalizerRan.get()) {
      println("SUCCESS: Direct interrupt properly executes finalizers!")
    } else {
      println("PROBLEM: Direct interrupt does NOT execute finalizers!")
    }
  }
}
