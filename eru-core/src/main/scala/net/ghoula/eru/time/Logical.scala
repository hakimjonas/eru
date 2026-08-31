package net.ghoula.eru.time

import net.ghoula.eru.Eru

/** Capability combining `Monotonic` and `Wall` under caller control.
  *
  * A `Logical` satisfies both `using Monotonic` and `using Wall` requirements via subtyping: a
  * single `given Logical` supplied in test scope resolves every time dependency a program may have.
  * Test code neither wires two separate givens nor swaps a runtime backend; the substitution is a
  * type-level witness.
  *
  * Production code does NOT supply a `Logical`. The default `Monotonic` given is provided by the
  * companion; a `Wall` must be supplied explicitly by the caller where wall-clock access is needed.
  * `Logical` is a test-only (or explicit-simulation) shape.
  *
  * Operations beyond reading the clocks:
  *   - `advance(d)` — move monotonic and wall forward by `d` together.
  *   - `setWall(instant)` — jump wall to `instant` without affecting monotonic.
  *   - `warp(offset)` — apply a wall-only offset (simulates NTP correction / manual skew).
  */
trait Logical extends Monotonic with Wall {

  /** Move both clocks forward by `duration`. */
  def advance(duration: java.time.Duration): Eru[Nothing, Unit]

  /** Set the wall clock to `instant`. Does not affect the monotonic clock. */
  def setWall(instant: java.time.Instant): Eru[Nothing, Unit]

  /** Apply an offset to the wall clock without moving monotonic. Models NTP corrections or manual
    * wall-clock skew; monotonic remains strictly non-decreasing across the warp.
    */
  def warp(offset: java.time.Duration): Eru[Nothing, Unit]
}
