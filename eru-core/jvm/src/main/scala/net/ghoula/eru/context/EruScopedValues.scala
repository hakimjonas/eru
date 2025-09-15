package net.ghoula.eru.context

import net.ghoula.eru.*
import net.ghoula.eru.trace.EruTrace

/** JVM 25 Scoped Values integration for Eru context propagation.
  *
  * This module provides a preparation layer for migrating from ThreadLocal-based context
  * propagation to JVM 25's native Scoped Values. Scoped Values offer:
  *
  *   - Lower memory overhead than ThreadLocal
  *   - Native integration with Virtual Threads
  *   - Automatic cleanup when scope exits
  *   - Better performance characteristics
  *
  * Implementation Strategy:
  *   - Maintains backward compatibility with existing ThreadLocal usage
  *   - Provides feature flag to enable Scoped Values when available
  *   - Fallback mechanism for JVM versions < 25
  *   - Zero type casting, adhering to Eru's safety manifesto
  */
object EruScopedValues {

  /** Detects if Scoped Values are available (JVM 25+). */
  private val enableScopedValues: Boolean = {
    try {
      val javaVersion = System.getProperty("java.version")
      val majorVersion = javaVersion.split("\\.")(0).toInt
      majorVersion >= 25
    } catch {
      case _: Exception => false
    }
  }

  /** Context container for fiber-related metadata.
    *
    * @param fiberId
    *   the unique identifier for this fiber
    * @param startTime
    *   when this fiber started execution
    * @param metadata
    *   additional key-value metadata for this fiber
    */
  case class FiberContext(
    fiberId: FiberId,
    startTime: java.time.Instant,
    metadata: Map[String, String] = Map.empty
  )

  /** Abstract interface for context propagation.
    *
    * Works with both ThreadLocal and Scoped Values transparently, providing a unified API for
    * context management regardless of the underlying JVM version.
    *
    * @tparam A
    *   the type of context being propagated
    */
  trait ContextProvider[A] {

    /** Get the current context value.
      *
      * @return
      *   the current context if one is active
      */
    def current(): Option[A]

    /** Run an operation with the given context value.
      *
      * @param value
      *   the context value to use
      * @param action
      *   the operation to run within this context
      * @tparam B
      *   the return type of the operation
      * @return
      *   the result of the operation
      */
    def runWith[B](value: A)(action: => B): B
  }

  /** Trace context provider using optimal strategy based on JVM version.
    *
    * On JVM 25+, uses Scoped Values for better performance and automatic cleanup. On older JVMs,
    * falls back to ThreadLocal for compatibility.
    */
  object TraceContextProvider extends ContextProvider[EruTrace.TraceContext] {

    /** ThreadLocal fallback for JVM < 25. */
    private val threadLocalContext: ThreadLocal[Option[EruTrace.TraceContext]] =
      ThreadLocal.withInitial(() => None)

    /** ScopedValue for JVM 25+. */
    private lazy val scopedValueContext: Option[java.lang.ScopedValue[EruTrace.TraceContext]] = {
      if (enableScopedValues) {
        Some(java.lang.ScopedValue.newInstance())
      } else {
        None
      }
    }

    def current(): Option[EruTrace.TraceContext] = {
      if (enableScopedValues) {
        scopedValueContext.flatMap { sv =>
          try {
            Option(sv.get())
          } catch {
            case _: java.util.NoSuchElementException => None
          }
        }
      } else {
        threadLocalContext.get()
      }
    }

    def runWith[B](context: EruTrace.TraceContext)(action: => B): B = {
      if (enableScopedValues) {
        scopedValueContext match {
          case Some(sv) => java.lang.ScopedValue.where(sv, context).call(() => action)
          case None => action // Fallback for JVM < 25
        }
      } else {
        val previousContext = threadLocalContext.get()
        threadLocalContext.set(Some(context))
        try {
          action
        } finally {
          threadLocalContext.set(previousContext)
        }
      }
    }
  }

  /** Fiber context provider using optimal strategy based on JVM version.
    *
    * Manages fiber-specific context information including fiber IDs, start times, and metadata.
    */
  object FiberContextProvider extends ContextProvider[FiberContext] {

    /** ThreadLocal fallback for JVM < 25. */
    private val threadLocalContext: ThreadLocal[Option[FiberContext]] =
      ThreadLocal.withInitial(() => None)

    /** ScopedValue for JVM 25+. */
    private lazy val scopedValueContext: Option[java.lang.ScopedValue[FiberContext]] = {
      if (enableScopedValues) {
        Some(java.lang.ScopedValue.newInstance())
      } else {
        None
      }
    }

    def current(): Option[FiberContext] = {
      if (enableScopedValues) {
        scopedValueContext.flatMap { sv =>
          try {
            Option(sv.get())
          } catch {
            case _: java.util.NoSuchElementException => None
          }
        }
      } else {
        threadLocalContext.get()
      }
    }

    def runWith[B](context: FiberContext)(action: => B): B = {
      if (enableScopedValues) {
        scopedValueContext match {
          case Some(sv) => java.lang.ScopedValue.where(sv, context).call(() => action)
          case None => action // Fallback for JVM < 25
        }
      } else {
        val previousContext = threadLocalContext.get()
        threadLocalContext.set(Some(context))
        try {
          action
        } finally {
          threadLocalContext.set(previousContext)
        }
      }
    }
  }

