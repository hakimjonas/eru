package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** Async concurrency tests for CountDownLatch and CyclicBarrier coordination.
  *
  * These tests validate proper async coordination behavior for synchronization primitives.
  */
class CoordinationConcurrencySpec extends TestWithRuntime {

  test("countdown latch coordinates multiple waiters") {
    val waiterCount = 5
    val latch = Eru.countDownLatch(waiterCount).unsafeRunSync()
    val allWaitersReady = Eru.countDownLatch(waiterCount).unsafeRunSync()

    // Multiple waiters
    val waiters = (1 to waiterCount).map { i =>
      runtime.fork {
        for {
          _ <- allWaitersReady.countDown
          _ <- latch.await
        } yield s"waiter$i-completed"
      }.unsafeRunSync()
    }

    // Wait for all waiters to be ready
    allWaitersReady.await.unsafeRunSync()

    // Countdown from separate fibers
    val countdowns = (1 to waiterCount).map { i =>
      runtime.fork {
        latch.countDown.map(_ => s"countdown$i")
      }.unsafeRunSync()
    }

    // All should complete
    val waiterResults = waiters.map { waiter =>
      waiter.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Waiter expected success but got: $other")
      }
    }

    val countdownResults = countdowns.map { countdown =>
      countdown.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Countdown expected success but got: $other")
      }
    }

    assertEquals(waiterResults.size, waiterCount)
    assertEquals(countdownResults.size, waiterCount)
    assert(waiterResults.forall(_.endsWith("-completed")))
  }

  test("cyclic barrier coordinates multiple parties across cycles") {
    val partyCount = 4
    val cycleCount = 3
    val barrier = Eru.cyclicBarrier(partyCount).unsafeRunSync()

    // Track completion of each cycle
    val cycleCompletions = (1 to cycleCount).map(_ => Eru.countDownLatch(partyCount).unsafeRunSync())

    // Multiple parties
    val parties = (1 to partyCount).map { partyId =>
      runtime.fork {
        for {
          results <- Eru.collectAll((1 to cycleCount).map { cycle =>
            for {
              _ <- barrier.await
              _ <- cycleCompletions(cycle - 1).countDown
            } yield s"Party$partyId-Cycle$cycle"
          })
        } yield results
      }.unsafeRunSync()
    }

    // Wait for each cycle to complete
    cycleCompletions.foreach(_.await.unsafeRunSync())

    // All parties should complete all cycles
    val results = parties.map { party =>
      party.await.unsafeRunSync() match {
        case Exit.Success(cycles) => cycles
        case other => fail(s"Party expected success but got: $other")
      }
    }

    results.foreach { partyCycles =>
      assertEquals(partyCycles.size, cycleCount)
    }

    // Verify barrier is reusable (should be 0 waiting)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
  }

  test("countdown latch with staggered countdown completion") {
    val count = 10
    val latch = Eru.countDownLatch(count).unsafeRunSync()
    val waiterReady = Eru.promise[Nothing, Unit].unsafeRunSync()
    val countdownReady = Eru.promise[Nothing, Unit].unsafeRunSync()

    // Single waiter
    val waiter = runtime.fork {
      for {
        _ <- waiterReady.succeed(())
        _ <- latch.await
      } yield "waiter-completed"
    }.unsafeRunSync()

    // Multiple countdown operations
    val countdowns = (1 to count).map { i =>
      runtime.fork {
        for {
          _ <- waiterReady.await
          _ <- countdownReady.await
          _ <- latch.countDown
        } yield s"countdown$i"
      }.unsafeRunSync()
    }

    // Start all countdowns
    countdownReady.succeed(()).unsafeRunSync()

    // Waiter should complete after all countdowns
    val waiterResult = waiter.await.unsafeRunSync()
    waiterResult match {
      case Exit.Success(value) => assertEquals(value, "waiter-completed")
      case other => fail(s"Waiter expected success but got: $other")
    }

    // All countdowns should complete
    countdowns.foreach { countdown =>
      countdown.await.unsafeRunSync() match {
        case Exit.Success(_) => // Expected
        case other => fail(s"Countdown expected success but got: $other")
      }
    }

    assertEquals(latch.getCount.unsafeRunSync(), 0)
  }

  test("cyclic barrier coordination with large number of parties") {
    val partyCount = 20
    val barrier = Eru.cyclicBarrier(partyCount).unsafeRunSync()
    val allReady = Eru.countDownLatch(partyCount).unsafeRunSync()

    // Many parties
    val parties = (1 to partyCount).map { partyId =>
      runtime.fork {
        for {
          _ <- allReady.countDown
          _ <- allReady.await // Wait for all parties to be ready
          _ <- barrier.await
        } yield s"party$partyId-completed"
      }.unsafeRunSync()
    }

    // All should complete simultaneously
    val results = parties.map { party =>
      party.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Party expected success but got: $other")
      }
    }

    assertEquals(results.size, partyCount)
    assert(results.forall(_.endsWith("-completed")))
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
  }
}
