package net.ghoula.eru.time

import net.ghoula.eru.Eru

/** Capability granting access to wall-clock time.
  *
  * Wall time is the right reading for *addressing absolute moments in civil time*: scheduling an
  * event at a specific instant, producing timestamps for logs/events/serialization, interoperating
  * with calendar-aware systems. It is subject to NTP corrections, DST transitions, and manual
  * adjustments.
  *
  * An effect that requires `using Wall` cannot be constructed in a scope that lacks a `Wall` given.
  * This matches the `Monotonic` pattern: wall-clock reads are visible at the type level, distinct
  * from monotonic reads at the call site.
  */
trait Wall {

  /** Read the current wall-clock value as a `java.time.Instant`.
    *
    * The stdlib type is the right carrier here because wall-time interoperates with calendar
    * libraries, formatters, and external protocols. No wrapping.
    */
  def wallNow: Eru[Nothing, java.time.Instant]

  /** Schedule `effect` to run when the wall clock observes `instant`.
    *
    * Returns immediately (fire-and-forget). The implementation MAY fire late, early, or re-fire if
    * the wall clock is warped during the wait.
    */
  def at[E, A](instant: java.time.Instant)(effect: => Eru[E, A]): Eru[Nothing, Unit]
}
