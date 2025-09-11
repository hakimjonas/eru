# Fair Benchmarks System

A comprehensive, modular benchmark system for fair apples-to-apples comparison of effect system performance across Eru, ZIO, and Cats Effect.

## 🎯 Design Principles

- **Modular Categories**: Organized by functional area, each runnable independently
- **Fair Comparisons**: Identical logical operations across all frameworks
- **No Parameters**: Fixed values to eliminate parameter variation effects
- **Structured Output**: JSON format for reliable analysis
- **Practical Runtime**: Each category runs in 2-5 minutes (~27min total for all)

## 📋 Categories

| Category | Coverage | Runtime | Benchmarks |
|----------|----------|---------|------------|
| **Core Operations** | succeed, map, flatMap, chains | ~2min | Basic effect system operations |
| **Error Handling** | fail, attempt, recover, transformation | ~2min | Error management patterns |
| **State Management** | Ref create/update/modify operations | ~3min | Concurrent state primitives |
| **Coordination** | Deferred, Semaphore, synchronization | ~4min | Inter-fiber coordination |
| **Concurrency & Parallelism** | race, fork, zipPar, parallel execution | ~5min | Concurrent execution patterns |
| **Resource Management** | bracket, ensure, finalizers, cleanup | ~4min | Resource discipline and cleanup |
| **Stack Safety** | deep chains, nested composition | ~3min | Stack safety under load |
| **Collection Operations** | traverse, sequence, parallel processing | ~4min | Collection-oriented effects |

## 🚀 Quick Start

```bash
# Run single category (fastest feedback)
./run-fair-benchmarks.sh core

# Run multiple categories  
./run-fair-benchmarks.sh core errors state

# Run concurrency and parallelism tests
./run-fair-benchmarks.sh concurrency resources

# Run all categories (comprehensive)
./run-fair-benchmarks.sh all

# Quick run with fewer iterations
./run-fair-benchmarks.sh core --quick

# Full statistical run
./run-fair-benchmarks.sh all --full --output=detailed-results.json

# Test specific areas
./run-fair-benchmarks.sh stack collections --quick
```

## 📊 Example Results

```bash
# Core Operations Results
CoreOperationsBench.eruChain:        37,283 ops/ms
CoreOperationsBench.zioChain:         7,922 ops/ms  
CoreOperationsBench.ioChain:             91 ops/ms

# Concurrency Results  
ConcurrencyBench.eruRaceBasic:      114,151 ops/ms
ConcurrencyBench.zioRaceBasic:       73,148 ops/ms
ConcurrencyBench.ioRaceBasic:        78,763 ops/ms

# State Management Results
StateManagementBench.eruRefBasic:   40,846 ops/ms
StateManagementBench.zioRefBasic:    8,591 ops/ms
StateManagementBench.ioRefBasic:        90 ops/ms

# Quick Analysis: Eru consistently outperforms alternatives across all categories
```

## 🛠️ Architecture

### Base Infrastructure
- `FairBenchmarkBase`: Shared configuration and utilities
- Consistent test values and helper methods
- Standardized JMH settings across all categories

### Category Structure
```scala
class CategoryBench extends FairBenchmarkBase {
  @Benchmark def eruOperation(): Type = runEru(/* Eru implementation */)
  @Benchmark def zioOperation(): Type = runZio(/* ZIO implementation */)  
  @Benchmark def ioOperation(): Type = runIO(/* Cats Effect implementation */)
}
```

### Adding New Categories
1. Create new class extending `FairBenchmarkBase`
2. Implement equivalent operations for all three frameworks
3. Add to `run-fair-benchmarks.sh` category mapping
4. Document expected runtime and coverage

## 📈 Analysis Tools

```bash
# View results with jq
jq '.[] | select(.benchmark | contains("eru")) | .primaryMetric.score' results.json

# Compare performance ratios
jq -r '.[] | "\(.benchmark): \(.primaryMetric.score)"' results.json | sort

# Extract specific categories
jq '.[] | select(.benchmark | contains("Core"))' results.json
```

## 🔬 Statistical Confidence

- **Quick Mode**: 1 warmup, 3 measurements (~30% faster)
- **Standard Mode**: 3 warmups, 5 measurements (balanced)
- **Full Mode**: 5 warmups, 10 measurements (highest confidence)

## 🎛️ Future Extensions

Categories available for expansion:
- **Advanced Parallelism**: When Phase 5 parallel operations are implemented
- **Resource Management**: Additional bracket patterns and complex cleanup scenarios  
- **Collection Polymorphism**: When generic collection support arrives (Phase 5)
- **Streaming Operations**: When EruStream is implemented (Phase 6)
- **Environment/Service Patterns**: Dependency injection benchmarks (Phase 6)

## 🏃‍♂️ Running Specific Benchmarks

```bash
# Run only Eru benchmarks from a category
sbt "eruBenchJVM/Jmh/run *CoreOperationsBench.eru*"

# Run with JMH profilers
sbt "eruBenchJVM/Jmh/run -prof gc CoreOperationsBench"

# Run single method
sbt "eruBenchJVM/Jmh/run CoreOperationsBench.eruSucceed"
```

This system provides reliable, comprehensive performance insights while remaining practical for iterative development and optimization work.