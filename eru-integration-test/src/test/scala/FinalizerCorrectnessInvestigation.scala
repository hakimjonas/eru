package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Mathematically rigorous investigation of finalizer execution during fiber interruption.
  *
  * This test suite is designed to definitively determine whether finalizer interruption correctness
  * is a real architectural issue or an artifact of test conditions.
  *
  * **Methodology**: Each test follows strict isolation, determinism, and observability principles
  * to ensure results are reproducible and logically sound.
  */
class FinalizerCorrectnessInvestigation extends FunSuite {

  /** Test execution counter to ensure proper isolation */
  private val testCounter = new AtomicInteger(0)

  /** Creates isolated test ID for debugging */
  private def nextTestId(): String = s"test-${testCounter.incrementAndGet()}"

  // ============================================================================
  // CONTROL TESTS: Establish baseline behavior
  // ============================================================================

  test("[CONTROL-1] Finalizers execute during normal completion") {
    val testId = nextTestId()
    val finalizerExecuted = new AtomicBoolean(false)
    val executionLog = new AtomicReference("")

    def log(msg: String): Unit = {
      val timestamp = System.nanoTime()
      val current = executionLog.get()
      executionLog.set(s"$current[$timestamp] $testId: $msg\n")
    }

    val computation = for {
      _ <- Eru.effect { log("Starting computation") }
      result <- Eru
        .succeed("completed")
        .ensure(Eru.effect {
          finalizerExecuted.set(true)
          log("Finalizer executed in normal completion")
        })
      _ <- Eru.effect { log(s"Computation completed with result: $result") }
    } yield result

    val result = computation.runIsolatedExit

    log(s"Final result: $result")
    println(s"[CONTROL-1] ${executionLog.get()}")

    assert(finalizerExecuted.get(), s"[$testId] Finalizer must execute during normal completion")
    assert(result == Exit.Success("completed"), s"[$testId] Result must be Success(completed)")
  }

  test("[CONTROL-2] Finalizers execute during typed failure") {
    val testId = nextTestId()
    val finalizerExecuted = new AtomicBoolean(false)
    val executionLog = new AtomicReference("")

    def log(msg: String): Unit = {
      val timestamp = System.nanoTime()
      val current = executionLog.get()
      executionLog.set(s"$current[$timestamp] $testId: $msg\n")
    }

    val computation = for {
      _ <- Eru.effect { log("Starting computation") }
      result <- Eru
        .fail("intentional-error")
        .ensure(Eru.effect {
          finalizerExecuted.set(true)
          log("Finalizer executed in failure case")
        })
      _ <- Eru.effect { log("This should not execute") }
    } yield result

    val result = computation.runIsolatedExit

    log(s"Final result: $result")
    println(s"[CONTROL-2] ${executionLog.get()}")

    assert(finalizerExecuted.get(), s"[$testId] Finalizer must execute during failure")
    assert(result == Exit.Failure("intentional-error"), s"[$testId] Result must be Failure")
  }

  // ============================================================================
  // MATHEMATICAL TEST REPLICA: Exact replication of passing test
  // ============================================================================

  test("[REPLICA-1] Exact replica of FinalizerInterruptionMathematicalTest") {
    val testId = nextTestId()
    val finalizerExecuted = new AtomicBoolean(false)
    val executionLog = new AtomicReference("")

    def log(msg: String): Unit = {
      val timestamp = System.nanoTime()
      val current = executionLog.get()
      executionLog.set(s"$current[$timestamp] $testId: $msg\n")
    }

    log("Starting exact replica test")

    // EXACT COPY of the mathematical test
    val computation = for {
      _ <- Eru.effect { log("Forking fiber with sleep + ensure") }
      fiber <- runtime.fork {
        runtime
          .sleep(Duration.ofSeconds(10))
          .ensure(Eru.effect {
            finalizerExecuted.set(true)
            log("REPLICA finalizer executed")
          })
      }
      _ <- Eru.effect {
        log("Parent sleeping 50ms before interrupt")
        Thread.sleep(50)
      }
      _ <- Eru.effect { log("Sending interrupt to fiber") }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test interruption")))
      _ <- Eru.effect { log("Interrupt sent, awaiting fiber") }
      exit <- fiber.await
      _ <- Eru.effect { log(s"Fiber exit received: $exit") }
    } yield exit

    val result = computation.runIsolatedExit

    log(s"Final computation result: $result")
    println(s"[REPLICA-1] ${executionLog.get()}")

    // Mathematical property: If original test passes, this MUST pass
    val fiberExitResult = result match {
      case Exit.Success(fiberExit) => fiberExit
      case other => fail(s"[$testId] Expected Success(fiberExit), got: $other")
    }

    println(s"[REPLICA-1] Finalizer executed: ${finalizerExecuted.get()}")
    println(s"[REPLICA-1] Fiber exit: $fiberExitResult")

    // If the mathematical test passes, this assertion should pass
    if (!finalizerExecuted.get()) {
      println("[REPLICA-1] ⚠️  REPLICA FAILED - Mathematical test claims to pass but replica fails!")
      println("[REPLICA-1] This suggests test isolation or context differences")
    }
  }

