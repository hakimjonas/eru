package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.trace.EruTrace

/** Correctness invariant: EruTrace context propagates across fork, matching the discipline already
  * established for StructuredConcurrency.currentScope and TimerService.currentTimer.
  *
  * Pins Finding 1 from the API Discipline Audit. Pre-fix, `EruTrace.currentContext` was a
  * ThreadLocal that no fork / race site captured-and-re-set on the spawned VT. A user calling
  * `.traced("...")` and forking inside the traced region would silently lose the trace lineage on
  * the child fiber, which would then call `startTrace("root-trace")` and begin a fresh unrelated
  * TraceId on its first `getCurrentContext` miss.
  *
  * Post-fix: the 4 fork/race sites in RuntimeBackend plus handleSuspend's supplyAsync VT all
  * capture `EruTrace.getCurrentContext` on the parent thread and `setCurrentContext` on the spawned
  * VT.
  */
final class TraceForkPropagationSpec extends EruTestSuite {

  test("fork inside a traced region inherits the parent's trace context") {
    val parentCtx = EruTrace.startTrace("parent-op")
    EruTrace.setCurrentContext(Some(parentCtx))

    try {
      val childCtxOpt: Option[EruTrace.TraceContext] = {
        val fiberEff = Eru.effectTotal(EruTrace.getCurrentContext).fork
        val fiber = fiberEff.unsafeRunSync()
        fiber.await.unsafeRunSync() match {
          case Exit.Success(ctx) => ctx
          case other => fail(s"Expected Success, got $other")
        }
      }

      childCtxOpt match {
        case Some(childCtx) =>
          assertEquals(
            childCtx.traceId.toLong,
            parentCtx.traceId.toLong,
            "Child fiber must inherit parent's TraceId — propagation is the correctness invariant"
          )
        case None =>
          fail(
            "Child fiber observed no trace context despite parent having one set — " +
              "this is the pre-fix failure mode where ThreadLocal was not propagated across fork"
          )
      }
    } finally {
      EruTrace.setCurrentContext(None)
    }
  }

  test("race sibling fibers inherit the parent's trace context") {
    val parentCtx = EruTrace.startTrace("race-parent-op")
    EruTrace.setCurrentContext(Some(parentCtx))

    try {
      val readCtx: Eru[Nothing, Option[EruTrace.TraceContext]] =
        Eru.effectTotal(EruTrace.getCurrentContext)
      val result = readCtx.race(readCtx).unsafeRunSync()
      val observed = result match {
        case Left(ctx) => ctx
        case Right(ctx) => ctx
      }

      observed match {
        case Some(ctx) =>
          assertEquals(
            ctx.traceId.toLong,
            parentCtx.traceId.toLong,
            "Race sibling must inherit parent's TraceId"
          )
        case None =>
          fail("Race sibling observed no trace context despite parent having one set")
      }
    } finally {
      EruTrace.setCurrentContext(None)
    }
  }
}
