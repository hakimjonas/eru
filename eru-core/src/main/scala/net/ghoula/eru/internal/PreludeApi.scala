package net.ghoula.eru.internal

import net.ghoula.eru.*

/** Unified facade for extension methods that are part of the public API surface.
  *
  * Delegates to the canonical internal.extensions object so the CorePrelude can remain stable even
  * if the internal organization changes. Re-exports all existing extensions including resource
  * safety, error handling, and other core functionality.
  */
object PreludeApi {
  export net.ghoula.eru.internal.extensions.*
}
