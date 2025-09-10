package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

/** Precise validation of sleep-specific finalizer issue */
class SleepFinalizerIssueValidation extends FunSuite {

  test("CONTROL: Non-sleep fiber interruption with finalizers - SHOULD WORK") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        // Non-sleep computation with finalizer
        Eru
          .succeed("running")
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("CONTROL: Non-sleep finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Non-sleep test")))
      exit <- fiber.await
    } yield exit

    val result = runtime.timeout(Duration.ofSeconds(3))(computation).runIsolatedExit

    println(s"CONTROL: Finalizer executed = ${finalizerExecuted.get()}")
    println(s"CONTROL: Result = $result")

    assert(finalizerExecuted.get(), "Non-sleep finalizer should execute during interruption")
  }

  test("ISSUE: Sleep fiber interruption with finalizers - EXPECTED TO FAIL") {
    val finalizerExecuted = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        // Sleep computation with finalizer
        runtime
          .sleep(Duration.ofSeconds(10))
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            println("ISSUE: Sleep finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Sleep test")))
      exit <- fiber.await
    } yield exit

    val result = runtime.timeout(Duration.ofSeconds(3))(computation).runIsolatedExit

    println(s"ISSUE: Finalizer executed = ${finalizerExecuted.get()}")
    println(s"ISSUE: Result = $result")

    // This should fail based on our investigation
    // Commenting out assertion to see the behavior
    // assert(finalizerExecuted.get(), "Sleep finalizer should execute during interruption")

    if (!finalizerExecuted.get()) {
      println("ISSUE: ❌ CONFIRMED - Sleep interruption does NOT execute finalizers")
    } else {
      println("ISSUE: ✅ UNEXPECTED - Sleep interruption DOES execute finalizers")
    }
  }

  test("COMPARISON: Effect vs Sleep finalizer behavior under interruption") {
    val effectFinalizerExecuted = new AtomicBoolean(false)
    val sleepFinalizerExecuted = new AtomicBoolean(false)

    // Test 1: Effect-based computation
    val effectComputation = for {
      fiber <- runtime.fork {
        Eru.effect { Thread.sleep(100) }.ensure(Eru.effect {
          effectFinalizerExecuted.set(true)
          println("COMPARISON: Effect finalizer executed!")
        })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Effect test")))
      exit <- fiber.await
    } yield exit

    val effectResult = runtime.timeout(Duration.ofSeconds(3))(effectComputation).runIsolatedExit

    // Test 2: Sleep-based computation
    val sleepComputation = for {
      fiber <- runtime.fork {
        runtime
          .sleep(Duration.ofMillis(100))
          .ensure(Eru.effect {
            sleepFinalizerExecuted.set(true)
            println("COMPARISON: Sleep finalizer executed!")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Sleep test")))
      exit <- fiber.await
    } yield exit

    val sleepResult = runtime.timeout(Duration.ofSeconds(3))(sleepComputation).runIsolatedExit

    println(s"COMPARISON: Effect finalizer executed = ${effectFinalizerExecuted.get()}")
    println(s"COMPARISON: Sleep finalizer executed = ${sleepFinalizerExecuted.get()}")
    println(s"COMPARISON: Effect result = $effectResult")
    println(s"COMPARISON: Sleep result = $sleepResult")

    if (effectFinalizerExecuted.get() && !sleepFinalizerExecuted.get()) {
      println("COMPARISON: ✅ HYPOTHESIS CONFIRMED - Effect interruption works, Sleep interruption fails")
    } else if (!effectFinalizerExecuted.get() && !sleepFinalizerExecuted.get()) {
      println("COMPARISON: ❌ BROADER ISSUE - Both Effect and Sleep interruption fail")
    } else if (effectFinalizerExecuted.get() && sleepFinalizerExecuted.get()) {
      println("COMPARISON: ✅ BOTH WORK - Our hypothesis was wrong")
    } else {
      println("COMPARISON: 🤔 UNEXPECTED PATTERN - Sleep works but Effect doesn't")
    }
  }
}
