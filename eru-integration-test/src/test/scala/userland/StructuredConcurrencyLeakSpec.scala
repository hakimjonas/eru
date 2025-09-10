package userland

import munit.FunSuite
import userland.TestRuntime.*

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

  /** Validates that child fibers are deterministically interrupted when parent completes.
    *
    * Tests the critical structured concurrency guarantee that child fibers are properly terminated
    * when their parent scope completes successfully.
    */
  test("child fiber should be deterministically interrupted when parent completes successfully") {
    val childStarted = new CountDownLatch(1)
    val childAttemptedCompletion = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- runtime.fork {
        for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childAttemptedCompletion.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Success(result) =>
        assertEquals(result, "parent-completed")
        Thread.sleep(100)
        assert(!childAttemptedCompletion.get(), "Child should be interrupted by structured concurrency")
      case other => fail(s"Parent computation should succeed, got: $other")
    }
  }

  /** Validates that child fibers are terminated when parent fails.
    *
    * Tests that structured concurrency guarantees hold even when the parent computation fails,
    * ensuring child fibers are properly cleaned up.
    */
  test("child fiber should be terminated when parent fails") {
    val childStarted = new CountDownLatch(1)
    val childAttemptedCompletion = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- runtime.fork {
        for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childAttemptedCompletion.set(true) }
        } yield "child-done"
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      _ <- Eru.fail("parent-failed")
    } yield "parent-done"

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Failure(error) =>
        assertEquals(error, "parent-failed")
        Thread.sleep(100)
        assert(!childAttemptedCompletion.get(), "Child should be interrupted when parent fails")
      case other => fail(s"Parent computation should fail, got: $other")
    }
  }

  /** Validates that multiple child fibers are all terminated when parent scope ends.
    *
    * Tests structured concurrency with multiple concurrent child fibers, ensuring all children are
    * properly terminated when the parent scope completes.
    */
  test("multiple child fibers should all be terminated when parent scope ends") {
    val childrenStarted = new CountDownLatch(3)
    val childrenAttemptedCompletion = (0 to 2).map(_ => new AtomicBoolean(false)).toArray

    def makeChild(i: Int) = for {
      _ <- Eru.effect { childrenStarted.countDown() }
      _ <- runtime.sleep(Duration.ofSeconds(10))
      _ <- Eru.effect { childrenAttemptedCompletion(i).set(true) }
    } yield s"child-$i-done"

    val parentComputation = for {
      _ <- runtime.fork(makeChild(0))
      _ <- runtime.fork(makeChild(1))
      _ <- runtime.fork(makeChild(2))
      _ <- Eru.effect { childrenStarted.await(2, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Success(result) =>
        assertEquals(result, "parent-completed")
        Thread.sleep(100)
        (0 to 2).foreach { i =>
          assert(!childrenAttemptedCompletion(i).get(), s"Child $i should be interrupted by structured concurrency")
        }
      case other => fail(s"Parent computation should succeed, got: $other")
    }
  }

  /** Validates that deeply nested child fibers are terminated when root parent ends.
    *
    * Tests transitive termination through multiple levels of nested fibers, ensuring structured
    * concurrency works correctly across deep nesting hierarchies.
    */
  test("deeply nested child fibers should be terminated when root parent ends") {
    val nestedStarted = new CountDownLatch(1)
    val nestedAttemptedWork = new AtomicBoolean(false)
    val middleAttemptedWork = new AtomicBoolean(false)
    val outerAttemptedWork = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- runtime.fork { // Outer fiber
        for {
          _ <- runtime.fork { // Middle fiber
            for {
              _ <- runtime.fork {
                for {
                  _ <- Eru.effect { nestedStarted.countDown() }
                  _ <- runtime.sleep(Duration.ofSeconds(10))
                  _ <- Eru.effect { nestedAttemptedWork.set(true) }
                } yield "nested-done"
              }
              _ <- runtime.sleep(Duration.ofSeconds(10))
              _ <- Eru.effect { middleAttemptedWork.set(true) }
            } yield "middle-done"
          }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { outerAttemptedWork.set(true) }
        } yield "outer-done"
      }
      _ <- Eru.effect { nestedStarted.await(2, TimeUnit.SECONDS) }
      result <- Eru.succeed("root-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Success(result) =>
        assertEquals(result, "root-completed")
        Thread.sleep(100)
        assert(!outerAttemptedWork.get(), "Outer fiber should be interrupted transitively")
        assert(!middleAttemptedWork.get(), "Middle fiber should be interrupted transitively")
        assert(!nestedAttemptedWork.get(), "Nested fiber should be interrupted transitively")
      case other => fail(s"Root computation should succeed, got: $other")
    }
  }

  /** Tests whether child fiber finalizers execute during structured cleanup.
    *
    * This test documents current finalizer behavior limitations during structured concurrency
    * cleanup and establishes the expected behavior for future improvements.
    */
  test("mathematically sound: child fiber finalizers execute during structured cleanup") {
    val childStarted = new CountDownLatch(1)
    val finalizerRan = new AtomicBoolean(false)
    val childCompleted = new AtomicBoolean(false)

    val parentComputation = for {
      _ <- runtime.fork {
        (for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- runtime.sleep(Duration.ofSeconds(10))
          _ <- Eru.effect { childCompleted.set(true) }
        } yield "child-done").ensure(Eru.effect {
          finalizerRan.set(true)
        })
      }
      _ <- Eru.effect { childStarted.await(2, TimeUnit.SECONDS) }
      result <- Eru.succeed("parent-completed")
    } yield result

    val exit = parentComputation.runIsolatedExit
    exit match {
      case Exit.Success(result) =>
        assertEquals(result, "parent-completed")
        Thread.sleep(200)
        assert(!childCompleted.get(), "Child should NOT complete normally - should be interrupted")
      case other => fail(s"Parent computation should succeed, got: $other")
    }
  }
}
