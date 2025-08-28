package net.ghoula.eru

/** Unified public prelude for Eru.
  *
  * Import this in user code and tests:
  *   import net.ghoula.eru.prelude.*
  *
  * This prelude is the single, comprehensive entry point for end users. It is a strict superset
  * of the core prelude, and also exports all runtime-specific conveniences and data types that are
  * part of the public API. No `internal` package types or names leak through this surface.
  */
object prelude {
  export net.ghoula.eru.CorePrelude.*
  export net.ghoula.eru.RuntimeExtensions.*
  type Ref[A] = net.ghoula.eru.Ref[A]
  type Deferred[A] = net.ghoula.eru.Deferred[A]
  type Semaphore = net.ghoula.eru.Semaphore
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]
}
