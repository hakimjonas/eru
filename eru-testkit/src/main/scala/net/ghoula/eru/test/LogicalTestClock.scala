package net.ghoula.eru.test

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.Eru
import net.ghoula.eru.time.{Logical, MonotonicInstant}

/** A `Logical` capability backed by an underlying [[TestClock]].
  *
  * Tests supply `given Logical = LogicalTestClock(clock)` to substitute logical time at the type
  * level: the companion-resident `given Monotonic` is shadowed by subtyping. Production code is
  * unaffected; only tests that want deterministic time pay the wiring cost.
  *
  * Semantic discipline:
  *
  *   - `monotonicNow` is sourced from a dedicated counter, NOT from the wrapped TestClock's wall
  *     reading. This preserves the Logical contract that `warp(offset)` moves wall WITHOUT moving
  *     monotonic.
  *   - `wallNow` reads the underlying TestClock's `currentTime`.
  *   - `advance(d)` moves both: bumps the monotonic counter by `d.toNanos` AND advances the
  *     TestClock by `d`. The TestClock's `advance` triggers any pending operations scheduled
  *     against wall time, which is the existing semantic.
  *   - `setWall(instant)` calls TestClock.setTime, leaving the monotonic counter untouched.
  *   - `warp(offset)` advances the TestClock by `offset` without touching monotonic.
  *   - `sleep(duration)` is interpreted as monotonic time progression — it's `advance(duration)`.
  *     Test code that wants to test wall-clock-driven scheduling separately should use `setWall` /
  *     `warp` explicitly.
  *
  * `LogicalTestClock` does NOT replace TestClock or TestClockBackend. Existing TestClock-driven
  * tests keep working unchanged. The `Logical` adapter is additive: opt-in for tests that want the
  * type-level capability shape.
  */
final class LogicalTestClock private (clock: TestClock) extends Logical {
  private val monoNanos = new AtomicLong(0L)

  def monotonicNow: Eru[Nothing, MonotonicInstant] =
    Eru.effectTotal(MonotonicInstant.ofNanos(monoNanos.get()))

  def wallNow: Eru[Nothing, Instant] =
    Eru.effectTotal(clock.currentTime)

  def sleep(duration: Duration): Eru[Nothing, Unit] =
    advance(duration)

  def at[E, A](instant: Instant)(effect: => Eru[E, A]): Eru[Nothing, Unit] =
    Eru.effectTotal {
      clock.schedule(instant, () => { val _ = effect.attempt.unsafeRunSync(); () })
      ()
    }

  def advance(duration: Duration): Eru[Nothing, Unit] =
    Eru.effectTotal {
      val _ = monoNanos.addAndGet(duration.toNanos)
      val _ = clock.advance(duration)
    }

  def setWall(instant: Instant): Eru[Nothing, Unit] =
    Eru.effectTotal {
      val _ = clock.setTime(instant)
    }

  def warp(offset: Duration): Eru[Nothing, Unit] =
    Eru.effectTotal {
      val _ = clock.advance(offset)
    }

  /** Test-only accessor for the underlying TestClock, useful when tests need to reach the
    * scheduling primitives (`pendingCount`, `nextScheduled`) that the `Logical` capability doesn't
    * expose.
    */
  def underlying: TestClock = clock
}

object LogicalTestClock {

  /** Wrap an existing `TestClock` as a `Logical`. The TestClock's wall-time state and the adapter's
    * monotonic counter start independently; `advance` keeps them in lockstep thereafter, while
    * `warp` and `setWall` move only wall.
    */
  def apply(clock: TestClock): LogicalTestClock = new LogicalTestClock(clock)

  /** Convenience factory: create a fresh TestClock at the given start instant and wrap it. */
  def create(startWall: Instant): LogicalTestClock =
    new LogicalTestClock(TestClock.create(startWall))

  /** Convenience factory: create a fresh TestClock at the current system time and wrap it. */
  def create(): LogicalTestClock =
    new LogicalTestClock(TestClock.create())
}
