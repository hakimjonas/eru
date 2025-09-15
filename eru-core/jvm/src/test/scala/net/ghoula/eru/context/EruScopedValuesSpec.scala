package net.ghoula.eru.context

import java.time.Instant

import net.ghoula.eru.*
import net.ghoula.eru.trace.EruTrace

/** Test suite for the EruScopedValues context propagation system.
  *
  * Validates context providers, context runners, and diagnostics across different JVM versions.
  * Ensures proper fallback behavior on JVM < 25 and optimal performance on JVM 25+.
  */
final class EruScopedValuesSpec extends munit.FunSuite {

  /** Validates that context providers can store and retrieve context correctly. */
  test("TraceContextProvider stores and retrieves context") {
    val traceId = EruTrace.TraceId.fresh()
    val context = EruTrace.TraceContext(traceId)

    val result = EruScopedValues.TraceContextProvider.runWith(context) {
      EruScopedValues.TraceContextProvider.current()
    }

    assert(result.isDefined)
    assertEquals(result.get.traceId, traceId)
  }

  /** Validates that fiber context can be stored and retrieved correctly. */
  test("FiberContextProvider stores and retrieves context") {
    val fiberId = FiberId.fresh()
    val startTime = Instant.now()
    val metadata = Map("test" -> "value")
    val context = EruScopedValues.FiberContext(fiberId, startTime, metadata)

    val result = EruScopedValues.FiberContextProvider.runWith(context) {
      EruScopedValues.FiberContextProvider.current()
    }

    assert(result.isDefined)
    assertEquals(result.get.fiberId, fiberId)
    assertEquals(result.get.metadata, metadata)
  }

  /** Validates that observer context can be stored and retrieved correctly. */
  test("ObserverContextProvider stores and retrieves context") {
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = ()
    }

    val result = EruScopedValues.ObserverContextProvider.runWith(observer) {
      EruScopedValues.ObserverContextProvider.current()
    }

