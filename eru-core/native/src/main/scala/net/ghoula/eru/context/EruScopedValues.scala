package net.ghoula.eru.context

import net.ghoula.eru.*
import net.ghoula.eru.trace.EruTrace

/** ThreadLocal-based context propagation for Scala Native.
  *
  * This module provides ThreadLocal-based context propagation for Scala Native, maintaining
  * API compatibility with the JVM ScopedValues implementation while using only Native-compatible
  * features.
  *
  * Since Scala Native does not support ScopedValues or advanced JVM features, this implementation
  * provides a ThreadLocal-only approach that maintains identical API surface.
  */
object EruScopedValues {

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
    * Works with ThreadLocal on Native, providing a unified API for context management.
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

  /** Trace context provider using ThreadLocal for Native compatibility. */
  object TraceContextProvider extends ContextProvider[EruTrace.TraceContext] {

    /** ThreadLocal implementation for Native. */
    private val threadLocalContext: ThreadLocal[Option[EruTrace.TraceContext]] =
      ThreadLocal.withInitial(() => None)

    def current(): Option[EruTrace.TraceContext] = {
      threadLocalContext.get()
    }

    def runWith[B](context: EruTrace.TraceContext)(action: => B): B = {
      val previousContext = threadLocalContext.get()
      threadLocalContext.set(Some(context))
      try {
        action
      } finally {
        threadLocalContext.set(previousContext)
      }
    }
  }

  /** Fiber context provider using ThreadLocal for Native compatibility. */
  object FiberContextProvider extends ContextProvider[FiberContext] {

    /** ThreadLocal implementation for Native. */
    private val threadLocalContext: ThreadLocal[Option[FiberContext]] =
      ThreadLocal.withInitial(() => None)

    def current(): Option[FiberContext] = {
      threadLocalContext.get()
    }

    def runWith[B](context: FiberContext)(action: => B): B = {
      val previousContext = threadLocalContext.get()
      threadLocalContext.set(Some(context))
      try {
        action
      } finally {
        threadLocalContext.set(previousContext)
      }
    }
  }

  /** Observer context provider using ThreadLocal for Native compatibility. */
  object ObserverContextProvider extends ContextProvider[EruObserver] {

    /** ThreadLocal implementation for Native. */
    private val threadLocalContext: ThreadLocal[Option[EruObserver]] =
      ThreadLocal.withInitial(() => None)

    def current(): Option[EruObserver] = {
      threadLocalContext.get()
    }

    def runWith[B](observer: EruObserver)(action: => B): B = {
      val previousContext = threadLocalContext.get()
      threadLocalContext.set(Some(observer))
      try {
        action
      } finally {
        threadLocalContext.set(previousContext)
      }
    }
  }

  /** High-level API for running effects with complete context propagation. */
  object ContextRunner {

    /** Run an effect with full context using ThreadLocal propagation.
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
      *   the current JVM version (N/A for Native)
      * @param scopedValuesSupported
      *   whether Scoped Values are supported (always false on Native)
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
      val strategy = "ThreadLocal"
      val jvmVersion = "Scala Native"
      val activeContexts = Map(
        "trace" -> TraceContextProvider.current().isDefined,
        "fiber" -> FiberContextProvider.current().isDefined,
        "observer" -> ObserverContextProvider.current().isDefined
      )

      ContextInfo(
        strategy = strategy,
        jvmVersion = jvmVersion,
        scopedValuesSupported = false,
        activeContexts = activeContexts
      )
    }

    /** Measure performance overhead of context operations.
      *
      * Useful for benchmarking ThreadLocal performance on Native.
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