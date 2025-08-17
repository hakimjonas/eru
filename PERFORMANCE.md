# Performance Baseline and Optimization History

This document provides performance baselines, optimization history, and performance characteristics of the Eru effect system. All benchmarks are conducted on the JVM using JMH with standard warmup and measurement iterations.

## Current Performance Status (v0.3.0)

As of August 2025, Eru has evolved from "correct-but-slow" to "correct-and-selectively-fast" through targeted optimizations that maintain architectural purity.

### Core Performance Achievements

#### ✅ **MapChain Optimization (Construction-Time Map Fusion)**
**Status**: Implemented and highly successful  
**Performance Impact**: 6-8x improvement for consecutive map operations

**Before vs After:**
- **Depth 10**: ~4.4M → 28.3M ops/sec (**6.3x improvement**)
- **Depth 100**: ~400K → 3.7M ops/sec (**9.3x improvement**)
- **Depth 1000**: ~37K → 306K ops/sec (**8.3x improvement**)

**Technical Approach**: Modified `Eru.map()` to detect consecutive map operations and fuse them using function composition (`f.andThen(g)`), creating specialized `MapChain` nodes that eliminate intermediate Chain creation.

#### ✅ **Step-wise Fiber Interpreter**
**Status**: Complete and production-ready  
**Features**: Cast-free continuation management, true cooperative yielding, fair scheduling

### Benchmark Results (JVM, August 2025)

#### Sequential Composition Performance

**FlatMap Operations (Baseline Performance):**
- **Depth 10**: 1,688 ops/ms (~1.7M ops/sec)
- **Depth 100**: 258 ops/ms (~6.5x slower than depth 10)
- **Depth 1000**: 25.9 ops/ms (~65x slower than depth 10)

**Map Operations (With Eager Evaluation + MapChain Optimization):**
- **Depth 10**: 102,531 ops/ms (~102.5M ops/sec) - **60.8x faster than flatMap**
- **Depth 100**: 124,613 ops/ms (~124.6M ops/sec) - **482.8x faster than flatMap**
- **Depth 1000**: 124,525 ops/ms (~124.5M ops/sec) - **4,814x faster than flatMap**

**Key Insights:**
- MapChain optimization provides consistent 6-8x improvement across all depth levels
- Performance scaling is predictable and linear
- Optimization effectiveness increases with chain depth

#### Concurrent Runtime Performance

**Core Operation Benchmarks:**
- **Basic Composition**: 130.8 ns/op (succeed.flatMap chain)
- **Effect Overhead**: 126.9 ns/op (baseline effect creation/execution)
- **Deep Composition** (10 flatMaps): 2,061 ns/op (~206 ns per operation)
- **Error Handling**: 97.7 ns/op (very efficient error path)
- **Attempt Handling**: 136.3 ns/op (Result type conversion)
- **Ensure Handling**: 171.5 ns/op (finalizer management)
- **Sequential Zip**: 1,733 ns/op (tuple coordination)

**Performance Analysis:**
- **Excellent Baseline**: ~127 ns per basic operation is competitive
- **Consistent Scaling**: Deep composition shows predictable per-operation overhead
- **Efficient Error Handling**: Error paths are actually faster than success paths
- **Resource Management**: Ensure operations have acceptable overhead

## Optimization History

### ✅ Successful Optimizations

#### **1. Construction-Time Map Chain Fusion (August 2025)**
- **Impact**: 6-8x performance improvement for consecutive map operations
- **Approach**: Specialized `MapChain` AST node with function composition
- **Status**: Production ready, maintains perfect correctness

#### **2. Step-wise Fiber Interpreter (August 2025)**
- **Impact**: Enables true cooperative scheduling and concurrency
- **Approach**: Cast-free continuation management with fair scheduler
- **Status**: Complete, enables all concurrent features

#### **3. Continuation Stacking Optimization (August 2025)**
- **Impact**: Reduces interpreter steps for nested flatMap operations  
- **Approach**: Direct chain unwinding in interpreter
- **Status**: Integrated with step-wise interpreter

#### **4. Eager Evaluation for Pure Chains (August 2025)**
- **Impact**: Extraordinary performance improvement - 60-4,814x faster for map operations
- **Approach**: Construction-time evaluation of succeed().map() chains and MapChain composition
- **Status**: Production ready, maintains perfect correctness

### ❌ Attempted but Ineffective Optimizations

#### **Enhanced Direct Execution Batching**
- **Goal**: Batch multiple simple operations to reduce interpreter overhead
- **Result**: 5-12% performance regression across all benchmarks
- **Reason**: Added complexity overhead exceeded benefits; wrong optimization target
- **Status**: Reverted - approach shelved

