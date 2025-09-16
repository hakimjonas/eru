package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for Deferred concurrent primitive functionality.
  *
  * Validates all operations of the Deferred data type including construction, completion, and await
  * semantics. Deferred provides single-assignment variable semantics that enable safe coordination
  * between concurrent fibers, supporting common patterns like producer-consumer communication and
  * synchronization barriers.
  */
final class DeferredSpec extends EruTestSuite {

  test("await blocks until completion and returns the value") {
    val d = Deferred.make[Int].unsafeRunSync()

    // Fork a fiber that completes the deferred after a short delay
    val completingFiber = (
      sleep(java.time.Duration.ofMillis(10)).flatMap { _ =>
        d.complete(42)
      }
    ).fork.unsafeRunSync()

    // Await should block until completion
    val value = d.await.unsafeRunSync()
    assertEquals(value, 42)

    // Verify the completing fiber succeeded
    val completed = completingFiber.await.unsafeRunSync()
    assertEquals(completed, Exit.Success(true))
  }

  test("await returns immediately if already completed") {
    val d = Deferred.make[String].unsafeRunSync()
    val completed = d.complete("immediate").unsafeRunSync()
    assertEquals(completed, true)

    // Await should return immediately since already completed
    val value = d.await.unsafeRunSync()
    assertEquals(value, "immediate")
  }

  test("complete is idempotent and returns false on second call") {
    val d = Deferred.make[String].unsafeRunSync()
    val first = d.complete("ok").unsafeRunSync()
    val second = d.complete("again").unsafeRunSync()
    assertEquals(first, true)
    assertEquals(second, false)

    // Value should be from first completion
    val value = d.await.unsafeRunSync()
    assertEquals(value, "ok")
  }

  test("multiple fibers can await the same deferred") {
    val d = Deferred.make[Int].unsafeRunSync()

    // Use zipPar for deterministic coordination
    val result = runtime
      .zipPar(
        // Fork multiple fibers that all await the same deferred
        parSequence((1 to 3).map { _ => runtime.fork(d.await) }.toList),
        // Complete the deferred after a small delay to ensure fibers are waiting
        sleep(java.time.Duration.ofMillis(5)).flatMap(_ => d.complete(99))
      )
      .unsafeRunSync()

    val (waitingFibers, completed) = result
    assertEquals(completed, true)

    // All fibers should receive the same value
    waitingFibers.foreach { fiber =>
      val exit = fiber.await.unsafeRunSync()
      assertEquals(exit, Exit.Success(99))
    }
  }

  test("await blocks until completion and returns the value - TestClock version (deterministic fiber coordination)") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock

      val program = for {
        d <- Eru.deferred[Int]
        // Fork a fiber that completes the deferred after a delay
        completingFiber <- isolatedRuntime.fork {
          isolatedRuntime.sleep(java.time.Duration.ofMillis(10)).flatMap { _ =>
            d.complete(42)
          }
        }
        // Advance TestClock to allow the fiber to execute
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(15)))
        // Now await the deferred - should complete deterministically
        value <- d.await
        // Verify the completing fiber succeeded
        completed <- completingFiber.await
      } yield (value, completed)

      program.runExit() match {
        case Exit.Success((value, Exit.Success(completionResult))) =>
          assertEquals(value, 42)
          assertEquals(completionResult, true)
        case other => fail(s"Expected successful deferred coordination, got: $other")
      }

      println("TestClock Deferred: deterministic fiber coordination without 10ms wall-clock delay")
    }
  }
}
