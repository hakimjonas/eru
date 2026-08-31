package net.ghoula.eru.trace

import net.ghoula.eru.*
import net.ghoula.eru.CorePrelude.*
import net.ghoula.eru.trace.EruTrace.{TraceContext, getCurrentContext}

/** Trace-context isolation.
  *
  * The trace context is thread-local ambient state: after a `traced`/`withTraceBaggage` effect
  * completes, the context that was current before the effect must be restored. The previous
  * implementation never restored it, so a span's context leaked into every subsequent effect on the
  * same thread — stale parent attribution and cross-request baggage bleed.
  */
class TraceContextIsolationSpec extends munit.FunSuite {

  private def currentIs(prior: Option[TraceContext]): Unit =
    assertEquals(getCurrentContext, prior, "trace context leaked past the effect boundary")

  test("traced restores the prior context after success") {
    val prior = getCurrentContext
    val _ = Eru.succeed(1).traced("op").unsafeRunSync()
    currentIs(prior)
  }

  test("traced restores the prior context after a typed failure") {
    val prior = getCurrentContext
    val _ = Eru.fail("boom").traced("op").attempt.unsafeRunSync()
    currentIs(prior)
  }

  test("nested traced scopes restore in LIFO order") {
    val prior = getCurrentContext
    val _ = Eru.succeed(1).traced("outer").traced("inner").unsafeRunSync()
    currentIs(prior)
  }

  test("withTraceBaggage restores the prior context after completion") {
    val prior = getCurrentContext
    val _ = Eru.succeed(1).withTraceBaggage("user", "42").unsafeRunSync()
    currentIs(prior)
  }

  test("the inner effect observes the trace context during execution") {
    val prior = getCurrentContext
    var observed: Option[TraceContext] = None
    val _ = Eru.effect {
      observed = getCurrentContext
      42
    }
      .traced("op")
      .unsafeRunSync()
    assert(observed != prior, "the traced effect must run inside the span context")
    currentIs(prior)
  }
}
