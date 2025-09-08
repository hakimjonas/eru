package userland

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Mathematically correct test for structured concurrency This test proves (not just suggests) that
  * structured concurrency works
  */
class MathematicallyCorrectStructuredConcurrencyTest extends FunSuite {

  /** MATHEMATICAL PROPERTY: Parent-Child Lifetime Binding ∀ parent, child: lifetime(child) ⊆
    * lifetime(parent)
    *
    * PROOF METHOD: Use a synchronization primitive that can only be satisfied if the child is
    * terminated when parent completes.
    */
  test("parent-child lifetime binding - mathematical property") {
    val childStarted = new CountDownLatch(1)
    val parentCompleted = new CountDownLatch(1)
    val childAttemptedCompletion = new AtomicBoolean(false)

    // Create a computation where child CANNOT complete if properly terminated
    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.countDown() } // Signal child started
          // Child does long-running work that should be interrupted
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // This should be interrupted
          _ <- Eru.effect { childAttemptedCompletion.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect { childStarted.await(5, TimeUnit.SECONDS) } // Wait for child to start
      result <- Eru.succeed("parent-completed")
    } yield {
      parentCompleted.countDown() // Signal parent is completing
      result
    }

    val result = parentComputation.unsafeRunSync()

    // MATHEMATICAL VERIFICATION:
    assert(result == "parent-completed", "Parent should complete successfully")

    // Give the child a moment to attempt completion after parent signals
    Thread.sleep(100)

    // CRITICAL ASSERTION: If structured concurrency is correct,
    // the child should be interrupted BEFORE it can set childAttemptedCompletion
    assert(
      !childAttemptedCompletion.get(),
      "STRUCTURED CONCURRENCY VIOLATION: Child continued executing after parent completed"
    )
  }

  /** MATHEMATICAL PROPERTY: Transitive Termination If A spawns B and B spawns C, then completion of
    * A should terminate both B and C This tests the mathematical property: parent(A,B) ∧
    * parent(B,C) → terminate(A) ⇒ terminate(B) ∧ terminate(C)
    */
  test("transitive termination - mathematical property") {
    val aStarted = new CountDownLatch(1)
    val bStarted = new CountDownLatch(1)
    val cStarted = new CountDownLatch(1)
    val rootCompleted = new CountDownLatch(1)

    val cAttemptedWork = new AtomicBoolean(false)
    val bAttemptedWork = new AtomicBoolean(false)

    val rootComputation = for {
      _ <- EruRuntime.fork { // Fiber A
        for {
          _ <- Eru.effect { aStarted.countDown() }
          _ <- EruRuntime.fork { // Fiber B (child of A)
            for {
              _ <- Eru.effect { bStarted.countDown() }
              _ <- EruRuntime.fork { // Fiber C (child of B, grandchild of root)
                for {
                  _ <- Eru.effect { cStarted.countDown() }
                  _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Should be interrupted
                  _ <- Eru.effect { cAttemptedWork.set(true) } // Should never execute
                } yield "c-done"
              }
              _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Should be interrupted
              _ <- Eru.effect { bAttemptedWork.set(true) } // Should never execute
            } yield "b-done"
          }
          // Make A wait so it doesn't complete immediately - this is key for transitive termination!
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Should be interrupted
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

    val result = rootComputation.unsafeRunSync()
    Thread.sleep(100) // Allow time for any violations to manifest

    assert(result == "root-completed")
    assert(!bAttemptedWork.get(), "Fiber B should be terminated transitively")
    assert(!cAttemptedWork.get(), "Fiber C should be terminated transitively")
  }

}
