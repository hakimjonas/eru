package net.ghoula.eru.api

import net.ghoula.eru.*

/** Public facade for all extension methods that are part of the Eru core public API.
  *
  * This object re-exports the ergonomics layer (extension methods) while preserving a strict
  * separation from internal implementation packages. Users should never need to refer to any
  * `internal` packages—import the prelude and these extensions will be available.
  *
  * Typical usage: import net.ghoula.eru.CorePrelude.*
  *
  * @see
  *   net.ghoula.eru.CorePrelude
  */
object PreludeApi {
  export net.ghoula.eru.internal.extensions.*
}
