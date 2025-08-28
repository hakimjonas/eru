package net.ghoula.eru

/** Unified public prelude for Eru.
  *
  * Usage: import net.ghoula.eru.prelude.*
  *
  * This prelude re-exports the complete public surface of eru-core and the runtime extensions so
  * users get a single, canonical import with no exposure of internal packages.
  */
object prelude {
  export net.ghoula.eru.CorePrelude.*
  export net.ghoula.eru.RuntimeExtensions.*

  /** Exposes the EruObserver companion via the unified prelude so that observer helpers (e.g.,
    * noop, console) and event types are available from the same canonical import.
    */
  val EruObserver = net.ghoula.eru.EruObserver

  /** Type alias for the runtime Ref, re-exposed for discoverability.
    * @tparam A
    *   element type stored in the reference
    */
  type Ref[A] = net.ghoula.eru.Ref[A]

  /** Type alias for the runtime Deferred, re-exposed for discoverability.
    * @tparam A
    *   value type produced when completed
    */
  type Deferred[A] = net.ghoula.eru.Deferred[A]

  /** Type alias for the runtime Semaphore, re-exposed for discoverability. */
  type Semaphore = net.ghoula.eru.Semaphore

  /** Type alias for runtime fibers, re-exposed for discoverability.
    * @tparam E
    *   typed error of the fiber
    * @tparam A
    *   success type of the fiber
    */
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]
}