  // ============================================================================
  // CONTROLLED INTERRUPT TESTS: With full observability
  // ============================================================================

  test("[INTERRUPT-1] Controlled fiber interrupt with deterministic timing") {
    val testId = nextTestId()
    val finalizerExecuted = new AtomicBoolean(false)
    val fiberStarted = new CountDownLatch(1)
    val fiberSleeping = new CountDownLatch(1)
    val interruptSent = new CountDownLatch(1)
    val executionLog = new AtomicReference("")

    def log(msg: String): Unit = {
      val timestamp = System.nanoTime()
      val current = executionLog.get()
      executionLog.set(s"$current[$timestamp] $testId: $msg\n")
    }

    val computation = for {
      _ <- Eru.effect { log("Creating fiber with controlled timing") }
      fiber <- runtime.fork {
        (for {
          _ <- Eru.effect {
            log("Child: Fiber started")
            fiberStarted.countDown()
          }
          _ <- Eru.effect {
            log("Child: About to enter sleep")
            fiberSleeping.countDown()
          }
          _ <- Eru.effect {
            log("Child: Waiting for interrupt signal")
            interruptSent.await(5, TimeUnit.SECONDS)
          }
          _ <- Eru.effect { log("Child: Starting sleep") }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { log("Child: Sleep completed (should NOT happen)") }
        } yield "child-done").ensure(Eru.effect {
          finalizerExecuted.set(true)
          log("Child: FINALIZER EXECUTED")
        })
      }
      _ <- Eru.effect {
        log("Parent: Waiting for child to start")
        fiberStarted.await(1, TimeUnit.SECONDS)
      }
      _ <- Eru.effect {
        log("Parent: Waiting for child to reach sleep")
        fiberSleeping.await(1, TimeUnit.SECONDS)
      }
      _ <- Eru.effect {
        log("Parent: Signaling child to proceed")
        interruptSent.countDown()
        Thread.sleep(100) // Give child time to enter sleep
      }
      _ <- Eru.effect { log("Parent: Sending interrupt") }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Controlled interrupt")))
      _ <- Eru.effect { log("Parent: Interrupt sent, awaiting result") }
      exit <- fiber.await
      _ <- Eru.effect { log(s"Parent: Child exited with: $exit") }
    } yield exit

    val result = computation.runIsolatedExit

    log(s"Final result: $result")
    println(s"[INTERRUPT-1] ${executionLog.get()}")

    val fiberExitResult = result match {
      case Exit.Success(fiberExit) => fiberExit
      case other => fail(s"[$testId] Expected Success(fiberExit), got: $other")
    }

    println(s"[INTERRUPT-1] Finalizer executed: ${finalizerExecuted.get()}")
    println(s"[INTERRUPT-1] Fiber exit: $fiberExitResult")

    // Logical assertion: Interrupted fiber with finalizer MUST execute finalizer
    fiberExitResult match {
      case Exit.Interrupt(_, _) =>
        if (!finalizerExecuted.get()) {
          fail(s"[$testId] CORRECTNESS VIOLATION: Fiber interrupted but finalizer did not execute!")
        } else {
          println(s"[$testId] ✅ CORRECT: Fiber interrupted and finalizer executed")
        }
      case other =>
        fail(s"[$testId] Expected Interrupt exit, got: $other")
    }
  }

  // ============================================================================
  // BACKEND COMPARISON TESTS: Different execution contexts
  // ============================================================================

