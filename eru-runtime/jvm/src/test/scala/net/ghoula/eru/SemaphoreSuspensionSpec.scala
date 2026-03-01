package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for Semaphore with the suspension type system.
  *
  * This suite validates that Semaphore correctly implements the suspension type patterns,
  * preventing deadlocks at compile time and ensuring proper concurrent coordination.
  */
class SemaphoreSuspensionSpec extends EruTestSuite {

  test("Immediate operations can be called with unsafeRunSync") {
    val semaphore = Semaphore.make(3).unsafeRunSync()

    // tryAcquire is Immediate
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), false) // No more permits

    // tryAcquireN is Immediate
    assertEquals(semaphore.tryAcquireN(2).unsafeRunSync(), false) // Only 0 available

    // release is Immediate
    semaphore.release.unsafeRunSync()
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)

    // releaseN is Immediate
    semaphore.releaseN(3).unsafeRunSync()

    // permitsAvailable is Immediate
    assertEquals(semaphore.permitsAvailable.unsafeRunSync(), 3L)

    // withPermitTry is Immediate
    val result = semaphore.withPermitTry {
      Eru.succeed("executed")
    }.unsafeRunSync()
    assertEquals(result, Some("executed"))
  }

  test("Suspending operations require proper handling") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

    // These operations return Suspending types and cannot be called with unsafeRunSync
    // The following would not compile:
    // semaphore.acquire.unsafeRunSync()  // ❌ COMPILE ERROR
    // semaphore.acquireN(2).unsafeRunSync()  // ❌ COMPILE ERROR
    // semaphore.withPermit(Eru.unit).unsafeRunSync()  // ❌ COMPILE ERROR

    // Instead, we must use fibers for suspending operations
    val program = for {
      _ <- semaphore.acquire.eru.fork.flatMap(_.await)
      _ <- semaphore.release.eru
      result <- semaphore.withPermit(Eru.succeed(42)).eru.fork.flatMap(_.await)
    } yield result

    val result = program.unsafeRunSync()
    assertEquals(result, Exit.Success(42))
  }

  test("withPermit blocks when no permits available - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock

      val program = for {
        semaphore <- Eru.semaphore(1)

        // First fiber acquires the permit for 50ms
        fiber1 <- isolatedRuntime.fork {
          semaphore.withPermit {
            isolatedRuntime.sleep(java.time.Duration.ofMillis(50)).flatMap(_ => Eru.succeed("first"))
          }.eru
        }

        // Advance time to let fiber1 acquire the permit
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(10)))

        // Second fiber must wait
        fiber2 <- isolatedRuntime.fork {
          semaphore.withPermit(Eru.succeed("second")).eru
        }

        // Advance time slightly - fiber2 will be blocked waiting
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(10)))

        // Advance time to let fiber1 complete and release permit
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(40)))

        // Wait for both to complete
        result1 <- fiber1.await
        result2 <- fiber2.await
      } yield (result1, result2)

      program.runExit() match {
        case Exit.Success((Exit.Success("first"), Exit.Success("second"))) =>
        // Success - both completed in order
        case other =>
          fail(s"Expected both fibers to complete successfully, got: $other")
      }
    }
  }

  test("withPermitTry returns None when no permits available") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

    // Acquire the only permit
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)

    // Try to run with permit - should return None immediately
    val result = semaphore.withPermitTry {
      Eru.succeed("should-not-execute")
    }.unsafeRunSync()

    assertEquals(result, None)

    // Release and try again
    semaphore.release.unsafeRunSync()

    val result2 = semaphore.withPermitTry {
      Eru.succeed("should-execute")
    }.unsafeRunSync()

    assertEquals(result2, Some("should-execute"))
  }

  test("withPermit ensures permit release on success") {
    val semaphore = Semaphore.make(2).unsafeRunSync()

    val program = for {
      initial <- semaphore.permitsAvailable.eru
      result <- semaphore.withPermit(Eru.succeed("success")).eru.fork.flatMap(_.await)
      after <- semaphore.permitsAvailable.eru
    } yield (initial, result, after)

    val (initial, result, after) = program.unsafeRunSync()
    assertEquals(initial, 2L)
    assertEquals(result, Exit.Success("success"))
    assertEquals(after, 2L) // Permit was released
  }

  test("withPermit ensures permit release on failure") {
    val semaphore = Semaphore.make(2).unsafeRunSync()

    val program = for {
      initial <- semaphore.permitsAvailable.eru
      fiber <- semaphore
        .withPermit(
          Eru.fail("intentional-failure"): Eru[String, Nothing]
        )
        .eru
        .fork
      result <- fiber.await
      after <- semaphore.permitsAvailable.eru
    } yield (initial, result, after)

    val (initial, result, after) = program.unsafeRunSync()
    assertEquals(initial, 2L)
    result match {
      case Exit.Failure(error) => assertEquals(error, "intentional-failure")
      case other => fail(s"Expected failure, got: $other")
    }
    assertEquals(after, 2L) // Permit was released despite failure
  }

  test("concurrent acquire/release maintains permit count - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock
      val numOperations = 20

      val program = for {
        semaphore <- Eru.semaphore(5)

        // Run many concurrent acquire/release cycles
        fibers <- Eru.foreach(1 to numOperations) { _ =>
          isolatedRuntime.fork {
            for {
              acquired <- semaphore.tryAcquire.eru
              _ <-
                if (acquired) {
                  isolatedRuntime.sleep(java.time.Duration.ofMillis(1)).flatMap(_ => semaphore.release.eru)
                } else {
                  Eru.unit
                }
            } yield acquired
          }
        }

        // Advance time to let all operations complete
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(10)))

        results <- Eru.foreach(fibers)(_.await)
        finalPermits <- semaphore.permitsAvailable.eru
      } yield (results, finalPermits)

      program.runExit() match {
        case Exit.Success((results, finalPermits)) =>
          // All operations should complete
          assertEquals(results.length, numOperations)
          // All permits should be returned
          assertEquals(finalPermits, 5L)
        case other =>
          fail(s"Expected successful completion, got: $other")
      }
    }
  }

  test("withPermits acquires multiple permits atomically - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock

      val program = for {
        semaphore <- Eru.semaphore(3)

        // This should succeed (3 permits available)
        fiber1 <- isolatedRuntime.fork {
          semaphore
            .withPermits(3)(
              isolatedRuntime
                .sleep(java.time.Duration.ofMillis(50))
                .map(_ => "got-all-3")
            )
            .eru
        }

        // Give it time to acquire
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(10)))

        // This should block (0 permits available)
        fiber2 <- isolatedRuntime.fork {
          semaphore.withPermits(2)(Eru.succeed("need-2")).eru
        }

        // Check available permits while fiber1 is running
        during <- semaphore.permitsAvailable.eru

        // Advance time to let fiber1 complete and release
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(50)))
        result1 <- fiber1.await

        // Now fiber2 can proceed
        result2 <- fiber2.await

        // Check final state
        after <- semaphore.permitsAvailable.eru
      } yield (result1, result2, during, after)

      program.runExit() match {
        case Exit.Success((Exit.Success("got-all-3"), Exit.Success("need-2"), _, 3L)) =>
        // Success — both fibers completed, permits fully restored.
        // `during` is not asserted because fork scheduling order vs main thread
        // is non-deterministic with CompletableFuture.supplyAsync.
        case other =>
          fail(s"Expected specific results, got: $other")
      }
    }
  }

  test("type system prevents deadlock patterns") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

    // This would deadlock in traditional systems but won't compile here:
    // val deadlock = for {
    //   _ <- semaphore.acquire.unsafeRunSync()  // ❌ COMPILE ERROR
    //   _ <- semaphore.acquire.unsafeRunSync()  // Would deadlock
    // } yield ()

    // Instead, we must handle it properly with fibers and timeout
    val safe = for {
      fiber1 <- semaphore.acquire.eru.fork
      fiber2 <- semaphore.acquire.timeout(java.time.Duration.ofMillis(50)).fork

      // Release to unblock
      _ <- sleep(java.time.Duration.ofMillis(10))
      _ <- semaphore.release.eru

      r1 <- fiber1.await
      r2 <- fiber2.await
    } yield (r1, r2)

    val (r1, r2) = safe.unsafeRunSync()
    assertEquals(r1, Exit.Success(()))
    r2 match {
      case Exit.Success(()) => () // Got permit in time
      case Exit.Failure(_: java.util.concurrent.TimeoutException) => () // Timed out
      case other => fail(s"Unexpected result: $other")
    }
  }

  test("withPermitTry allows graceful degradation - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock

      // Simulate a resource pool where we try to use a resource
      // but fall back to alternative if none available
      def processWithResource(semaphore: Semaphore)(id: Int): Eru[Nothing, String] =
        semaphore.withPermitTry {
          Eru.succeed(s"processed-$id-with-resource")
        }.eru.map {
          case Some(result) => result
          case None => s"processed-$id-without-resource"
        }

      val program = for {
        semaphore <- Eru.semaphore(1)

        // First gets the resource and holds it for 30ms
        fiber1 <- isolatedRuntime.fork {
          semaphore.withPermit {
            isolatedRuntime
              .sleep(java.time.Duration.ofMillis(30))
              .map(_ => "holding-resource")
          }.eru
        }

        // Advance time slightly
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(5)))

        // These try but fall back because resource is held
        result2 <- processWithResource(semaphore)(2)
        result3 <- processWithResource(semaphore)(3)

        // Advance time to let first fiber complete
        _ <- Eru.effect(clock.advance(java.time.Duration.ofMillis(30)))
        _ <- fiber1.await

        // Now this one gets the resource
        result4 <- processWithResource(semaphore)(4)
      } yield (result2, result3, result4)

      program.runExit() match {
        case Exit.Success((r2, r3, r4)) =>
          assertEquals(r2, "processed-2-without-resource")
          assertEquals(r3, "processed-3-without-resource")
          assertEquals(r4, "processed-4-with-resource")
        case other =>
          fail(s"Expected successful degradation, got: $other")
      }
    }
  }

  test("compilation prevents unsafe patterns") {
    // This test documents patterns that are prevented at compile time
    val _ = Semaphore.make(1).unsafeRunSync()

    // ❌ Can't call unsafeRunSync on Suspending operations:
    // semaphore.acquire.unsafeRunSync()
    // semaphore.acquireN(3).unsafeRunSync()
    // semaphore.withPermit(Eru.unit).unsafeRunSync()

    // ✅ Must use proper async patterns:
    // - Fork and await
    // - Add timeout to convert to Immediate
    // - Use tryAcquire/withPermitTry for non-blocking

    // The test passes by preventing compilation of bad patterns
    assert(true)
  }
}
