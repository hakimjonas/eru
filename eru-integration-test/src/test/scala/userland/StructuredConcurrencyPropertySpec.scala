package userland

import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.*

/** Property-based tests for structured concurrency guarantees.
  *
  * These tests verify structured concurrency properties through deterministic synchronization
  * mechanisms rather than timing assumptions, establishing that parent-child lifetime relationships
  * and transitive termination guarantees hold correctly.
  */
class StructuredConcurrencyPropertySpec extends munit.FunSuite {

  /** Validates the property that child fiber lifetimes are bounded by parent lifetimes.
    *
    * Property: For all parent and child fibers, lifetime(child) ⊆ lifetime(parent)
    * Verification: Uses synchronization primitives that can only be satisfied if the child is
    * properly terminated when the parent completes.
    */
  test("parent-child lifetime binding property") {
    val childAttemptedCompletion = new AtomicBoolean(false)
    val childStarted = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- runtime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true) }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childAttemptedCompletion.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect {
        // Spin until child starts (purely for coordination, no timing)
        while (!childStarted.get()) { /* spin */ }
      }
      result <- Eru.succeed("parent-completed")
    } yield result

    val result = parentComputation.runIsolatedExit match {
      case Exit.Success(value) => value
      case other => fail(s"Computation should succeed, got: $other")
    }

    assert(result == "parent-completed", "Parent should complete successfully")

    // Child should be interrupted by structured concurrency - no timing needed
    assert(
      !childAttemptedCompletion.get(),
      "STRUCTURED CONCURRENCY VIOLATION: Child continued executing after parent completed"
    )
  }

  /** Validates the property of transitive termination.
    *
    * Property: If A spawns B and B spawns C, then completion of A should terminate both B and C.
    * Formally: parent(A,B) ∧ parent(B,C) → terminate(A) ⇒ terminate(B) ∧ terminate(C)
    */
  test("transitive termination property") {
    val cAttemptedWork = new AtomicBoolean(false)
    val bAttemptedWork = new AtomicBoolean(false)
    val aStarted = new AtomicBoolean(false)
    val bStarted = new AtomicBoolean(false)
    val cStarted = new AtomicBoolean(false)

    val rootComputation = for {
      _ <- runtime.fork {
        for {
          _ <- Eru.effect { aStarted.set(true) }
          _ <- runtime.fork {
            for {
              _ <- Eru.effect { bStarted.set(true) }
              _ <- runtime.fork {
                for {
                  _ <- Eru.effect { cStarted.set(true) }
                  _ <- runtime.sleep(Duration.ofSeconds(10))
                  _ <- Eru.effect { cAttemptedWork.set(true) }
                } yield "c-done"
              }
              _ <- runtime.sleep(Duration.ofSeconds(10))
              _ <- Eru.effect { bAttemptedWork.set(true) }
            } yield "b-done"
          }
          _ <- runtime.sleep(Duration.ofSeconds(10))
        } yield "a-done"
      }
      _ <- Eru.effect {
        // Spin until all nested fibers start (purely for coordination, no timing)
        while (!aStarted.get() || !bStarted.get() || !cStarted.get()) { /* spin */ }
      }
      result <- Eru.succeed("root-completed")
    } yield result

    val result = rootComputation.runIsolatedExit match {
      case Exit.Success(value) => value
      case other => fail(s"Root computation should succeed, got: $other")
    }
    // All nested fibers should be terminated transitively - no timing needed
    assert(result == "root-completed", "Root should complete successfully")
    assert(!bAttemptedWork.get(), "Fiber B should be terminated transitively")
    assert(!cAttemptedWork.get(), "Fiber C should be terminated transitively")
  }

}
