package net.ghoula.eru.time

/** A monotonic clock reading, opaque over `Long` nanoseconds.
  *
  * `MonotonicInstant` is the type-distinct carrier of a monotonic clock value. It cannot be
  * confused with a `java.time.Instant` (wall clock) at the call site: the types are distinct and no
  * implicit conversion is provided.
  *
  * The underlying representation is `Long` nanoseconds since an unspecified origin. The origin is
  * not stable across JVM processes; `MonotonicInstant` values are only comparable within the same
  * process. Serializing or transporting a `MonotonicInstant` across a process boundary is not
  * meaningful.
  *
  * Arithmetic:
  *   - `m.until(n)` yields the `Duration` from `m` to `n` (negative if `n` precedes `m`).
  *   - `m + d` and `m - d` shift by a `Duration`.
  *
  * Runtime cost:
  *   - Opaque `Long`; no boxing at runtime. `ofNanos` and `toNanos` are `inline`.
  */
opaque type MonotonicInstant = Long

object MonotonicInstant {

  /** Wrap a `Long` nanosecond reading as a `MonotonicInstant`. Intended for capability
    * implementations that source their reading from a platform clock (e.g., `System.nanoTime()`).
    */
  inline def ofNanos(nanos: Long): MonotonicInstant = nanos

  extension (m: MonotonicInstant) {

    /** Unwrap the underlying nanosecond reading. */
    inline def toNanos: Long = m

    /** Duration from `m` to `other`. Negative when `other` precedes `m`. */
    def until(other: MonotonicInstant): java.time.Duration =
      java.time.Duration.ofNanos(other - m)

    /** Shift forward by `d`. */
    def +(d: java.time.Duration): MonotonicInstant =
      m + d.toNanos

    /** Shift backward by `d`. */
    def -(d: java.time.Duration): MonotonicInstant =
      m - d.toNanos
  }

  given CanEqual[MonotonicInstant, MonotonicInstant] = CanEqual.derived
  given Ordering[MonotonicInstant] = Ordering.Long
}
