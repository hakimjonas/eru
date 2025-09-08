package userland

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Critical tests to verify structured concurrency guarantees.
  *
  * These tests are designed to catch the structured concurrency leak where child fibers continue
  * running after their parent scope terminates. This is a critical correctness bug that can lead to
  * resource leaks and violation of structured concurrency principles.
  */
class StructuredConcurrencyLeakSpec extends FunSuite {

  test("child fiber should be terminated when parent completes successfully") {
    val childStarted = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          _ <- Eru.effect { childCompleted.set(true); () }
        } yield "child-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) } // Wait for child to start
      result <- Eru.succeed("parent-completed")
    } yield result

    val result = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    // Give some time to see if child continues running
    Thread.sleep(500)

    assert(childStarted.get(), "Child should have started")
    assert(
      !childCompleted.get(),
      "Child should NOT complete after parent completes - structured concurrency violation!"
    )
  }

  test("child fiber should be terminated when parent fails") {
    val childStarted = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          _ <- Eru.effect { childCompleted.set(true); () }
        } yield "child-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) } // Wait for child to start
      _ <- Eru.fail("parent-failed")
    } yield "parent-done"

    val ex = intercept[EruException[String]] {
      parentComputation.unsafeRunSync()
    }
    assertEquals(ex.error, "parent-failed")

    // Give some time to see if child continues running
    Thread.sleep(500)

    assert(childStarted.get(), "Child should have started")
    assert(!childCompleted.get(), "Child should NOT complete after parent fails - structured concurrency violation!")
  }

  test("multiple child fibers should all be terminated when parent scope ends") {
    val children = (1 to 3).map(_ => new AtomicBoolean(false)).toArray
    val completions = (1 to 3).map(_ => new AtomicBoolean(false)).toArray
    val startLatch = new CountDownLatch(3)

    val parentComputation = for {
      // Fork children individually instead of using parSequence
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { children(0).set(true); startLatch.countDown(); () }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          _ <- Eru.effect { completions(0).set(true); () }
        } yield "child-0-done"
      }
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { children(1).set(true); startLatch.countDown(); () }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          _ <- Eru.effect { completions(1).set(true); () }
        } yield "child-1-done"
      }
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.effect { children(2).set(true); startLatch.countDown(); () }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          _ <- Eru.effect { completions(2).set(true); () }
        } yield "child-2-done"
      }
      _ <- Eru.effect { startLatch.await(2, TimeUnit.SECONDS) } // Wait for children to start
      result <- Eru.succeed("parent-completed")
    } yield result

    val result = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    // Give some time to see if children continue running
    Thread.sleep(500)

    (0 to 2).foreach { i =>
      assert(children(i).get(), s"Child $i should have started")
      assert(!completions(i).get(), s"Child $i should NOT complete - structured concurrency violation!")
    }
  }

  test("deeply nested child fibers should be terminated when root parent ends") {
    val nested = new AtomicBoolean(false)
    val nestedCompleted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- EruRuntime.fork {
            for {
              _ <- EruRuntime.fork {
                for {
                  _ <- Eru.effect { nested.set(true); startLatch.countDown(); () }
                  _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
                  _ <- Eru.effect { nestedCompleted.set(true); () }
                } yield "deeply-nested-done"
              }
              // Make Middle wait so it doesn't complete immediately
              _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Should be interrupted
            } yield "middle-done"
          }
          // Make Outer wait so it doesn't complete immediately
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Should be interrupted
        } yield "outer-done"
      }
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) } // Wait for nested to start
      result <- Eru.succeed("root-completed")
    } yield result

    val result = parentComputation.unsafeRunSync()
    assertEquals(result, "root-completed")

    // Give some time to see if nested continues running
    Thread.sleep(500)

    assert(nested.get(), "Nested child should have started")
    assert(!nestedCompleted.get(), "Deeply nested child should NOT complete - structured concurrency violation!")
  }

  test("mathematically sound: child fiber finalizers execute during structured cleanup") {
    val childStarted = new AtomicBoolean(false)
    val finalizerRan = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)
    val finalizerLatch = new CountDownLatch(1)

    // Use deterministic synchronization instead of arbitrary timing
    val parentComputation = for {
      childFiber <- EruRuntime.fork {
        for {
          _ <- Eru.effect {
            childStarted.set(true)
            startLatch.countDown()
            ()
          }
          // Use a long-running interruptible operation
          _ <- EruRuntime
            .sleep(Duration.ofSeconds(30))
            .ensure(Eru.effect {
              finalizerRan.set(true)
              finalizerLatch.countDown() // Signal finalizer completion
              ()
            })
          _ <- Eru.effect { childCompleted.set(true); () }
        } yield "child-done"
      }

      // Wait for child to start (deterministic)
      _ <- Eru.effect { startLatch.await(2, TimeUnit.SECONDS) }

      // Parent completes immediately - this should trigger structured cleanup
      result <- Eru.succeed("parent-completed")

      // CRITICAL: Wait for structured cleanup to complete finalizers
      // This is the deterministic test - if structured concurrency is correct,
      // the finalizer MUST complete before parent fiber finishes
      _ <- Eru.effect {
        val finalizerCompleted = finalizerLatch.await(1, TimeUnit.SECONDS)
        if (!finalizerCompleted) {
          throw new AssertionError("Structured concurrency violation: finalizer did not execute within reasonable time")
        }
      }

    } yield (result, childFiber)

    val (result, childFiber) = parentComputation.unsafeRunSync()
    assertEquals(result, "parent-completed")

    // Deterministic verification - no timing assumptions
    assert(childStarted.get(), "Child should have started")
    assert(finalizerRan.get(), "Finalizer must execute during structured cleanup - mathematical requirement")
    assert(!childCompleted.get(), "Child should NOT complete normally - should be interrupted")

    // Additional verification: child fiber should be in interrupted state
    val childExit = childFiber.await.unsafeRunSync()
    childExit match {
      case Exit.Success(_) =>
        // If child succeeded, finalizer should have run during normal completion
        assert(finalizerRan.get(), "Finalizer should run on successful completion")
      case Exit.Interrupt(_, _) =>
        // If child was interrupted, finalizer should have run during interruption
        assert(finalizerRan.get(), "Finalizer should run during interruption")
      case other =>
        fail(s"Unexpected child exit state: $other")
    }
  }
}
