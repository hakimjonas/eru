package userland

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

/** Critical tests to verify structured concurrency guarantees.
  *
  * These tests are designed to catch the structured concurrency leak where child fibers continue
  * running after their parent scope terminates. This is a critical correctness bug that can lead to
  * resource leaks and violation of structured concurrency principles.
  */
class StructuredConcurrencyLeakSpec extends FunSuite {

  test("child fiber should be deterministically interrupted when parent completes successfully") {
    val childStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val parentComputation = for {
      // Capture the child fiber to inspect its state later
      childFiber <- EruRuntime.fork {
        for {
          _ <- Eru.effect { childStarted.set(true); startLatch.countDown() }
          _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Long-running task
        } yield "child-done"
      }
      // Wait for the child to confirm it has started
      _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) }
      // Parent completes successfully, which should trigger cleanup of the child
      _ <- Eru.succeed("parent-completed")
    } yield childFiber // Return the child fiber itself

    // Create a root fiber to run the parent computation. This establishes the scope.
    val rootFiber = EruRuntime.fork {
      parentComputation
    }.unsafeRunSync()

    // Await the root fiber. When it completes, its scope is closed, and children should be interrupted.
    val rootExit = rootFiber.await.unsafeRunSync()

    rootExit match {
      case Exit.Success(childFiber: Fiber[?, ?]) =>
        // The parent has completed. Now, we deterministically check the child's state.
        // Structured concurrency dictates it must have been interrupted.
        val childExit = childFiber.await.unsafeRunSync()
        assert(childStarted.get(), "Child should have started")
        childExit match {
          case _: Exit.Interrupt[?, ?] => // Expected
          case other => fail(s"Child must be interrupted by structured concurrency, but was: $other")
        }
      case other =>
        fail(s"Parent computation should have succeeded, but failed with: $other")
    }
  }

  test("child fiber should be terminated when parent fails") {
    val childStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)
    var childFiber: Option[Fiber[Throwable, String]] = None

    // Create a root fiber to establish proper parent-child relationships
    val rootFiber = EruRuntime.fork {
      val parentComputation = for {
        fiber <- EruRuntime.fork {
          for {
            _ <- Eru.effect { childStarted.set(true); startLatch.countDown(); () }
            _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
          } yield "child-done"
        }
        _ <- Eru.effect { childFiber = Some(fiber) }
        _ <- Eru.effect { startLatch.await(1, TimeUnit.SECONDS) } // Wait for child to start
        _ <- Eru.fail("parent-failed")
      } yield "parent-done"

      parentComputation
    }.unsafeRunSync()

    // Await the root fiber and check the exit state
    val rootExit = rootFiber.await.unsafeRunSync()
    rootExit match {
      case Exit.Failure(error) => assertEquals(error, "parent-failed")
      case other => fail(s"Root fiber should fail with parent-failed, got: $other")
    }

    assert(childStarted.get(), "Child should have started")
    assert(childFiber.isDefined, "childFiber was not assigned")

    // Await the child fiber and check it was interrupted
    val childExit = childFiber.get.await.unsafeRunSync()
    childExit match {
      case _: Exit.Interrupt[?, ?] => // Expected
      case other => fail(s"Child should have been interrupted, but was: $other")
    }
  }

  test("multiple child fibers should all be terminated when parent scope ends") {
    val childrenStarted = (1 to 3).map(_ => new AtomicBoolean(false)).toArray
    val startLatch = new CountDownLatch(3)

    def makeChild(i: Int) = for {
      _ <- Eru.effect { childrenStarted(i).set(true); startLatch.countDown(); () }
      _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
    } yield s"child-$i-done"

    // Create a root fiber to establish proper parent-child relationships
    val rootFiber = EruRuntime.fork {
      val parentComputation = for {
        // Fork children individually instead of using parSequence
        f0 <- EruRuntime.fork(makeChild(0))
        f1 <- EruRuntime.fork(makeChild(1))
        f2 <- EruRuntime.fork(makeChild(2))
        _ <- Eru.effect { startLatch.await(2, TimeUnit.SECONDS) } // Wait for children to start
        result <- Eru.succeed("parent-completed")
      } yield (result, Seq(f0, f1, f2))

      parentComputation
    }.unsafeRunSync()

    // Await the root fiber and check the exit state
    val rootExit = rootFiber.await.unsafeRunSync()
    val (result, childFibers) = rootExit match {
      case Exit.Success(value) => value
      case other => fail(s"Root fiber should succeed, got: $other")
    }
    assertEquals(result, "parent-completed")

    childFibers.zipWithIndex.foreach { case (childFiber, i) =>
      assert(childrenStarted(i).get(), s"Child $i should have started")
      val childExit = childFiber.await.unsafeRunSync()
      childExit match {
        case _: Exit.Interrupt[?, ?] => // Expected
        case other => fail(s"Child $i should have been- interrupted, but was: $other")
      }
    }
  }

  test("deeply nested child fibers should be terminated when root parent ends") {
    val nestedStarted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)
    var innermostFiber: Option[Fiber[Throwable, String]] = None

    // Create a root fiber to establish proper parent-child relationships
    val rootFiber = EruRuntime.fork {
      val parentComputation = for {
        _ <- EruRuntime.fork {
          for {
            _ <- EruRuntime.fork {
              for {
                fiber <- EruRuntime.fork {
                  for {
                    _ <- Eru.effect { nestedStarted.set(true); startLatch.countDown(); () }
                    _ <- EruRuntime.sleep(Duration.ofSeconds(10)) // Very long running
                  } yield "deeply-nested-done"
                }
                _ <- Eru.effect { innermostFiber = Some(fiber) }
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

      parentComputation
    }.unsafeRunSync()

    // Await the root fiber and check the exit state
    val rootExit = rootFiber.await.unsafeRunSync()
    rootExit match {
      case Exit.Success(result) => assertEquals(result, "root-completed")
      case other => fail(s"Root fiber should succeed, got: $other")
    }

    assert(nestedStarted.get(), "Nested child should have started")
    assert(innermostFiber.isDefined, "Innermost fiber was not captured")

    val childExit = innermostFiber.get.await.unsafeRunSync()
    childExit match {
      case _: Exit.Interrupt[?, ?] => // Expected
      case other => fail(s"Deeply nested child should have been interrupted, but was: $other")
    }
  }

  test("mathematically sound: child fiber finalizers execute during structured cleanup") {
    val childStarted = new AtomicBoolean(false)
    val finalizerRan = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)
    val startLatch = new CountDownLatch(1)

    val rootFiber = EruRuntime.fork {
      val parentComputation = for {
        childFiber <- EruRuntime.fork {
          (for {
            _ <- Eru.effect {
              childStarted.set(true)
              startLatch.countDown()
              ()
            }
            // This long sleep ensures the child is running when the parent scope closes.
            _ <- EruRuntime.sleep(Duration.ofSeconds(10))
            _ <- Eru.effect { childCompleted.set(true); () }
          } yield "child-done").ensure(Eru.effect {
            // This finalizer MUST run for structured concurrency to be correct.
            finalizerRan.set(true)
            ()
          })
        }

        // Wait for the child to start before the parent completes.
        _ <- Eru.effect { startLatch.await(2, TimeUnit.SECONDS) }
        // The parent computation completes successfully. This triggers the structured cleanup
        // of its children (the childFiber).
        result <- Eru.succeed("parent-completed")

      } yield (result, childFiber)

      parentComputation
    }.unsafeRunSync()

    // By awaiting the rootFiber, we are testing a critical property of structured concurrency.
    // The `await` call should only complete after the rootFiber's scope has been closed,
    // which involves interrupting all child fibers and running their finalizers.
    //
    // The potential deadlock described by the user occurs if:
    // 1. `rootFiber.await` waits for child finalizers to complete.
    // 2. Child finalizers are only run *after* the parent fiber is considered "finished".
    // If "finished" means `await` has returned, then `await` will never return.
    //
    // A correct implementation ensures that "finishing" involves running child finalizers *before*
    // `await` can return. This test verifies that behavior. If it hangs, the runtime has a deadlock.
    val rootExit = rootFiber.await.unsafeRunSync()

    var result: Option[String] = None
    var childFiber: Option[Fiber[?, ?]] = None

    rootExit match {
      case Exit.Success(value) =>
        result = Some(value._1)
        childFiber = Some(value._2)
      case other => fail(s"Root fiber should succeed, but failed with: $other")
    }

    assertEquals(result.get, "parent-completed")

    // At this point, the parent scope has closed. We can now assert the expected side effects.
    assert(childStarted.get(), "Child should have started")
    // This is the crucial assertion. If the test didn't hang, it means `await` returned.
    // Now we verify that the reason it didn't hang is that the finalizer was indeed executed.
    assert(finalizerRan.get(), "Finalizer must execute during structured cleanup - mathematical requirement")
    assert(!childCompleted.get(), "Child should NOT complete normally - should be interrupted")

    // We also check that the child's exit status is `Interrupt`, confirming it was terminated
    // by structured concurrency and didn't complete its work.
    val childExit = childFiber.get.await.unsafeRunSync()
    childExit match {
      case _: Exit.Interrupt[?, ?] => // Expected: The child was interrupted as part of structured concurrency.
      case other =>
        fail(s"Child should have been interrupted, but was in state: $other. This violates the test's premise.")
    }
  }
}
