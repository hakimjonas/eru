# JVM 25 Migration Plan for Eru Effect System

## Overview

This document outlines a two-phase plan to leverage JVM 25's new concurrency features in the Eru effect system. JVM 25 introduces significant improvements to Virtual Threads, Structured Concurrency (5th preview), and Scoped Values (finalized), providing opportunities for both immediate optimizations and architectural enhancements.

## Current State

- **Architecture**: Eru uses Virtual Threads backend with ThreadLocal-based structured concurrency
- **Performance**: 50-80x faster than other effect systems on current JVM
- **Capabilities**: Full structured concurrency semantics with child fiber tracking
- **Observability**: Custom tracing system with EruTrace integration

## Phase 1: Virtual Threads Backend Optimizations (JVM 25 Ready)

**Timeline**: 1-2 weeks
**Risk**: Low - maintains current architecture with optimizations
**Benefits**: Immediate performance gains from JVM 25 improvements

### 1.1 JFR Enhancements Integration

**What**: Leverage new JFR features for better observability

- **@Contextual Annotation**: Mark trace fields in custom JFR events
- **Cooperative Sampling**: Reduce profiling overhead
- **Context Association**: Link trace IDs with low-level JVM events

```scala
// Enhanced tracing with JFR @Contextual support
@jdk.jfr.Contextual
case class EruTraceEvent(
  @jdk.jfr.Contextual traceId: String,
  @jdk.jfr.Contextual spanId: String,
  operation: String,
  duration: Duration
) extends jdk.jfr.Event
```

**Implementation Steps**:
1. Update `EruTrace` to emit JFR events with `@Contextual` annotations
2. Modify `TracingEruObserver` to generate contextual events
3. Add JFR event throttling configuration for high-throughput scenarios

### 1.2 ForkJoinPool Optimizations

**What**: Take advantage of improved async execution

- **Enhanced Common Pool**: Better utilization of ForkJoinPool improvements
- **Reduced Context Switching**: Optimized thread pool management
- **Memory Efficiency**: Improved virtual address space handling

**Implementation Steps**:
1. Review `VTAsyncScheduler` usage of ForkJoinPool
2. Update async boundary handling in `handleSuspend`
3. Benchmark pool sizing and task distribution strategies

### 1.3 Scoped Values Migration (Preparation)

**What**: Replace ThreadLocal with Scoped Values for context propagation

- **Performance**: Lower memory overhead than ThreadLocal
- **Integration**: Native support for Virtual Threads
- **Compatibility**: Works seamlessly with structured concurrency

```scala
// Replace ThreadLocal trace context with Scoped Values
object TraceContext {
  private val CURRENT_TRACE = ScopedValue.newInstance[TraceContext]()

  def current(): Option[TraceContext] =
    ScopedValue.isBound(CURRENT_TRACE) match {
      case true => Some(CURRENT_TRACE.get())
      case false => None
    }

  def runWithContext[A](context: TraceContext)(action: => A): A =
    ScopedValue.where(CURRENT_TRACE, context).run(() => action)
}
```

**Implementation Steps**:
1. Create Scoped Values wrapper for trace context
2. Update `EruTrace` context management
3. Maintain backward compatibility during transition
4. Benchmark context propagation performance

### 1.4 G1 and ZGC Optimizations

**What**: Leverage garbage collector improvements

- **Reduced GC Overhead**: G1 remembered set optimizations
- **Better Memory Management**: ZGC virtual address space handling
- **Lower Latency**: Improved pause times for high-throughput workloads

**Implementation Steps**:
1. Update `.jvmopts` with JVM 25-optimized GC flags
2. Benchmark different GC configurations
3. Adjust memory allocation patterns if needed

## Phase 2: Structured Concurrency Backend Implementation

**Timeline**: 3-4 weeks
**Risk**: Medium - new backend implementation
**Benefits**: Native structured concurrency, enhanced performance and observability

### 2.1 Backend Architecture Extension

**What**: Add StructuredConcurrency variant to RuntimeBackend enum

```scala
enum RuntimeBackend {
  case Synchronous
  case VirtualThreads
  case StructuredConcurrency  // New JVM 25 backend

  // Enhanced capabilities
  case RuntimeBackend.StructuredConcurrency =>
    new BackendCapabilities(
      virtualThreads = true,
      structuredScopes = true,
      timersNonBlocking = true,
      scopedValues = true,        // New capability
      nativeStructuredTasks = true // New capability
    )
}
```

### 2.2 StructuredTaskScope Integration

**What**: Replace ThreadLocal-based scoping with native StructuredTaskScope

