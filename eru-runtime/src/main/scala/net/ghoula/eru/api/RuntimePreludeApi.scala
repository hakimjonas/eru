package net.ghoula.eru.api

/** Public facade for runtime-level ergonomic additions and runner conveniences.
  *
  * This object re-exports runtime helper methods in a stable public namespace to ensure
  * no `internal` packages are exposed through the public API.
  */
object RuntimePreludeApi {
  export net.ghoula.eru.internal.extensions_runner.*
}
