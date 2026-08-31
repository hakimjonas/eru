package net.ghoula.eru.time

import java.time.Duration

import net.ghoula.eru.Eru

/** Production `Monotonic` capability backed by `System.nanoTime()`.
  *
  * Stateless, JVM-only. `System.nanoTime` maps to the platform's monotonic clock
  * (`clock_gettime(CLOCK_MONOTONIC)` on Linux).
  *
  * `monotonicNow` returns a `MonotonicInstant` carrying the raw nanoTime reading. The stdlib
  * guarantees nanoTime is non-decreasing across successive calls on the same JVM; successive calls
  * within the same nanosecond return the same value, so readings are non-decreasing but not
  * strictly increasing.
  *
  * `sleep` delegates to `Eru.interruptibleBlocking` over `Thread.sleep` with a nanosecond
  * supplement, then spins against `nanoTime` to absorb timer-granularity undersleep, so the
  * at-least-duration law of the `Monotonic` capability holds to clock resolution. The runtime
  * module's backend routes `runtime.sleep` through the hashed timer wheel instead.
  */
private[eru] object SystemMonotonic extends Monotonic {

  def monotonicNow: Eru[Nothing, MonotonicInstant] =
    Eru.effectTotal(MonotonicInstant.ofNanos(System.nanoTime()))

  /** Block the calling thread for at least `totalNanos` (already clamped to >= 0).
    *
    * Sleeps the bulk via `Thread.sleep(ms, nanos)`, then spins on the monotonic clock until the
    * deadline to absorb any undersleep from OS timer granularity (bounded by ~1ms). Interruption
    * during the sleep throws; interruption during the spin is detected, cleared, and rethrown as
    * `InterruptedException` so `interruptibleBlocking` converts it to fiber interruption.
    */
  private[eru] def sleepAtLeast(totalNanos: Long): Unit = {
    val start = System.nanoTime()
    val deadline =
      if (start > 0L && totalNanos > Long.MaxValue - start) Long.MaxValue
      else start + totalNanos
    val ms = totalNanos / 1_000_000L
    val remainder = (totalNanos % 1_000_000L).toInt
    if (ms > 0L) Thread.sleep(ms, remainder)
    while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
      Thread.onSpinWait()
    }
    if (Thread.interrupted()) {
      throw new InterruptedException("sleep interrupted")
    }
  }

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      sleepAtLeast(math.max(0L, duration.toNanos))
    }
}
