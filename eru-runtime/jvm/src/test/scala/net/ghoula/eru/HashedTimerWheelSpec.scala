package net.ghoula.eru

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import net.ghoula.eru.internal.HashedTimerWheel

/** Deterministic tests for HashedTimerWheel.
  *
  * All tests use manual clock + manual tick + synchronous task runner, so they are fully
  * deterministic with no wall-clock dependency.
  */
final class HashedTimerWheelSpec extends munit.FunSuite {

  private def testWheel(tickMs: Long = 10L, slots: Int = 64, startTime: Long = 1000000L) = {
    val clock = new AtomicLong(startTime)
    val wheel = new HashedTimerWheel(tickMs, slots, () => clock.get(), daemonEnabled = false, taskRunner = _.run())
    (wheel, clock)
  }

  test("scheduled task fires when its bucket is reached") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    // Schedule 50ms into the future → delayTicks = 5
    wheel.schedule(clock.get() + 50L, () => fired.incrementAndGet())

    // Ticks 1–4: task should not fire
    (1 to 4).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire before its bucket")

    // Tick 5: task fires
    wheel.tick()
    assertEquals(fired.get(), 1, "Task should fire when its bucket is reached")

    wheel.shutdown()
  }

  test("task fires even when clock is slightly before epochMillis") {
    // Regression test for the original bug: rounds==0 but clock a few ms early
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val target = clock.get() + 50L
    wheel.schedule(target, () => fired.incrementAndGet())

    // Advance clock to just before target (simulating Thread.sleep jitter)
    clock.set(target - 3L)

    // Advance all 5 ticks
    (1 to 5).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Task must fire even when clock is slightly early")

    wheel.shutdown()
  }

  test("multi-round task decrements rounds before firing") {
    // 64 slots, 10ms tick → one rotation = 640ms
    // Schedule 700ms out → delayTicks=70, rounds=1, bucketOffset=6
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    wheel.schedule(clock.get() + 700L, () => fired.incrementAndGet())

    // First pass through bucket 6 (tick 6): rounds decrements 1→0, task should NOT fire
    (1 to 6).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire on first pass (rounds > 0)")

    // Complete the rotation: ticks 7–64
    (7 to 64).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire mid-rotation")

    // Second pass through bucket 6 (tick 70): rounds==0, fires
    (65 to 70).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Task should fire on second pass through its bucket")

    wheel.shutdown()
  }

  test("multiple tasks at different offsets fire in tick order") {
    val (wheel, clock) = testWheel()
    val order = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val now = clock.get()

    // Schedule in reverse order — should still fire in time order
    wheel.schedule(now + 30L, () => order.add(3))
    wheel.schedule(now + 10L, () => order.add(1))
    wheel.schedule(now + 20L, () => order.add(2))

    // Tick past all three (delayTicks: 1, 2, 3)
    (1 to 3).foreach(_ => wheel.tick())

    val results = new java.util.ArrayList[Int]()
    order.forEach(results.add(_))
    assertEquals(results.size(), 3, "All three tasks should have fired")
    assertEquals(results.get(0), 1)
    assertEquals(results.get(1), 2)
    assertEquals(results.get(2), 3)

    wheel.shutdown()
  }

  test("past-due task fires on first available tick") {
    val (wheel, _) = testWheel()
    val fired = new AtomicInteger(0)

    // epoch=0 is far in the past → delayMs clamped to 0, delayTicks clamped to 1
    wheel.schedule(0L, () => fired.incrementAndGet())

    wheel.tick()
    assertEquals(fired.get(), 1, "Past-due task should fire on first tick")

    wheel.shutdown()
  }

  test("shutdown is idempotent") {
    val (wheel, _) = testWheel()
    wheel.shutdown()
    wheel.shutdown()
    wheel.shutdown()
  }

  test("concurrent schedule calls do not corrupt state") {
    val (wheel, clock) = testWheel()
    val totalTasks = 200
    val counter = new AtomicInteger(0)
    val now = clock.get()

    // Schedule from multiple threads concurrently
    val threads = (0 until totalTasks).map { i =>
      Thread.startVirtualThread { () =>
        wheel.schedule(now + 10L + (i % 50), () => counter.incrementAndGet())
      }
    }
    threads.foreach(_.join())

    // Tick enough to cover all possible buckets (delayTicks 1–5 across 64 slots)
    (1 to 64).foreach(_ => wheel.tick())

    assertEquals(counter.get(), totalTasks, s"All $totalTasks tasks should fire")

    wheel.shutdown()
  }
}
