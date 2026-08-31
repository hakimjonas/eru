package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.test.IsolatedTestRunner

/** Test suite for Semaphore with the suspension type system.
  *
  * This suite validates that Semaphore correctly implements the suspension type patterns,
  * preventing deadlocks at compile time and ensuring proper concurrent coordination.
  *
  * Fiber completion is detected by polling the Active latch because currentState is set at
  * construction and never transitions; completion signals through the latch being counted down by
  * complete(). TestClock tests poll real time only to let pool threads register their callbacks;
  * outcomes stay clock-driven. Suspending operations (acquire, acquireN, withPermit) do not expose
  * unsafeRunSync, so blocking patterns like acquire.unsafeRunSync do not compile and must be run in
  * fibers instead.
  */
class SemaphoreSuspensionSpec extends EruTestSuite {

  /** Polls real time (up to 2s) until the clock has at least one scheduled callback. Forked fibers
    * start asynchronously, so callbacks register slightly after forking; this is a pure
    * synchronization mechanism that does not decide any outcome.
    */
  private def awaitClockRegistered(clock: net.ghoula.eru.test.TestClock): Unit = {
    var spins = 0
    while (clock.pendingCount == 0 && spins < 2000) {
      Thread.sleep(1L)
      spins += 1
    }
  }

  /** Advances the clock in 1ms steps until no callbacks remain scheduled. */
  private def advanceUntilIdle(clock: net.ghoula.eru.test.TestClock): Unit = {
    var steps = 0
    while (clock.pendingCount > 0 && steps < 500) {
      clock.advance(java.time.Duration.ofMillis(1))
      steps += 1
    }
  }

  private def completed(f: Fiber[?, ?]): Boolean = f match {
    case uf: UnifiedFiber[?, ?] =>
      uf.currentState match {
        case UnifiedFiberState.Completed(_) => true
        case UnifiedFiberState.Active(latch, _, _, _, _, _) => latch.getCount == 0L
      }
    case _ => false
  }

  /** Polls real time (up to 5s) until the fiber completes. */
  private def awaitCompleted(f: Fiber[?, ?]): Unit = {
    var spins = 0
    while (!completed(f) && spins < 5000) {
      Thread.sleep(1L)
      spins += 1
    }
    assert(completed(f), "fiber did not complete")
  }

  /** Polls real time (up to 5s) until the condition holds. */
  private def awaitCondition(cond: => Boolean): Unit = {
    var spins = 0
    while (!cond && spins < 5000) {
      Thread.sleep(1L)
      spins += 1
    }
    assert(cond, "condition not reached")
  }

