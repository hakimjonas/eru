package net.ghoula.eru.api

import net.ghoula.eru.*

/** Public facade that re-exports extension methods for the Eru core API.
  *
  * Typical usage: {{@ code import net.ghoula.eru.prelude.* @}}
  *
  * Extension families:
  *   - Resource safety: ensure, bracket
  *   - Error handling: attempt, recover, mapError
  *   - Diagnostics: debug
  *   - Core combinators and utilities
  *
  * @see
  *   net.ghoula.eru.CorePrelude
  */
object PreludeApi {
  export net.ghoula.eru.internal.extensions.*
}
