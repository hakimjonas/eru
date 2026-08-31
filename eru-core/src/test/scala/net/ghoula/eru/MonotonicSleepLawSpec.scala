package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.time.Monotonic

/** The Monotonic capability law: sleep MUST NOT return before the requested duration.
  *
  * The old implementation truncated durations to whole milliseconds via `toMillis`, so a 1.5ms
  * sleep returned after ~1ms and a sub-millisecond sleep returned immediately. These tests pin the
  * at-least guarantee, with a 200us tolerance that absorbs only measurement noise.
  */
class MonotonicSleepLawSpec extends munit.FunSuite {

  private def elapsedOf(durationNanos: Long): Long = {
    val t0 = System.nanoTime()
    Monotonic.default.sleep(Duration.ofNanos(durationNanos)).unsafeRunSync()
    System.nanoTime() - t0
  }

  test("sleep honors the at-least law for durations with a sub-millisecond remainder") {
    val elapsed = elapsedOf(1_500_000L)
    assert(
      elapsed >= 1_500_000L - 200_000L,
      s"Sleep returned ${elapsed}ns after request, before the 1.5ms duration elapsed"
    )
  }

  test("sleep honors the at-least law for sub-millisecond durations") {
    val elapsed = elapsedOf(500_000L)
    assert(
      elapsed >= 500_000L - 200_000L,
      s"Sleep returned ${elapsed}ns after request, before the 0.5ms duration elapsed"
    )
  }

  test("sleep of zero duration completes without blocking") {
    val elapsed = elapsedOf(0L)
    assert(elapsed < 50_000_000L, s"Zero-duration sleep took ${elapsed / 1e6}ms")
  }
}
