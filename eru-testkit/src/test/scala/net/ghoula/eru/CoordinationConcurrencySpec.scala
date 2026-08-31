package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Async concurrency tests for CountDownLatch and CyclicBarrier coordination.
  *
  * These tests validate proper async coordination behavior for synchronization primitives.
  *
  * Coordination tests keep waiter counts small to minimize timing sensitivity. Waiters are forked
  * at the top level (the test's own scope) rather than inside parSequence: a nested scope's
  * finally-block would interrupt still-parked waiters before the test could await them. Latch
  * countDown is Immediate (non-suspending), so its Eru wrappers can be parSequenced directly. The
  * barrier creation test avoids real multi-party coordination to prevent deadlocks.
  */
class CoordinationConcurrencySpec extends EruTestSuite {

  test("countdown latch coordinates multiple waiters") {
    val waiterCount = 3

    val coordinated = for {
      latch <- Eru.countDownLatch(waiterCount)

      waiters <- Eru.traverse((1 to waiterCount).toList) { i =>
        latch.await.eru.map(_ => s"waiter$i-completed").fork
      }

      _ <- parSequence((1 to waiterCount).map { _ => latch.countDown.eru }.toList)

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
    val result = for {
      barrier <- Eru.cyclicBarrier(3)
      parties <- barrier.parties.eru
      waiting <- barrier.waiting.eru
    } yield (parties, waiting)

    val (parties, waiting) = result.unsafeRunSync()
    assertEquals(parties, 3)
    assertEquals(waiting, 0)
  }

  test("countdown latch with simple countdown completion") {
    val count = 3

    val coordinated = for {
      latch <- Eru.countDownLatch(count)

      waiter <- latch.await.eru.map(_ => "waiter-completed").fork

      _ <- parSequence((1 to count).map { _ => latch.countDown.eru }.toList)

      waiterResult <- waiter.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case other => Eru.fail(s"Expected success but got: $other")
      }

      finalCount <- latch.count.eru
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
