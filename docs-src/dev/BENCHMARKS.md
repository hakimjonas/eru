### `benchmarks.md` (Updated)

# Benchmarks — Measuring Eru Core (JVM)

**Status**: Developer tool; not part of CI.

## Purpose

* Provide directional performance baselines for Eru's effect system.
* Guide optimizations with data-driven insights.
* Detect regressions when changing interpreter internals.
* Validate JVM optimizations are working correctly.

## Scope

* JVM-only using JMH with proper statistical analysis.
* Microbenchmarks target hot paths in the effect system.
* Baseline and validation benchmarks follow JMH recommendations to ensure correctness.

## Available Benchmark Suites

### Core Performance Benchmarks

* **`EruMapFlatMapBench`**: Throughput comparison of `map` vs `flatMap` chains at various depths. This is the primary
  suite for validating the construction-time fusion optimization.
* **`EruRuntimeBench`**: Average time measurements for core runtime operations (composition, error handling, finalizers,
  etc.).

### Validation & Baseline Benchmarks

* **`BaselineBench`**: Minimum overhead measurements and raw function composition baselines to provide context for Eru's
  performance.
* **`ValidationBench`**: Confirms that JVM optimizations like Dead Code Elimination and Constant Folding are behaving as
  expected, ensuring our benchmarks are measuring real work.

## Running Benchmarks

### Basic Usage

From the project root:

```bash
# Run all benchmarks with default settings
sbt bench
```

### Advanced Usage

```bash
# Run a specific benchmark class
sbt "project eruBenchJVM; jmh:run .*EruMapFlatMapBench.*"

# Run with a profiler to get deeper insights
sbt "project eruBenchJVM; jmh:run -prof gc"
```
