# Benchmark Methodology

## Principles

1. **Transparency**: Be clear about what each benchmark measures
2. **Fairness**: Ensure apples-to-apples comparisons
3. **Realism**: Include benchmarks that reflect real-world usage
4. **Context**: Always present results with appropriate context

## Benchmark Categories

### 1. Pure Value Optimization Benchmarks
**File**: `PureValueOptimizationBench.scala`
**What they measure**: How well frameworks optimize operations on already-computed values
**Real-world scenario**: Combining pre-computed results, building data structures from constants
**Important note**: These favor frameworks with pure value detection

### 2. Fair Concurrency Benchmarks
**File**: `FairConcurrencyBench.scala`
**What they measure**: Actual concurrent execution performance with real effects
**Real-world scenario**: I/O operations, parallel computations, async coordination
**Important note**: These measure true concurrency performance

### 3. Core Operation Benchmarks
**File**: `CoreOperationsBench.scala`
**What they measure**: Basic effect construction and composition
**Real-world scenario**: Building effect chains, error handling, resource management
**Important note**: Foundation for all other operations

## Fair Comparison Rules

### Rule 1: Same Operations
Each framework must perform the exact same operation:
- Same input values
- Same computation logic
- Same result type
- Same error handling semantics

### Rule 2: Idiomatic Code
Use each framework's idiomatic patterns:
- Eru: `Eru.effect`, `fork`, `race`, `zipPar`
- ZIO: `ZIO.attempt`, `fork`, `raceEither`, `zipPar`
- Cats Effect: `IO.delay`, `start`, `race`, `parTupled`

### Rule 3: Consistent Runtime Setup
- Same JVM flags
- Same warm-up iterations
- Same measurement iterations
- Same thread pool configuration where applicable

### Rule 4: Clear Categorization
Benchmarks must clearly indicate what they test:
- Suffix `PureValues` for pure value optimization tests
- Suffix `LightEffects` for minimal computation tests
- Suffix `HeavyEffects` for I/O or blocking operations
- Suffix `Mixed` for combinations

## Measurement Guidelines

### What to Measure
1. **Throughput**: Operations per millisecond (ops/ms)
2. **Latency**: Time per operation (where appropriate)
3. **Memory**: Allocation rate (in profiling mode)
4. **Scalability**: Performance with varying concurrency levels

### What NOT to Do
1. **Don't mix categories**: Keep pure value and effect benchmarks separate
2. **Don't hide context**: Always explain what optimizations apply
3. **Don't cherry-pick**: Report all relevant benchmarks, not just favorable ones
4. **Don't over-optimize for benchmarks**: Real-world performance matters more

## Reporting Results

### Required Context
When reporting benchmark results, always include:

1. **Benchmark category** (pure value optimization vs real effects)
2. **What's being measured** (specific operation and scenario)
3. **Why it matters** (real-world relevance)
4. **Any optimizations that apply** (e.g., pure value detection)

### Example Result Presentation

#### Good:
"In pure value optimization benchmarks, Eru achieves 600x faster race operations by detecting that values are already computed and avoiding thread creation. This optimization applies when racing pre-computed results."

#### Bad:
"Eru is 600x faster than competitors in race operations."

## Running Benchmarks

### Quick Validation
```bash
# Run specific benchmark with minimal iterations
sbt "eruBenchJVM/Jmh/run -i 2 -wi 2 -f1 .*FairConcurrency.*Light.*"
```

### Full Suite
```bash
# Run complete benchmark suite
./tools/run-benchmarks.sh full
```

### Categories
```bash
# Pure value optimizations
sbt "eruBenchJVM/Jmh/run .*PureValueOptimization.*"

# Fair concurrent operations
sbt "eruBenchJVM/Jmh/run .*FairConcurrency.*"

# Core operations
sbt "eruBenchJVM/Jmh/run .*CoreOperations.*"
```

## Benchmark Evolution

As Eru evolves, benchmarks should:

1. **Add new categories** as new features are added
2. **Maintain backward compatibility** for historical comparison
3. **Document changes** when benchmarks are modified
4. **Keep both optimized and fair versions** to show different aspects

## Integrity Checklist

Before publishing benchmark results:

- [ ] Benchmarks compile and run without errors
- [ ] Each comparison is apples-to-apples
- [ ] Results are reproducible
- [ ] Context is clearly provided
- [ ] Both favorable and unfavorable results are included
- [ ] Real-world relevance is explained
- [ ] Optimizations are documented

## Conclusion

Good benchmarks are:
- **Honest** about what they measure
- **Fair** to all frameworks
- **Relevant** to real usage
- **Reproducible** by others
- **Contextual** in presentation

The goal is to help users make informed decisions, not to win benchmark games.