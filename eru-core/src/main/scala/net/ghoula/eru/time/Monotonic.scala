package net.ghoula.eru.time

import net.ghoula.eru.Eru

/** Capability granting access to monotonic time.
  *
  * Monotonic time is the right reading for *measuring elapsed time*: durations between two
  * observations, timeouts, retry backoff, latency samples. It is unaffected by wall-clock
  * adjustments (NTP corrections, DST transitions, manual resets, VM migration between hosts with
  * skewed clocks).
  *
  * An effect that requires `using Monotonic` cannot be constructed in a scope that lacks a
  * `Monotonic` given. This is the Pillar I (type-directed guarantees) expression of time
  * dependency: every monotonic clock read is visible at the type level.
  *
  * The capability trait exposes the two primitive operations — reading the monotonic clock and
  * sleeping for a monotonic duration. `EruRuntime.sleep` (runtime module) builds on these;
  * `Eru.at`/`Eru.after` (core) schedule on wall-clock epoch millis and do not use this capability.
  */
trait Monotonic {

  /** Read the current monotonic clock value as a `MonotonicInstant`. */
  def monotonicNow: Eru[Nothing, MonotonicInstant]

  /** Suspend for at least `duration` on the monotonic clock.
    *
    * MUST NOT return before `duration` has elapsed on this capability's clock source. No
    * early-firing, regardless of wall-clock adjustments during the sleep. This is the at-least
    * duration guarantee made a law of the capability.
    */
  def sleep(duration: java.time.Duration): Eru[Nothing, Unit]
}

object Monotonic {

  /** Default `Monotonic` instance, in `Monotonic`'s implicit scope.
    *
    * Lives in the trait's companion so any reference to `Monotonic` (`using Monotonic`,
    * `summon[Monotonic]`, `import` chain) resolves it without the caller needing `import given`
    * ceremony. Consumers may shadow this in test scope by supplying a higher-priority given (e.g.,
    * a `Logical` instance from a `TestClock`).
    *
    * Backed by `System.nanoTime()` with interruptible-blocking sleep.
    */
  given default: Monotonic = SystemMonotonic
}