  /** Observer context provider using optimal strategy based on JVM version.
    *
    * Manages observer instances for event tracking and monitoring.
    */
  object ObserverContextProvider extends ContextProvider[EruObserver] {

    /** ThreadLocal fallback for JVM < 25. */
    private val threadLocalContext: ThreadLocal[Option[EruObserver]] =
      ThreadLocal.withInitial(() => None)

    /** ScopedValue for JVM 25+. */
    private lazy val scopedValueContext: Option[java.lang.ScopedValue[EruObserver]] = {
      if (enableScopedValues) {
        Some(java.lang.ScopedValue.newInstance())
      } else {
        None
      }
    }

    def current(): Option[EruObserver] = {
      if (enableScopedValues) {
        scopedValueContext.flatMap { sv =>
          try {
            Option(sv.get())
          } catch {
            case _: java.util.NoSuchElementException => None
          }
        }
      } else {
        threadLocalContext.get()
      }
    }

    def runWith[B](observer: EruObserver)(action: => B): B = {
      if (enableScopedValues) {
        scopedValueContext match {
          case Some(sv) => java.lang.ScopedValue.where(sv, observer).call(() => action)
          case None => action // Fallback for JVM < 25
        }
      } else {
        val previousContext = threadLocalContext.get()
        threadLocalContext.set(Some(observer))
        try {
          action
        } finally {
          threadLocalContext.set(previousContext)
        }
      }
    }
  }

  /** High-level API for running effects with complete context propagation. */
  object ContextRunner {

    /** Run an effect with full context using optimal propagation strategy.
      *
      * @param traceContext
      *   the trace context to propagate
      * @param fiberContext
      *   the fiber context to propagate
      * @param observer
      *   the observer to propagate
      * @param effect
      *   the effect to run within this context
      * @tparam E
      *   the error type of the effect
      * @tparam A
      *   the success type of the effect
      * @return
      *   the effect with context propagation applied
      */
    def runWithFullContext[E, A](
      traceContext: EruTrace.TraceContext,
      fiberContext: FiberContext,
      observer: EruObserver
    )(effect: Eru[E, A]): Eru[E | Throwable, A] = {
      Eru.blocking {
        TraceContextProvider.runWith(traceContext) {
          FiberContextProvider.runWith(fiberContext) {
            ObserverContextProvider.runWith(observer) {
              effect.unsafeRunSync()
            }
          }
        }
      }
    }

    /** Run an effect with trace context only.
      *
      * @param traceContext
      *   the trace context to propagate
      * @param effect
      *   the effect to run within this context
      * @tparam E
      *   the error type of the effect
      * @tparam A
      *   the success type of the effect
      * @return
      *   the effect with trace context propagation applied
      */
    def runWithTraceContext[E, A](
      traceContext: EruTrace.TraceContext
    )(effect: Eru[E, A]): Eru[E | Throwable, A] = {
      Eru.blocking {
        TraceContextProvider.runWith(traceContext) {
          effect.unsafeRunSync()
        }
      }
    }

    /** Run an effect with observer context only.
      *
      * @param observer
      *   the observer to propagate
      * @param effect
      *   the effect to run within this context
      * @tparam E
      *   the error type of the effect
      * @tparam A
      *   the success type of the effect
      * @return
      *   the effect with observer context propagation applied
      */
    def runWithObserver[E, A](observer: EruObserver)(effect: Eru[E, A]): Eru[E | Throwable, A] = {
      Eru.blocking {
        ObserverContextProvider.runWith(observer) {
          effect.unsafeRunSync()
        }
      }
    }
  }

  /** Diagnostics and monitoring for context propagation strategy. */
  object ContextDiagnostics {

    /** Information about the current context propagation strategy.
      *
      * @param strategy
      *   the propagation strategy being used
      * @param jvmVersion
      *   the current JVM version
      * @param scopedValuesSupported
      *   whether Scoped Values are supported
      * @param activeContexts
      *   which contexts are currently active
      */
    case class ContextInfo(
      strategy: String,
      jvmVersion: String,
      scopedValuesSupported: Boolean,
      activeContexts: Map[String, Boolean]
    )

    /** Get information about current context propagation.
      *
      * @return
      *   diagnostic information about the context system
      */
    def getContextInfo(): ContextInfo = {
      val strategy = if (enableScopedValues) "ScopedValues" else "ThreadLocal"
      val jvmVersion = System.getProperty("java.version")
      val activeContexts = Map(
        "trace" -> TraceContextProvider.current().isDefined,
        "fiber" -> FiberContextProvider.current().isDefined,
        "observer" -> ObserverContextProvider.current().isDefined
      )

      ContextInfo(
        strategy = strategy,
        jvmVersion = jvmVersion,
        scopedValuesSupported = enableScopedValues,
        activeContexts = activeContexts
      )
    }

    /** Measure performance overhead of context operations.
      *
      * Useful for benchmarking the performance difference between ThreadLocal and Scoped Values.
      *
      * @param operation
      *   the operation to measure
      * @tparam A
      *   the return type of the operation
      * @return
      *   a tuple of the operation result and duration in nanoseconds
      */
    def measureContextOverhead[A](operation: => A): (A, Long) = {
      val startTime = System.nanoTime()
      val result = operation
      val duration = System.nanoTime() - startTime
      (result, duration)
    }
  }
}