    assert(result.isDefined)
    assertEquals(result.get, observer)
  }

  /** Validates that contexts are properly isolated between different scopes. */
  test("context isolation between scopes") {
    val context1 = EruTrace.TraceContext(EruTrace.TraceId.fresh())
    val context2 = EruTrace.TraceContext(EruTrace.TraceId.fresh())

    val result1 = EruScopedValues.TraceContextProvider.runWith(context1) {
      EruScopedValues.TraceContextProvider.current().get.traceId
    }

    val result2 = EruScopedValues.TraceContextProvider.runWith(context2) {
      EruScopedValues.TraceContextProvider.current().get.traceId
    }

    assertNotEquals(result1, result2)
    assertEquals(result1, context1.traceId)
    assertEquals(result2, context2.traceId)
  }

  /** Validates that nested contexts work correctly with proper scoping. */
  test("nested context scoping") {
    val outerContext = EruTrace.TraceContext(EruTrace.TraceId.fresh())
    val innerContext = EruTrace.TraceContext(EruTrace.TraceId.fresh())

    val result = EruScopedValues.TraceContextProvider.runWith(outerContext) {
      val outerResult = EruScopedValues.TraceContextProvider.current().get.traceId

      val innerResult = EruScopedValues.TraceContextProvider.runWith(innerContext) {
        EruScopedValues.TraceContextProvider.current().get.traceId
      }

      val restoredResult = EruScopedValues.TraceContextProvider.current().get.traceId

      (outerResult, innerResult, restoredResult)
    }

    assertEquals(result._1, outerContext.traceId)
    assertEquals(result._2, innerContext.traceId)
    assertEquals(result._3, outerContext.traceId)
  }

  /** Validates that context is properly restored after exceptions. */
  test("context restoration after exceptions") {
    val context = EruTrace.TraceContext(EruTrace.TraceId.fresh())

    try {
      EruScopedValues.TraceContextProvider.runWith(context) {
        throw new RuntimeException("test exception")
      }
    } catch {
      case _: RuntimeException => ()
    }

    val currentContext = EruScopedValues.TraceContextProvider.current()
    assert(currentContext.isEmpty)
  }

  /** Validates that ContextRunner propagates full context correctly. */
  test("ContextRunner propagates full context") {
    val traceContext = EruTrace.TraceContext(EruTrace.TraceId.fresh())
    val fiberContext = EruScopedValues.FiberContext(FiberId.fresh(), Instant.now())
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = ()
    }

    val effect = Eru.effect {
      val trace = EruScopedValues.TraceContextProvider.current()
      val fiber = EruScopedValues.FiberContextProvider.current()
      val obs = EruScopedValues.ObserverContextProvider.current()
      (trace.isDefined, fiber.isDefined, obs.isDefined)
    }

    val result = EruScopedValues.ContextRunner
      .runWithFullContext(traceContext, fiberContext, observer)(effect)
      .unsafeRunSync()

    assertEquals(result, (true, true, true))
  }

  /** Validates that trace-only context propagation works correctly. */
  test("ContextRunner trace-only propagation") {
    val traceContext = EruTrace.TraceContext(EruTrace.TraceId.fresh())

    val effect = Eru.effect {
      val trace = EruScopedValues.TraceContextProvider.current()
      val fiber = EruScopedValues.FiberContextProvider.current()
      (trace.isDefined, fiber.isDefined)
    }

    val result = EruScopedValues.ContextRunner
      .runWithTraceContext(traceContext)(effect)
      .unsafeRunSync()

    assertEquals(result, (true, false))
  }

  /** Validates that observer-only context propagation works correctly. */
  test("ContextRunner observer-only propagation") {
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = ()
    }

    val effect = Eru.effect {
      val trace = EruScopedValues.TraceContextProvider.current()
      val obs = EruScopedValues.ObserverContextProvider.current()
      (trace.isDefined, obs.isDefined)
    }

    val result = EruScopedValues.ContextRunner
      .runWithObserver(observer)(effect)
      .unsafeRunSync()

    assertEquals(result, (false, true))
  }

  /** Validates that diagnostics return correct information about the context system. */
  test("ContextDiagnostics provides accurate information") {
    val info = EruScopedValues.ContextDiagnostics.getContextInfo()

    assert(info.jvmVersion.nonEmpty)
    assert(info.strategy == "ThreadLocal" || info.strategy == "ScopedValues")
    assert(info.activeContexts.contains("trace"))
    assert(info.activeContexts.contains("fiber"))
    assert(info.activeContexts.contains("observer"))
  }

  /** Validates that context overhead measurement works correctly. */
  test("ContextDiagnostics measures overhead correctly") {
    val operation = "test-result"
    val (result, duration) = EruScopedValues.ContextDiagnostics.measureContextOverhead(operation)

    assertEquals(result, "test-result")
    assert(duration >= 0)
  }

  /** Validates that context providers handle concurrent access correctly. */
  test("context providers handle concurrency") {
    val context1 = EruTrace.TraceContext(EruTrace.TraceId.fresh())
    val context2 = EruTrace.TraceContext(EruTrace.TraceId.fresh())

    val future1 = java.util.concurrent.CompletableFuture.supplyAsync(() =>
      EruScopedValues.TraceContextProvider.runWith(context1) {
        Thread.sleep(10)
        EruScopedValues.TraceContextProvider.current().get.traceId
      }
    )

    val future2 = java.util.concurrent.CompletableFuture.supplyAsync(() =>
      EruScopedValues.TraceContextProvider.runWith(context2) {
        Thread.sleep(10)
        EruScopedValues.TraceContextProvider.current().get.traceId
      }
    )

    val result1 = future1.get()
    val result2 = future2.get()

    assertEquals(result1, context1.traceId)
    assertEquals(result2, context2.traceId)
    assertNotEquals(result1, result2)
  }

  /** Validates that FiberContext creation works with all parameters. */
  test("FiberContext creation and field access") {
    val fiberId = FiberId.fresh()
    val startTime = Instant.now()
    val metadata = Map("key1" -> "value1", "key2" -> "value2")

    val context = EruScopedValues.FiberContext(fiberId, startTime, metadata)

    assertEquals(context.fiberId, fiberId)
    assertEquals(context.startTime, startTime)
    assertEquals(context.metadata, metadata)
  }

  /** Validates that FiberContext can be created with default metadata. */
  test("FiberContext creation with default metadata") {
    val fiberId = FiberId.fresh()
    val startTime = Instant.now()

    val context = EruScopedValues.FiberContext(fiberId, startTime)

    assertEquals(context.fiberId, fiberId)
    assertEquals(context.startTime, startTime)
    assert(context.metadata.isEmpty)
  }

  /** Validates that multiple context types can be used simultaneously. */
  test("multiple context types work together") {
    val traceContext = EruTrace.TraceContext(EruTrace.TraceId.fresh())
    val fiberContext = EruScopedValues.FiberContext(FiberId.fresh(), Instant.now())
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = ()
    }

    val result = EruScopedValues.TraceContextProvider.runWith(traceContext) {
      EruScopedValues.FiberContextProvider.runWith(fiberContext) {
        EruScopedValues.ObserverContextProvider.runWith(observer) {
          val trace = EruScopedValues.TraceContextProvider.current()
          val fiber = EruScopedValues.FiberContextProvider.current()
          val obs = EruScopedValues.ObserverContextProvider.current()
          (trace.isDefined, fiber.isDefined, obs.isDefined)
        }
      }
    }

    assertEquals(result, (true, true, true))
  }

  /** Validates that context providers work correctly when no context is set. */
  test("context providers return None when no context is set") {
    val traceResult = EruScopedValues.TraceContextProvider.current()
    val fiberResult = EruScopedValues.FiberContextProvider.current()
    val observerResult = EruScopedValues.ObserverContextProvider.current()

    assert(traceResult.isEmpty)
    assert(fiberResult.isEmpty)
    assert(observerResult.isEmpty)
  }
}
