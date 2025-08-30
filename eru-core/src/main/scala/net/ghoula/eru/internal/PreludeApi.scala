package net.ghoula.eru.internal

import net.ghoula.eru.*

/** Unified facade for extension methods that are part of the public API surface.
  *
  * Delegates to the canonical internal.extensions object so the CorePrelude can remain stable even
  * if the internal organization changes.
  */
object PreludeApi {
  // Re-export all existing extensions (resource safety, error handling, etc.)
  export net.ghoula.eru.internal.extensions.*
}
