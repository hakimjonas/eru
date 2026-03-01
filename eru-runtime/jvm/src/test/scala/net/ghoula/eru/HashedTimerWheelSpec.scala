package net.ghoula.eru

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.internal.HashedTimerWheel

/** Tests for the real HashedTimerWheel implementation.
  *
  * Unlike TimerWheelSpec (which tests Eru.at/after via a mock), these tests exercise the actual
  * hashed wheel timer: bucket distribution, scheduling accuracy, shutdown semantics, and concurrent
  * safety.
  */
final class HashedTimerWheelSpec extends munit.FunSuite {

  // Use a short tick for faster tests
  private def makeWheel(tickMs: Long = 10L, slots: Int = 64): HashedTimerWheel =
    new HashedTimerWheel(tickMs, slots)

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, "s")

  test("scheduled task fires within tick resolution") {
    val wheel = makeWheel()
    try {
      val latch = new CountDownLatch(1)
      val firedAt = new AtomicLong(0L)

      val target = System.currentTimeMillis() + 50L
      wheel.schedule(
        target,
        () => {
          firedAt.set(System.currentTimeMillis())
          latch.countDown()
        }
      )

      assert(latch.await(2, TimeUnit.SECONDS), "Task should fire within timeout")
      val drift = firedAt.get() - target
      // Allow generous tolerance: should fire within ~100ms of target
      assert(drift >= -20 && drift < 200, s"Drift ${drift}ms should be within tolerance")
    } finally {
      wheel.shutdown()
    }
  }

  test("multiple tasks at different delays fire in order") {
    val wheel = makeWheel()
    try {
      val order = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
      val latch = new CountDownLatch(3)
      val now = System.currentTimeMillis()

      // Schedule in reverse order — they should still fire in time order
      wheel.schedule(now + 150L, () => { order.add(3); latch.countDown() })
      wheel.schedule(now + 50L, () => { order.add(1); latch.countDown() })
      wheel.schedule(now + 100L, () => { order.add(2); latch.countDown() })

      assert(latch.await(3, TimeUnit.SECONDS), "All tasks should fire")
      val results = new java.util.ArrayList[Int]()
      order.forEach(results.add(_))
      assertEquals(results.get(0), 1, "First task should fire first")
      assertEquals(results.get(1), 2, "Second task should fire second")
      assertEquals(results.get(2), 3, "Third task should fire third")
    } finally {
      wheel.shutdown()
    }
  }

  test("shutdown is idempotent") {
    val wheel = makeWheel()
    // Call shutdown multiple times — should not throw
    wheel.shutdown()
    wheel.shutdown()
    wheel.shutdown()
  }

  test("past-due scheduling fires promptly") {
    val wheel = makeWheel()
    try {
      val latch = new CountDownLatch(1)
      val firedAt = new AtomicLong(0L)
      val before = System.currentTimeMillis()

      // Schedule in the past (epoch 0)
      wheel.schedule(
        0L,
        () => {
          firedAt.set(System.currentTimeMillis())
          latch.countDown()
        }
      )

      assert(latch.await(2, TimeUnit.SECONDS), "Past-due task should fire promptly")
      val elapsed = firedAt.get() - before
      // Past-due should be clamped to ~1 tick, so it fires quickly
      assert(elapsed < 500, s"Past-due task took ${elapsed}ms, expected < 500ms")
    } finally {
      wheel.shutdown()
    }
  }

  test("concurrent schedule calls do not corrupt state") {
    val wheel = makeWheel()
    try {
      val totalTasks = 200
      val counter = new AtomicInteger(0)
      val allFired = new CountDownLatch(totalTasks)
      val now = System.currentTimeMillis()

      // Schedule from multiple threads concurrently
      val threads = (0 until totalTasks).map { i =>
        Thread.startVirtualThread { () =>
          wheel.schedule(
            now + 30L + (i % 50),
            () => {
              counter.incrementAndGet()
              allFired.countDown()
            }
          )
        }
      }

      // Wait for all threads to finish scheduling
      threads.foreach(_.join())

      assert(allFired.await(5, TimeUnit.SECONDS), s"All $totalTasks tasks should fire, got ${counter.get()}")
      assertEquals(counter.get(), totalTasks)
    } finally {
      wheel.shutdown()
    }
  }
}