  test("Immediate operations can be called with unsafeRunSync") {
    val semaphore = Semaphore.make(3).unsafeRunSync()

    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), false)

    assertEquals(semaphore.tryAcquireN(2).unsafeRunSync(), false)

    semaphore.release.unsafeRunSync()
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)

    semaphore.releaseN(3).unsafeRunSync()

    assertEquals(semaphore.permitsAvailable.unsafeRunSync(), 3L)

    val result = semaphore.withPermitTry {
      Eru.succeed("executed")
    }.unsafeRunSync()
    assertEquals(result, Some("executed"))
  }

  test("Suspending operations require proper handling") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

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
      val semaphore = Eru.semaphore(1).unsafeRunSync()

      val fiber1 = isolatedRuntime.fork {
        semaphore.withPermit {
          isolatedRuntime.sleep(java.time.Duration.ofMillis(50)).flatMap(_ => Eru.succeed("first"))
        }.eru
      }
        .unsafeRunSync()

      val fiber2 = isolatedRuntime.fork {
        semaphore.withPermit(Eru.succeed("second")).eru
      }
        .unsafeRunSync()

      awaitClockRegistered(clock)
      advanceUntilIdle(clock)

      awaitCompleted(fiber1)
      awaitCompleted(fiber2)

      assertEquals(fiber1.await.unsafeRunSync(), Exit.Success("first"))
      assertEquals(fiber2.await.unsafeRunSync(), Exit.Success("second"))
    }
  }

  test("withPermitTry returns None when no permits available") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)

    val result = semaphore.withPermitTry {
      Eru.succeed("should-not-execute")
    }.unsafeRunSync()

    assertEquals(result, None)

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
    assertEquals(after, 2L)
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
    assertEquals(after, 2L)
  }

  test("concurrent acquire/release maintains permit count - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock
      val numOperations = 20
      val semaphore = Eru.semaphore(5).unsafeRunSync()

      val fibers = (1 to numOperations).map { _ =>
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
          .unsafeRunSync()
      }.toList

      var steps = 0
      var allDone = false
      while (!allDone && steps < 1000) {
        if (clock.pendingCount == 0) {
          allDone = fibers.forall(completed)
          Thread.sleep(1L)
        } else {
          clock.advance(java.time.Duration.ofMillis(1))
        }
        steps += 1
      }
      assert(allDone, "operations did not complete")

      val results = fibers.map(_.await.unsafeRunSync())
      assertEquals(results.length, numOperations)
      assertEquals(semaphore.permitsAvailable.unsafeRunSync(), 5L)
    }
  }

  test("withPermits acquires multiple permits atomically - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock
      val semaphore = Eru.semaphore(3).unsafeRunSync()

      val fiber1 = isolatedRuntime.fork {
        semaphore
          .withPermits(3)(
            isolatedRuntime
              .sleep(java.time.Duration.ofMillis(50))
              .map(_ => "got-all-3")
          )
          .eru
      }
        .unsafeRunSync()

      awaitCondition(semaphore.permitsAvailable.unsafeRunSync() == 0L)

      val fiber2 = isolatedRuntime.fork {
        semaphore.withPermits(2)(Eru.succeed("need-2")).eru
      }
        .unsafeRunSync()

      assertEquals(semaphore.permitsAvailable.unsafeRunSync(), 0L)

      awaitClockRegistered(clock)

      advanceUntilIdle(clock)
      awaitCompleted(fiber1)
      awaitCompleted(fiber2)

      assertEquals(fiber1.await.unsafeRunSync(), Exit.Success("got-all-3"))
      assertEquals(fiber2.await.unsafeRunSync(), Exit.Success("need-2"))
      assertEquals(semaphore.permitsAvailable.unsafeRunSync(), 3L)
    }
  }

  test("type system prevents deadlock patterns") {
    val semaphore = Semaphore.make(1).unsafeRunSync()

    val safe = for {
      fiber1 <- semaphore.acquire.eru.fork
      fiber2 <- semaphore.acquire.timeout(java.time.Duration.ofMillis(50)).fork

      _ <- sleep(java.time.Duration.ofMillis(10))
      _ <- semaphore.release.eru

      r1 <- fiber1.await
      r2 <- fiber2.await
    } yield (r1, r2)

    val (r1, r2) = safe.unsafeRunSync()
    assertEquals(r1, Exit.Success(()))
    r2 match {
      case Exit.Success(()) => ()
      case Exit.Failure(_: java.util.concurrent.TimeoutException) => ()
      case other => fail(s"Unexpected result: $other")
    }
  }

  test("withPermitTry allows graceful degradation - TestClock version") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock

      def processWithResource(semaphore: Semaphore)(id: Int): Eru[Nothing, String] =
        semaphore.withPermitTry {
          Eru.succeed(s"processed-$id-with-resource")
        }.eru.map {
          case Some(result) => result
          case None => s"processed-$id-without-resource"
        }

      val semaphore = Eru.semaphore(1).unsafeRunSync()

      val fiber1 = isolatedRuntime.fork {
        semaphore.withPermit {
          isolatedRuntime
            .sleep(java.time.Duration.ofMillis(30))
            .map(_ => "holding-resource")
        }.eru
      }
        .unsafeRunSync()

      awaitCondition(semaphore.permitsAvailable.unsafeRunSync() == 0L)
      val result2 = processWithResource(semaphore)(2).unsafeRunSync()
      val result3 = processWithResource(semaphore)(3).unsafeRunSync()

      awaitClockRegistered(clock)
      advanceUntilIdle(clock)
      fiber1.await.unsafeRunSync()
      val result4 = processWithResource(semaphore)(4).unsafeRunSync()

      assertEquals(result2, "processed-2-without-resource")
      assertEquals(result3, "processed-3-without-resource")
      assertEquals(result4, "processed-4-with-resource")
    }
  }

  test("suspension-safe alternatives to blocking semaphore patterns") {
    val semaphore = Semaphore.make(3).unsafeRunSync()

    assertEquals(semaphore.withPermitTry(Eru.succeed(42)).unsafeRunSync(), Some(42))
    assertEquals(semaphore.tryAcquire.unsafeRunSync(), true)
    assertEquals(semaphore.tryAcquireN(1).unsafeRunSync(), true)
  }
}
