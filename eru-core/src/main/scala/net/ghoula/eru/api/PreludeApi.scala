package net.ghoula.eru.api

import net.ghoula.eru.*

/** Public facade for all extension methods that are part of the Eru core public API.
  *
  * This object re-exports the ergonomics layer (extension methods) while preserving a strict
  * separation from internal implementation packages. Users should never need to refer to any
  * `internal` packages — import the unified prelude and these extensions will be available.
  *
  * Typical usage: import net.ghoula.eru.prelude.*
  *
  * Extension families surfaced through this facade include:
  *   - Resource safety: ensure, bracket
  *   - Error handling: attempt, recover, mapError
  *   - Diagnostics: debug, trace integration
  *   - Core combinators: map/flatMap helpers, validation utilities
  *
  * @example
  *   {{{ import net.ghoula.eru.prelude.*
  *
  * // Resource discipline val program: Eru[Nothing, String] =
  * Eru.succeed("res").ensure(Eru.effect(())).bracket(_ => Eru.effect(())) { r =>
  * Eru.succeed(s"Using $r") }
  *
  * // Error handling and diagnostics val handled: Eru[String, Int] = Eru.fail("boom").recover {
  * case "boom" => 1 }.debug("after-recover")
  *
  * val value: Int = handled.map(_ + 1).unsafeRunSync() }}
  *
  * @see
  *   net.ghoula.eru.CorePrelude
  */
object PreludeApi {
  export net.ghoula.eru.internal.extensions.*
}
