package net.ghoula.eru

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import net.ghoula.eru.internal.HashedTimerWheel

/** Deterministic tests for HashedTimerWheel.
  *
  * All tests use manual clock + manual tick + synchronous task runner, so they are fully
  * deterministic with no wall-clock dependency.
  *
  * Scheduling pads one tick past the ceiling so tasks complete at-or-after the requested duration
  * regardless of schedule phase. The concurrent cancel-vs-fire test allows a tolerance range
  * because cancel-vs-fire races can go either way: at least the non-canceled half must fire, and at
  * most all entries fire.
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

    wheel.schedule(clock.get() + 50L, () => fired.incrementAndGet())

    (1 to 5).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire before its bucket")

    wheel.tick()
    assertEquals(fired.get(), 1, "Task should fire when its bucket is reached")

    wheel.shutdown()
  }

  test("schedule pads one tick past ceiling: delay not a multiple of tickDur fires at-or-after") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    wheel.schedule(clock.get() + 25L, () => fired.incrementAndGet())

    (1 to 3).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task must not fire before the padded tick")

    wheel.tick()
    assertEquals(fired.get(), 1, "Task should fire at the padded tick")

    wheel.shutdown()
  }

  test("task fires even when clock is slightly before epochMillis") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val target = clock.get() + 50L
    wheel.schedule(target, () => fired.incrementAndGet())

    clock.set(target - 3L)

    (1 to 6).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Task must fire even when clock is slightly early")

    wheel.shutdown()
  }

  test("multi-round task decrements rounds before firing") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    wheel.schedule(clock.get() + 700L, () => fired.incrementAndGet())

    (1 to 7).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire on first pass (rounds > 0)")

    (8 to 64).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Task should not fire mid-rotation")

    (65 to 71).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Task should fire on second pass through its bucket")

    wheel.shutdown()
  }

  test("multiple tasks at different offsets fire in tick order") {
    val (wheel, clock) = testWheel()
    val order = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val now = clock.get()

    wheel.schedule(now + 30L, () => order.add(3))
    wheel.schedule(now + 10L, () => order.add(1))
    wheel.schedule(now + 20L, () => order.add(2))

    (1 to 4).foreach(_ => wheel.tick())

    val results = new java.util.ArrayList[Int]()
    order.forEach(results.add(_))
    assertEquals(results.size(), 3, "All three tasks should have fired")
    assertEquals(results.get(0), 1)
    assertEquals(results.get(1), 2)
    assertEquals(results.get(2), 3)

    wheel.shutdown()
  }

  test("past-due task fires on the second tick (pad)") {
    val (wheel, _) = testWheel()
    val fired = new AtomicInteger(0)

    wheel.schedule(0L, () => fired.incrementAndGet())

    (1 to 2).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Past-due task should fire within two ticks")

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

    val threads = (0 until totalTasks).map { i =>
      Thread.startVirtualThread { () =>
        val _ = wheel.schedule(now + 10L + (i % 50), () => counter.incrementAndGet())
      }
    }
    threads.foreach(_.join())

    (1 to 64).foreach(_ => wheel.tick())

    assertEquals(counter.get(), totalTasks, s"All $totalTasks tasks should fire")

    wheel.shutdown()
  }

  test("cancel before fire prevents task from running") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val handle = wheel.schedule(clock.get() + 50L, () => fired.incrementAndGet())

    handle.cancel()

    (1 to 10).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Canceled task must not fire")

    wheel.shutdown()
  }

  test("cancel is idempotent") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val handle = wheel.schedule(clock.get() + 50L, () => fired.incrementAndGet())

    handle.cancel()
    handle.cancel()
    handle.cancel()

    (1 to 10).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Multiple cancels must not cause task to fire")

    wheel.shutdown()
  }

  test("cancel after fire is a safe no-op") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val handle = wheel.schedule(clock.get() + 20L, () => fired.incrementAndGet())

    (1 to 5).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 1, "Task should have fired")

    handle.cancel()
    assertEquals(fired.get(), 1, "Cancel after fire must not change fired count")

    wheel.shutdown()
  }

  test("cancel on a multi-round entry prevents it from firing across rotations") {
    val (wheel, clock) = testWheel()
    val fired = new AtomicInteger(0)

    val handle = wheel.schedule(clock.get() + 1500L, () => fired.incrementAndGet())

    (1 to 64).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Multi-round task not fired mid-rotation")

    handle.cancel()

    (1 to 200).foreach(_ => wheel.tick())
    assertEquals(fired.get(), 0, "Canceled multi-round task must not fire")

    wheel.shutdown()
  }

  test("concurrent cancel vs fire is safe") {
    val (wheel, clock) = testWheel()
    val n = 200
    val fired = new AtomicInteger(0)
    val now = clock.get()

    val handles = (0 until n).map { i =>
      wheel.schedule(now + 5L + (i % 20), () => fired.incrementAndGet())
    }

    val tickerDone = new AtomicInteger(0)
    val tickerThread = Thread.startVirtualThread { () =>
      (1 to 64).foreach(_ => wheel.tick())
      tickerDone.incrementAndGet()
    }
    val cancelThreads = handles.zipWithIndex.map { case (h, i) =>
      Thread.startVirtualThread { () =>
        if (i % 2 == 0) h.cancel()
      }
    }

    tickerThread.join()
    cancelThreads.foreach(_.join())

    (1 to 32).foreach(_ => wheel.tick())

    val actual = fired.get()
    val minExpected = n / 2
    val maxExpected = n
    assert(
      actual >= minExpected && actual <= maxExpected,
      s"Expected $minExpected..$maxExpected fires, got $actual"
    )
    wheel.shutdown()
  }
}