  test("[BACKEND-1] Compare runIsolatedExit vs unsafeRunSync behavior") {
    val testId = nextTestId()
    val finalizerExecuted1 = new AtomicBoolean(false)
    val finalizerExecuted2 = new AtomicBoolean(false)
    val executionLog = new AtomicReference("")

    def log(msg: String): Unit = {
      val timestamp = System.nanoTime()
      val current = executionLog.get()
      executionLog.set(s"$current[$timestamp] $testId: $msg\n")
    }

    // Same computation with different execution methods
    def createTestComputation(finalizerFlag: AtomicBoolean, label: String) = for {
      fiber <- runtime.fork {
        runtime
          .sleep(Duration.ofSeconds(10))
          .ensure(Eru.effect {
            finalizerFlag.set(true)
            log(s"$label finalizer executed")
          })
      }
      _ <- Eru.effect { Thread.sleep(50) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Backend test")))
      exit <- fiber.await
    } yield exit

    log("Testing with runIsolatedExit")
    val result1 = createTestComputation(finalizerExecuted1, "runIsolatedExit").runIsolatedExit

    log("Testing with unsafeRunSync")
    val result2 =
      try {
        createTestComputation(finalizerExecuted2, "unsafeRunSync").unsafeRunSync()
      } catch {
        case ex: Exception =>
          log(s"unsafeRunSync threw exception: $ex")
          Exit.Die(ex)
      }

    println(s"[BACKEND-1] ${executionLog.get()}")
    println(s"[BACKEND-1] runIsolatedExit finalizer: ${finalizerExecuted1.get()}")
    println(s"[BACKEND-1] unsafeRunSync finalizer: ${finalizerExecuted2.get()}")
    println(s"[BACKEND-1] runIsolatedExit result: $result1")
    println(s"[BACKEND-1] unsafeRunSync result: $result2")

    // Mathematical property: Both execution methods should behave identically
    if (finalizerExecuted1.get() != finalizerExecuted2.get()) {
      println("[BACKEND-1] ⚠️  EXECUTION METHOD DIFFERENCE DETECTED!")
      println("[BACKEND-1] This explains the test discrepancies")
    }
  }

  // ============================================================================
  // MULTIPLE RUNS: Statistical reliability
  // ============================================================================

  test("[RELIABILITY-1] Multiple runs for statistical confidence") {
    val testId = nextTestId()
    val runs = 5
    val results = scala.collection.mutable.Buffer[(Boolean, Exit[?, ?])]()

    for (i <- 1 to runs) {
      val finalizerExecuted = new AtomicBoolean(false)

      val computation = for {
        fiber <- runtime.fork {
          runtime
            .sleep(Duration.ofSeconds(10))
            .ensure(Eru.effect {
              finalizerExecuted.set(true)
            })
        }
        _ <- Eru.effect { Thread.sleep(50) }
        _ <- fiber.interrupt(InterruptCause.Cancelled(Some(s"Run $i")))
        exit <- fiber.await
      } yield exit

      val result = computation.runIsolatedExit
      results += ((finalizerExecuted.get(), result))

      // Small delay between runs to prevent interference
      Thread.sleep(10)
    }

    val finalizerSuccesses = results.count(_._1)
    val finalizerFailures = runs - finalizerSuccesses

    println(s"[RELIABILITY-1] Runs: $runs")
    println(s"[RELIABILITY-1] Finalizer successes: $finalizerSuccesses")
    println(s"[RELIABILITY-1] Finalizer failures: $finalizerFailures")
    println(s"[RELIABILITY-1] Success rate: ${finalizerSuccesses.toDouble / runs * 100}%")

    results.zipWithIndex.foreach { case ((finalizerRan, exit), idx) =>
      println(s"[RELIABILITY-1] Run ${idx + 1}: finalizer=$finalizerRan, exit=$exit")
    }

    // Statistical analysis
    if (finalizerSuccesses == 0) {
      println("[RELIABILITY-1] ❌ CONSISTENT FAILURE - All finalizers failed")
    } else if (finalizerSuccesses == runs) {
      println("[RELIABILITY-1] ✅ CONSISTENT SUCCESS - All finalizers executed")
    } else {
      println("[RELIABILITY-1] ⚠️  INCONSISTENT BEHAVIOR - Flaky finalizer execution detected!")
      fail(s"[$testId] Finalizer behavior is non-deterministic: $finalizerSuccesses/$runs successes")
    }
  }
}
