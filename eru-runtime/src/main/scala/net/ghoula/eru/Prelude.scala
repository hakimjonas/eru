package net.ghoula.eru

/** Unified public prelude for Eru (Scala 3).
  *
  * Import this in user code and tests:
  *   import net.ghoula.eru.prelude.*
  *
  * This object re-exports the full core API. Runtime-specific additions can be
  * incrementally exported here as they are introduced.
  */
object prelude {
  export net.ghoula.eru.CorePrelude.*
}
