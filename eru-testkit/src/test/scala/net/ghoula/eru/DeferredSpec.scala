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
  *
  * The TestClock variant drives the completing fiber's sleep deterministically by advancing the
  * virtual clock, then waits for the pool thread by polling the fiber's active latch: while parked,
  * currentState never transitions to Completed, so completion is signaled via the latch count.
  */
final class DeferredSpec extends EruTestSuite {

  test("await blocks until completion and returns the value") {
    val d = Deferred.make[Int].unsafeRunSync()

    val completingFiber = (
      sleep(java.time.Duration.ofMillis(10)).flatMap { _ =>
        d.complete(42).eru
      }
    ).fork.unsafeRunSync()

    val value = d.await.eru.unsafeRunSync()
    assertEquals(value, 42)

    val completed = completingFiber.await.unsafeRunSync()
    assertEquals(completed, Exit.Success(true))
  }

  test("await returns immediately if already completed") {
    val d = Deferred.make[String].unsafeRunSync()
    val completed = d.complete("immediate").unsafeRunSync()
    assertEquals(completed, true)

    val value = d.await.eru.unsafeRunSync()
    assertEquals(value, "immediate")
  }

  test("complete is idempotent and returns false on second call") {
    val d = Deferred.make[String].unsafeRunSync()
    val first = d.complete("ok").unsafeRunSync()
    val second = d.complete("again").unsafeRunSync()
    assertEquals(first, true)
    assertEquals(second, false)

    val value = d.await.eru.unsafeRunSync()
    assertEquals(value, "ok")
  }

  test("multiple fibers can await the same deferred") {
    val d = Deferred.make[Int].unsafeRunSync()

    val waitingFibers = (1 to 5).map { _ =>
      d.await.eru.fork.unsafeRunSync()
    }.toList

    val completed = d.complete(99).unsafeRunSync()
    assertEquals(completed, true)

    waitingFibers.foreach { fiber =>
      val exit = fiber.await.unsafeRunSync()
      assertEquals(exit, Exit.Success(99))
    }
  }
  test("await blocks until completion and returns the value - TestClock version (deterministic fiber coordination)") {
    IsolatedTestRunner.withIsolatedRuntime { isolatedRuntime =>
      val clock = isolatedRuntime.testClock
      val d = Eru.deferred[Int].unsafeRunSync()

      val completingFiber = isolatedRuntime.fork {
        isolatedRuntime.sleep(java.time.Duration.ofMillis(10)).flatMap { _ =>
          d.complete(42).eru
        }
      }
        .unsafeRunSync()

      var spins = 0
      while (clock.pendingCount == 0 && spins < 2000) {
        Thread.sleep(1L)
        spins += 1
      }
      var steps = 0
      while (clock.pendingCount > 0 && steps < 100) {
        clock.advance(java.time.Duration.ofMillis(1))
        steps += 1
      }

      spins = 0
      var fiberDone = false
      while (!fiberDone && spins < 5000) {
        fiberDone = completingFiber match {
          case uf: UnifiedFiber[?, ?] =>
            uf.currentState match {
              case UnifiedFiberState.Completed(_) => true
              case UnifiedFiberState.Active(latch, _, _, _, _, _) => latch.getCount == 0L
            }
          case _ => false
        }
        if (!fiberDone) Thread.sleep(1L)
        spins += 1
      }
      assert(fiberDone, "completing fiber did not finish")

      assertEquals(d.await.eru.unsafeRunSync(), 42)
      assertEquals(completingFiber.await.unsafeRunSync(), Exit.Success(true))
    }
  }
}
