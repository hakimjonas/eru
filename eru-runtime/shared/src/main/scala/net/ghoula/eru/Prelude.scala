package net.ghoula.eru

/** Unified public prelude for Eru.
  *
  * Usage: import net.ghoula.eru.prelude.*
  */
object prelude {
  export net.ghoula.eru.CorePrelude.*
  export net.ghoula.eru.RuntimeExtensions.*

  // Bring the EruObserver object name into the public surface
  val EruObserver = net.ghoula.eru.EruObserver

  type Ref[A] = net.ghoula.eru.Ref[A]
  type Deferred[A] = net.ghoula.eru.Deferred[A]
  type Semaphore = net.ghoula.eru.Semaphore
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]
}
