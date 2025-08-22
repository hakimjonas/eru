package net.ghoula.eru

object prelude {
  // 1. Re-export all of the core functionality
  export net.ghoula.eru.CorePrelude.*

  // 2. Add the runtime-specific extensions
  export net.ghoula.eru.RuntimeExtensions.*
  
  // 3. Export runtime types for external consumers
  type Ref[A] = net.ghoula.eru.Ref[A]
  type Deferred[A] = net.ghoula.eru.Deferred[A]
  type Semaphore = net.ghoula.eru.Semaphore
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]
}