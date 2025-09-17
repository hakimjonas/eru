package net.ghoula.eru

import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Standalone runtime health verification to prove the hanging is test infrastructure, not runtime
  * bugs
  */
class RuntimeHealthCheck extends EruTestSuite {

  test("runtime can handle coordination primitives under load without hanging") {
    val latch = Eru.countDownLatch(5).unsafeRunSync()
    val barrier = Eru.cyclicBarrier(3).unsafeRunSync()
    val queue = Eru.queue[String](20).unsafeRunSync()

    // If runtime had fundamental bugs, these would deadlock
    val producers = (1 to 3).map { i =>
      (for {
        _ <- latch.countDown
        _ <- queue.offer(s"msg-$i")
        _ <- barrier.await // This would hang if runtime was broken
      } yield s"producer-$i-done").fork.unsafeRunSync()
    }

    // Complete the latch from separate fibers
    (1 to 2).foreach { _ =>
      latch.countDown.fork.unsafeRunSync()
    }

    // All should complete without hanging
    val results = producers.map { producer =>
      producer.await.unsafeRunSync() match {
        case Exit.Success(value) => value
        case other => fail(s"Expected success but got: $other")
      }
    }

    assertEquals(results.size, 3)
    assert(results.forall(_.endsWith("-done")))

    // Verify messages were queued
    val messages = (1 to 3).map(_ => queue.take.unsafeRunSync())
    assertEquals(messages.toSet, Set("msg-1", "msg-2", "msg-3"))
  }

  test("runtime handles hundreds of concurrent fibers without resource exhaustion") {
    val fiberCount = 200
    val completedCounter = new AtomicInteger(0)
    val cleanupCounter = new AtomicInteger(0)

    val fibers = (1 to fiberCount).map { i =>
      (Eru.effect {
        completedCounter.incrementAndGet()
        i
      }.ensure(Eru.effect {
        cleanupCounter.incrementAndGet()
      }))
        .fork
        .unsafeRunSync()
    }

    // Wait for all fibers - if runtime was broken, this would hang or fail
    val results = fibers.map(_.await.unsafeRunSync())
    val successCount = results.count {
      case Exit.Success(_) => true
      case _ => false
    }

    assertEquals(successCount, fiberCount, "All fibers should succeed")
    assertEquals(completedCounter.get(), fiberCount, "All effects should complete")
    assertEquals(cleanupCounter.get(), fiberCount, "All finalizers should run")
  }

  test("runtime maintains correctness under mixed success/failure scenarios") {
    val workCount = 50
    val successCounter = new AtomicInteger(0)
    val failureCounter = new AtomicInteger(0)
    val cleanupCounter = new AtomicInteger(0)

    val work = (1 to workCount).map { i =>
      ({
        val effect = if (i % 3 == 0) {
          Eru.fail(s"deliberate-failure-$i").flatMap { _ =>
            failureCounter.incrementAndGet()
            Eru.succeed(s"failure-$i")
          }
        } else {
          Eru.effect {
            successCounter.incrementAndGet()
            s"success-$i"
          }
        }

        effect
          .ensure(Eru.effect {
            cleanupCounter.incrementAndGet()
          })
          .attempt
      }).fork.unsafeRunSync()
    }

    val results = work.map(_.await.unsafeRunSync())
    val completedCount = results.count {
      case Exit.Success(_) => true
      case _ => false
    }

    assertEquals(completedCount, workCount, "All fibers should complete successfully")
    assert(successCounter.get() > 0, "Some effects should succeed")
    assertEquals(cleanupCounter.get(), workCount, "All cleanup should execute")
  }
}