```scala
private class StructuredConcurrencyBackend extends ConcurrencyBackend {

  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] = {
    Eru.effect {
      // Use JVM 25's StructuredTaskScope.open() factory method
      val scope = StructuredTaskScope.open[Either[E, A]]()

      val subtask = scope.fork(() => {
        val (exit, finalizers) = Eru.executeWithFinalizers(fa)
        // Execute finalizers in structured scope
        finalizers.foreach(_.apply().unsafeRunSync())
        exit.toEither
      })

      new StructuredConcurrencyFiber(scope, subtask, observer)
    }
  }

  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2, (A, B)] = {
    Eru.effect {
      StructuredTaskScope.open[Either[E1 | E2, (A, B)]]().use { scope =>
        val taskA = scope.fork(() => fa.attempt.unsafeRunSync())
        val taskB = scope.fork(() => fb.attempt.unsafeRunSync())

        // Automatically waits for all subtasks or fails fast
        scope.join()

        (taskA.get(), taskB.get()) match {
          case (Right(a), Right(b)) => Right((a, b))
          case (Left(e), _) => Left(e)
          case (_, Left(e)) => Left(e)
        }
      }
    }
  }
}
```

### 2.3 Enhanced Resource Management

**What**: Leverage automatic subtask cleanup and cancellation

- **Automatic Cancellation**: Failed subtasks cancel siblings
- **Resource Cleanup**: Guaranteed finalizer execution
- **Exception Handling**: Proper error aggregation

**Key Features**:
1. **Fail-Fast Semantics**: First error cancels remaining tasks
2. **Resource Safety**: All finalizers execute before scope closure
3. **Interruption Handling**: Clean cancellation of subtasks

### 2.4 Scoped Values Context System

**What**: Complete migration to Scoped Values for all context propagation

```scala
object EruContext {
  private val TRACE_CONTEXT = ScopedValue.newInstance[EruTrace.TraceContext]()
  private val FIBER_CONTEXT = ScopedValue.newInstance[FiberContext]()
  private val OBSERVER_CONTEXT = ScopedValue.newInstance[EruObserver]()

  def runWithFullContext[E, A](
    trace: EruTrace.TraceContext,
    fiber: FiberContext,
    observer: EruObserver
  )(effect: Eru[E, A]): Eru[E, A] = {
    Eru.effect {
      ScopedValue.where(TRACE_CONTEXT, trace)
        .where(FIBER_CONTEXT, fiber)
        .where(OBSERVER_CONTEXT, observer)
        .run(() => effect.unsafeRunSync())
    }
  }
}
```

### 2.5 Performance Benchmarking

**What**: Comprehensive performance comparison between backends

**Benchmark Categories**:
1. **Context Propagation**: ThreadLocal vs Scoped Values
2. **Structured Concurrency**: Custom vs Native implementation
3. **Memory Usage**: Allocation patterns and GC pressure
4. **Throughput**: Operations per second under load
5. **Latency**: P95/P99 response times

**Expected Improvements**:
- **15-30% throughput gain** from Scoped Values
- **20-40% reduction** in GC overhead
- **Native JFR integration** with zero additional overhead
- **Better cancellation performance** with StructuredTaskScope

## Implementation Timeline

### Week 1-2: Phase 1 Implementation
- [ ] JFR enhancements integration
- [ ] ForkJoinPool optimizations
- [ ] Scoped Values preparation layer
- [ ] G1/ZGC configuration tuning
- [ ] Compatibility testing with JVM 25 EA

### Week 3-4: Phase 2 Foundation
- [ ] Structured Concurrency backend skeleton
- [ ] StructuredTaskScope integration
- [ ] Enhanced resource management
- [ ] Basic functionality testing

### Week 5-6: Phase 2 Completion
- [ ] Complete Scoped Values migration
- [ ] Performance benchmarking suite
- [ ] Documentation and examples
- [ ] Production readiness testing

## Risk Mitigation

### Phase 1 Risks
- **JVM 25 Compatibility**: Extensive testing with EA builds
- **Performance Regression**: Comprehensive benchmark suite
- **API Changes**: Feature flags for gradual rollout

### Phase 2 Risks
- **Preview API Instability**: Structured Concurrency is 5th preview (stable)
- **Integration Complexity**: Incremental implementation with fallbacks
- **Performance Validation**: Side-by-side backend comparison

## Success Criteria

### Phase 1
- [ ] All existing tests pass on JVM 25
- [ ] JFR integration provides enhanced observability
- [ ] Scoped Values preparation layer ready
- [ ] 5-15% performance improvement from JVM optimizations

### Phase 2
- [ ] Structured Concurrency backend feature-complete
- [ ] 15-30% performance improvement over VT backend
- [ ] Enhanced observability with native JFR integration
- [ ] Seamless migration path for existing applications

## Future Considerations

### JVM 26+ Features
- **Scoped Values Enhancements**: Additional API improvements
- **Structured Concurrency Finalization**: Move from preview to stable
- **Virtual Thread Optimizations**: Continued JVM improvements
- **Project Loom Evolution**: Fibers and continuation support

### Eru Architectural Evolution
- **Multi-Backend Support**: Runtime backend selection
- **Advanced Structured Concurrency**: Custom scope policies
- **Enhanced Observability**: Deep JFR integration
- **Cross-Platform Consistency**: Unified API across JVM/Native

---

*This plan positions Eru as the first effect system to fully leverage JVM 25's structured concurrency features, maintaining our performance leadership while providing enhanced developer experience and observability.*