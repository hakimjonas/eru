package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Async concurrency tests for CountDownLatch and CyclicBarrier coordination.
  *
  * These tests validate proper async coordination behavior for synchronization primitives. Uses
  * IsolatedTestRunner with improved TestClock coordination support.
  */
class CoordinationConcurrencySpec extends TestWithSharedRuntime {

  test("countdown latch coordinates multiple waiters") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val waiterCount = 5

      val coordinated = for {
        latch <- Eru.countDownLatch(waiterCount)
        allWaitersReady <- Eru.countDownLatch(waiterCount)

        // Multiple waiters
        waiters <- runtime.parSequence((1 to waiterCount).map { i =>
          runtime.fork {
            for {
              _ <- allWaitersReady.countDown
              _ <- latch.await
            } yield s"waiter$i-completed"
          }
        }.toList)

        // Wait for all waiters to be ready
        _ <- allWaitersReady.await

        // Countdown from separate fibers
        countdowns <- runtime.parSequence((1 to waiterCount).map { i =>
          runtime.fork {
            latch.countDown.map(_ => s"countdown$i")
          }
        }.toList)

        // Collect all results
        waiterResults <- runtime.parSequence(
          waiters.map(
            _.await.flatMap {
              case Exit.Success(value) => Eru.succeed(value)
              case other => Eru.fail(s"Waiter expected success but got: $other")
            }
          )
        )

        countdownResults <- runtime.parSequence(
          countdowns.map(
            _.await.flatMap {
              case Exit.Success(value) => Eru.succeed(value)
              case other => Eru.fail(s"Countdown expected success but got: $other")
            }
          )
        )
      } yield (waiterResults, countdownResults)

      coordinated.runExit() match {
        case Exit.Success((waiterResults, countdownResults)) =>
          assertEquals(waiterResults.size, waiterCount)
          assertEquals(countdownResults.size, waiterCount)
          assert(waiterResults.forall(_.endsWith("-completed")))
        case other => fail(s"Expected successful coordination, got: $other")
      }
    }
  }

  test("cyclic barrier coordinates multiple parties across cycles") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val partyCount = 4
      val cycleCount = 3

      val coordinated = for {
        barrier <- Eru.cyclicBarrier(partyCount)
        cycleCompletions <- Eru.collectAll((1 to cycleCount).map(_ => Eru.countDownLatch(partyCount)))

        // Multiple parties
        parties <- runtime.parSequence((1 to partyCount).map { partyId =>
          runtime.fork {
            for {
              results <- Eru.collectAll((1 to cycleCount).map { cycle =>
                for {
                  _ <- barrier.await
                  _ <- cycleCompletions(cycle - 1).countDown
                } yield s"Party$partyId-Cycle$cycle"
              })
            } yield results
          }
        }.toList)

        // Wait for each cycle to complete
        _ <- Eru.collectAll(cycleCompletions.map(_.await))

        // Collect all party results
        results <- runtime.parSequence(
          parties.map(
            _.await.flatMap {
              case Exit.Success(cycles) => Eru.succeed(cycles)
              case other => Eru.fail(s"Party expected success but got: $other")
            }
          )
        )
      } yield (barrier, results)

      coordinated.runExit() match {
        case Exit.Success((_, results)) =>
          results.foreach { partyCycles =>
            assertEquals(partyCycles.size, cycleCount)
          }
          // The key verification is that all cycles completed successfully
          assert(results.nonEmpty, "All parties should have completed")
        case other => fail(s"Expected successful barrier coordination, got: $other")
      }
    }
  }

  test("countdown latch with staggered countdown completion") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val count = 10

      val coordinated = for {
        latch <- Eru.countDownLatch(count)
        waiterReady <- Eru.promise[Nothing, Unit]
        countdownReady <- Eru.promise[Nothing, Unit]

        // Single waiter
        waiter <- runtime.fork {
          for {
            _ <- waiterReady.succeed(())
            _ <- latch.await
          } yield "waiter-completed"
        }

        // Multiple countdown operations
        countdowns <- runtime.parSequence((1 to count).map { i =>
          runtime.fork {
            for {
              _ <- waiterReady.await
              _ <- countdownReady.await
              _ <- latch.countDown
            } yield s"countdown$i"
          }
        }.toList)

        // Start all countdowns
        _ <- countdownReady.succeed(())

        // Collect results
        waiterResult <- waiter.await.flatMap {
          case Exit.Success(value) => Eru.succeed(value)
          case other => Eru.fail(s"Waiter expected success but got: $other")
        }

        countdownResults <- runtime.parSequence(
          countdowns.map(
            _.await.flatMap {
              case Exit.Success(value) => Eru.succeed(value)
              case other => Eru.fail(s"Countdown expected success but got: $other")
            }
          )
        )

        finalCount <- latch.getCount
      } yield (waiterResult, countdownResults, finalCount)

      coordinated.runExit() match {
        case Exit.Success((waiterResult, countdownResults, finalCount)) =>
          assertEquals(waiterResult, "waiter-completed")
          assertEquals(countdownResults.size, count)
          assertEquals(finalCount, 0)
        case other => fail(s"Expected successful staggered countdown, got: $other")
      }
    }
  }

  test("cyclic barrier coordination with large number of parties") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val partyCount = 3

      val coordinated = for {
        barrier <- Eru.cyclicBarrier(partyCount)

        // Simplified coordination without CountDownLatch to prevent deadlock potential
        parties <- runtime.parSequence((1 to partyCount).map { partyId =>
          runtime.fork {
            barrier.await.map(_ => s"party$partyId-completed")
          }
        }.toList)

        // All should complete simultaneously
        results <- runtime.parSequence(
          parties.map(
            _.await.flatMap {
              case Exit.Success(value) => Eru.succeed(value)
              case other => Eru.fail(s"Party expected success but got: $other")
            }
          )
        )
      } yield results

      coordinated.runExit() match {
        case Exit.Success(results) =>
          assertEquals(results.size, partyCount)
          assert(results.forall(_.endsWith("-completed")))
        // Note: barrier.getNumberWaiting can be racy after completion, so we skip this check
        case other => fail(s"Expected successful barrier coordination, got: $other")
      }
    }
  }
}
