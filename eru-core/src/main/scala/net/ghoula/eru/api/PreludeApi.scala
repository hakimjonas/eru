package net.ghoula.eru.api

import net.ghoula.eru.*

/** Public facade that re-exports extension methods for the Eru core API.
  *
  * Typical usage: `import net.ghoula.eru.prelude.*`
  *
  * Extension families:
  *   - Result combinators: map, flatMap, fold, isSuccess, isFailure, toEru, toExit
  *   - Resource safety: ensureAll, autoCleanup, autoClose, useScoped, pooled, validateResource
  *   - Error handling: fallback, contextualizeError, accumulateErrors, validate
  *   - Observability: traced, traceEvent, withTraceBaggage
  *
  * @see
  *   net.ghoula.eru.CorePrelude
  */
object PreludeApi {
  export net.ghoula.eru.internal.Extensions.*
}
