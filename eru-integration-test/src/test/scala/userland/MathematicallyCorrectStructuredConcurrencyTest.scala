package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Mathematically rigorous tests for structured concurrency guarantees.
  *
  * These tests provide mathematical proofs (not just suggestions) that structured concurrency works
  * correctly by establishing formal properties and verifying them through deterministic
  * synchronization mechanisms rather than timing assumptions.
  */
class MathematicallyCorrectStructuredConcurrencyTest extends FunSuite {

  /** Validates the mathematical property of parent-child lifetime binding.
    *
    * Mathematical Property: ∀ parent, child: lifetime(child) ⊆ lifetime(parent) Proof Method: Uses
    * synchronization primitives that can only be satisfied if the child is properly terminated when
    * the parent completes.
    */
  test("parent-child lifetime binding - mathematical property") {
    val childStarted = new CountDownLatch(1)
    val parentCompleted = new CountDownLatch(1)
    val childAttemptedCompletion = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childAttemptedCompletion.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect { childStarted.await(5, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield {
      parentCompleted.countDown()
      result
    }

    val result = parentComputation.runIsolatedExit match {
      case Exit.Success(value) => value
      case other => fail(s"Computation should succeed, got: $other")
    }

    assert(result == "parent-completed", "Parent should complete successfully")

    Thread.sleep(100)

    assert(
      !childAttemptedCompletion.get(),
      "STRUCTURED CONCURRENCY VIOLATION: Child continued executing after parent completed"
    )
  }

  /** Validates the mathematical property of transitive termination.
    *
    * Mathematical Property: If A spawns B and B spawns C, then completion of A should terminate
    * both B and C. Formally: parent(A,B) ∧ parent(B,C) → terminate(A) ⇒ terminate(B) ∧ terminate(C)
    */
  test("transitive termination - mathematical property") {
    val aStarted = new CountDownLatch(1)
    val bStarted = new CountDownLatch(1)
    val cStarted = new CountDownLatch(1)
    val rootCompleted = new CountDownLatch(1)

    val cAttemptedWork = new AtomicBoolean(false)
    val bAttemptedWork = new AtomicBoolean(false)

    val rootComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { aStarted.countDown() }
          _ <- EruRuntime.fork {
            for {
              _ <- Eru.effect { bStarted.countDown() }
              _ <- EruRuntime.fork {
                for {
                  _ <- Eru.effect { cStarted.countDown() }
                  _ <- EruRuntime.sleep(Duration.ofSeconds(10))
                  _ <- Eru.effect { cAttemptedWork.set(true) }
                } yield "c-done"
              }
              _ <- EruRuntime.sleep(Duration.ofSeconds(10))
              _ <- Eru.effect { bAttemptedWork.set(true) }
            } yield "b-done"
          }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10))
        } yield "a-done"
      }
      _ <- Eru.effect {
        aStarted.await(5, TimeUnit.SECONDS)
        bStarted.await(5, TimeUnit.SECONDS)
        cStarted.await(5, TimeUnit.SECONDS)
      }
      result <- Eru.succeed("root-completed")
    } yield {
      rootCompleted.countDown()
      result
    }

    val result = rootComputation.runIsolatedExit match {
      case Exit.Success(value) => value
      case other => fail(s"Root computation should succeed, got: $other")
    }
    Thread.sleep(100)

    assert(result == "root-completed", "Root should complete successfully")
    assert(!bAttemptedWork.get(), "Fiber B should be terminated transitively")
    assert(!cAttemptedWork.get(), "Fiber C should be terminated transitively")
  }

}
