package net.ghoula.eru

object prelude {
  // 1. Re-export all of the core functionality
  export net.ghoula.eru.CorePrelude.*

  // 2. Add the runtime-specific extensions
  export net.ghoula.eru.RuntimeExtensions.*
}