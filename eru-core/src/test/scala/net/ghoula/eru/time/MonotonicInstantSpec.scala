package net.ghoula.eru.time

import java.time.Duration

/** Pure arithmetic on `MonotonicInstant`. No `Eru` effects, no capability implementations, no
  * fibers. If this hangs, the opaque type itself is broken (not possible) — or the test runner is.
  */
class MonotonicInstantSpec extends munit.FunSuite {

  test("ofNanos round-trips via toNanos") {
    assertEquals(MonotonicInstant.ofNanos(0L).toNanos, 0L)
    assertEquals(MonotonicInstant.ofNanos(123L).toNanos, 123L)
    assertEquals(MonotonicInstant.ofNanos(Long.MaxValue).toNanos, Long.MaxValue)
  }

  test("until is inverse of +") {
    val t0 = MonotonicInstant.ofNanos(0L)
    val t1 = t0 + Duration.ofNanos(500L)
    assertEquals(t0.until(t1).toNanos, 500L)
  }

  test("until returns negative Duration when target precedes source") {
    val t0 = MonotonicInstant.ofNanos(1000L)
    val t1 = MonotonicInstant.ofNanos(400L)
    assertEquals(t0.until(t1).toNanos, -600L)
  }

  test("addition is associative with Duration") {
    val t = MonotonicInstant.ofNanos(100L)
    val d1 = Duration.ofNanos(50L)
    val d2 = Duration.ofNanos(25L)
    assertEquals((t + d1 + d2).toNanos, (t + d1.plus(d2)).toNanos)
  }

  test("CanEqual distinguishes distinct readings") {
    val a = MonotonicInstant.ofNanos(10L)
    val b = MonotonicInstant.ofNanos(20L)
    assert(a != b)
  }

  test("Ordering agrees with nanosecond comparison") {
    val a = MonotonicInstant.ofNanos(10L)
    val b = MonotonicInstant.ofNanos(20L)
    val ord = summon[Ordering[MonotonicInstant]]
    assert(ord.compare(a, b) < 0)
    assert(ord.compare(b, a) > 0)
    assert(ord.compare(a, a) == 0)
  }
}