#### **Trampoline-Optimized Continuation Stack**
- **Goal**: Replace `List` with `ArrayDeque` for better allocation performance
- **Result**: Mixed results - helped map operations but hurt flatMap performance by 9-11%
- **Reason**: ArrayDeque operations more expensive than expected for continuation patterns
- **Decision**: Reverted due to mixed performance profile and mutable collection introduction
- **Status**: Abandoned - violates project principles without clear benefit

#### **Eager FlatMap Chain Evaluation**
- **Goal**: Extend eager evaluation optimization to flatMap chains like succeed(x).flatMap(pure)
- **Result**: Caused side effects to be evaluated during construction when they should remain lazy
- **Reason**: Too aggressive - affected laziness semantics in existing code like fromOption
- **Decision**: Disabled to preserve correctness and laziness guarantees
- **Status**: Abandoned - requires more sophisticated purity analysis to be safe

## Performance Characteristics by Workload

### **Map-Heavy Workloads**: ⭐⭐⭐⭐⭐ Exceptional
- **Performance**: 100M+ ops/sec with eager evaluation + MapChain fusion
- **Scaling**: Consistent performance regardless of chain depth
- **Use Case**: Data transformation pipelines, functional composition
- **Achievement**: 60-4,814x faster than equivalent flatMap operations

### **FlatMap Sequential Composition**: ⭐⭐⭐ Good Baseline
- **Performance**: 1.7M ops/sec at depth 10, degrading to ~26K ops/sec at depth 1000
- **Scaling**: Linear degradation with depth (expected)
- **Optimization Opportunity**: Future safe FlatMap fusion could provide significant improvements

### **Basic Effects**: ⭐⭐⭐⭐ Strong Foundation
- **Performance**: ~8M ops/sec for simple operations
- **Overhead**: ~127 ns per basic effect
- **Characteristics**: Consistent and predictable

### **Concurrent Operations**: ⭐⭐⭐⭐ Solid Foundation
- **Performance**: Fair scheduling with proper resource management
- **Features**: True parallel execution, interruption, finalizers
- **Characteristics**: Cooperative model with deterministic behavior

## Architecture Strengths

### **Correctness Foundation**: ⭐⭐⭐⭐⭐
- **Zero Test Failures**: All 123 tests pass consistently
- **Type Safety**: 100% cast-free implementation throughout
- **Resource Safety**: Proper finalizer execution under all exit conditions

### **Performance Characteristics**: ⭐⭐⭐⭐
- **Strong Optimizations**: Proven 6-8x improvements possible
- **Consistent Baseline**: Predictable performance across operations
- **Scaling Characteristics**: Linear and understandable

### **Platform Support**: ⭐⭐⭐⭐⭐
- **JVM**: Full feature support including Future interop and timer integration
- **Scala Native**: Complete core functionality with platform-specific adaptations

## Optimization Roadmap

### **Priority 1: Pure Construction-Time Optimizations** (Planned)
- **Eager Evaluation**: Detect and evaluate pure chains at construction time
- **FlatMap Fusion**: Extend MapChain approach to simple flatMap patterns
- **Expected Impact**: 3-10x improvements for pure operation sequences

### **Priority 2: Enhanced Observability Through Optimization** (Future)
- **Performance-Aware Observer Integration**: Optimize event emission
- **Optimization Transparency**: Debug labels for applied optimizations

### **Priority 3: Type-Directed Optimization** (Future)  
- **Smart Constructor Patterns**: Ergonomic helpers that naturally optimize
- **Compile-time Analysis**: Use Scala 3's type system for optimization hints

## Lessons Learned

### **Successful Optimization Patterns**
1. **Construction-Time Optimization**: Detect and optimize at ADT creation time
2. **Specialized AST Nodes**: Purpose-built nodes for common patterns
3. **Function Composition**: Direct composition avoids interpreter overhead
4. **Conservative Approach**: Only optimize provably safe transformations

### **Failed Optimization Anti-Patterns**
1. **Fighting the Design**: Complex batching mechanisms that add overhead
2. **Mutable State Introduction**: Breaks architectural purity without clear wins
3. **Premature Complexity**: Optimizing for uncommon usage patterns
4. **Mixed Performance Profiles**: Helping some operations while hurting others

## Performance Philosophy

Eru maintains a "correctness-first, optimize-selectively" approach:

1. **Correctness is Non-Negotiable**: All optimizations must pass comprehensive tests
2. **Architectural Purity**: No optimizations that compromise core principles
3. **Measurable Benefits**: Only implement optimizations with clear, significant gains
4. **Transparency**: Users should understand when and why optimizations apply

The successful MapChain optimization demonstrates that significant performance improvements (6-8x) are achievable while maintaining Eru's commitment to correctness, ergonomics, and observability.

## Benchmarking Notes

- **Environment**: JVM-based benchmarks using JMH
- **Consistency**: Multiple runs with proper warmup ensure reliable results
- **Methodology**: Standard JMH practices with statistical confidence intervals
- **Reproducibility**: Benchmarks available in `eru-bench-jvm` module

For current benchmark execution: `sbt bench`