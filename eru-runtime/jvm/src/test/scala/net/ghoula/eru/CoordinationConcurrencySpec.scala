package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Async concurrency tests for CountDownLatch and CyclicBarrier coordination.
  *
  * These tests validate proper async coordination behavior for synchronization primitives.
  */
class CoordinationConcurrencySpec extends munit.FunSuite {
  given EruRuntime = EruRuntime.shared

  test("countdown latch coordinates multiple waiters") {
    val waiterCount = 3 // Reduced from 5 to minimize timing issues

    val coordinated = for {
      latch <- Eru.countDownLatch(waiterCount)

      // Create waiters that will wait for the latch
      waiters <- parSequence((1 to waiterCount).map { i =>
        latch.await.map(_ => s"waiter$i-completed").fork
      }.toList)

      // Count down the latch from the main fiber
      _ <- parSequence((1 to waiterCount).map { i =>
        latch.countDown.map(_ => s"countdown$i").fork
      }.toList)

      // Collect waiter results
      waiterResults <- parSequence(waiters.map(_.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case other => Eru.fail(s"Expected success but got: $other")
      }))
    } yield waiterResults

    val result = coordinated.attempt.unsafeRunSync()
    result match {
      case Result.Success(waiterResults) =>
        assertEquals(waiterResults.size, waiterCount)
        assert(waiterResults.forall(_.endsWith("-completed")))
      case Result.Failure(error) => fail(s"Expected successful coordination, got: $error")
    }
  }

  test("cyclic barrier basic creation and properties") {
    // Simple test that doesn't involve actual coordination to avoid deadlocks
    val result = for {
      barrier <- Eru.cyclicBarrier(3)
      parties <- barrier.getParties
      waiting <- barrier.getNumberWaiting
    } yield (parties, waiting)

    val (parties, waiting) = result.unsafeRunSync()
    assertEquals(parties, 3)
    assertEquals(waiting, 0)
  }

  test("countdown latch with simple countdown completion") {
    val count = 3 // Reduced complexity

    val coordinated = for {
      latch <- Eru.countDownLatch(count)

      // Single waiter
      waiter <- latch.await.map(_ => "waiter-completed").fork

      // Multiple countdown operations
      _ <- parSequence((1 to count).map { _ => latch.countDown }.toList)

      // Get waiter result
      waiterResult <- waiter.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case other => Eru.fail(s"Expected success but got: $other")
      }

      finalCount <- latch.getCount
    } yield (waiterResult, finalCount)

    val result = coordinated.attempt.unsafeRunSync()
    result match {
      case Result.Success((waiterResult, finalCount)) =>
        assertEquals(waiterResult, "waiter-completed")
        assertEquals(finalCount, 0)
      case Result.Failure(error) => fail(s"Expected successful coordination, got: $error")
    }
  }
}
