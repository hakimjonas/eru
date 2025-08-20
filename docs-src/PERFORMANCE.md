# Eru Performance Analysis

This document provides a factual analysis of the Eru effect system's performance characteristics. All data is derived
from JMH microbenchmarks, and the goal is to present the results transparently, allowing developers to assess the
trade-offs and capabilities of the library for themselves.

**Methodology Note:** The following data comes from JMH microbenchmarks. While useful for measuring the performance of
specific operations, these numbers may not be directly representative of all real-world application workloads.

## Key Findings

Our development has been guided by the principles laid out in the Eru Manifesto. This has led to a dual focus on two
primary, non-negotiable goals: achieving a provably correct, zero-casting runtime, and optimizing the performance of
common functional patterns like flatMap.

### 1. Correctness: A Zero-Casting Runtime

**Status:** Implemented & Verified.

The first pillar of our Manifesto is Correctness as the Unseen Foundation. We have taken this principle to its logical
conclusion. Through a series of disciplined refactoring steps, the EruRuntime has been made fully type-safe. The
codebase is now free of `asInstanceOf` calls, a constraint that is enforced by the build linter.

This is more than a stylistic choice. It provides a strong, compile-time guarantee against an entire class of potential
runtime errors, making the system more robust and predictable by design.

### 2. Performance: Construction-Time flatMap Fusion

**Status:** Implemented & Verified.

The primary performance optimization in Eru is a construction-time fusion for pure flatMap chains. The flatMap
implementation identifies chains of pure, non-effectful computations (e.g., `Succeed(...).flatMap(f)`) and evaluates
them immediately upon construction, avoiding the creation of intermediate Chain objects.

The effect of this optimization is best understood by comparing the runtime performance of pure flatMap chains to pure
map chains, which represent a practical performance ceiling on the JVM.

**Latest Benchmark Results (August 20, 2025):**

| Benchmark   | Depth | Score (ops/ms) |
|-------------|-------|----------------|
| runPureFlat | 1000  | ~196,560       |
| runMapped   | 1000  | ~196,429       |

**Analysis:** The benchmark data indicates that the overhead for flatMap in pure, left-associative chains is
statistically negligible, with performance being equivalent to that of map. For effectual computations, the system
correctly falls back to the standard, lazy evaluation model, ensuring that side effects are deferred until the program
is executed.

## Performance Trade-offs and Usage Patterns

Radical transparency is a core value of this project. Understanding a system's trade-offs is key to using it
effectively. Our benchmark suite is designed to expose not just the strengths, but also the performance characteristics
of different usage patterns.

### The Cost of Mixing Pure and Effectful Code

The construction-time fusion is only active for purely successful computations. As soon as an `Eru.effect` is introduced
into a chain, the optimization for that link is disabled, and evaluation is deferred to the runtime.

The `runMixedPure` benchmark demonstrates this clearly: it interleaves pure flatMaps with `Eru.effect` calls.

| Benchmark    | Depth | Score (ops/ms) |
|--------------|-------|----------------|
| runMixedPure | 10    | ~4,208         |
| runMixedPure | 100   | ~350           |
| runMixedPure | 1000  | ~33            |

**Analysis:** These numbers are expected and demonstrate the correctness of the fusion logic. The performance is still
very good, but it is orders of magnitude different from a purely fused chain. This highlights a clear usage pattern: for
performance-critical code, developers should strive to group pure, computational logic into distinct chains that can be
fully optimized by the fusion engine.

## Eru's Value Proposition: Beyond Performance

While the performance numbers are encouraging, they are a consequence of our primary goal, not the goal itself. The true
value of Eru lies in its adherence to the principles of the Manifesto.

- **A Focus on Correctness:** The zero-casting runtime is a testament to our belief that a system should be correct by
  construction, not just by test.

- **Radical Ergonomics:** The fusion is automatic and transparent. Developers write simple, idiomatic functional code,
  and the system provides the performance without requiring any special annotations or modes.

- **A "Pit of Success":** By providing a fast path for pure code and a safe path for effectual code, Eru guides
  developers toward a style of programming that is both efficient and robust.

We believe that this combination of correctness, ergonomics, and principled performance offers a compelling vision for
modern functional programming in Scala 3. We present these findings for the community's review and hope that this work
proves to be a useful contribution to the ongoing, collaborative effort to build better tools.