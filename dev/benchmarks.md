# Benchmarks — Measuring Eru Core (JVM)

Status: Enhanced following JMH best practices (developer tool; not part of CI)

## Purpose
- Provide directional performance baselines for Eru's effect system
- Guide optimizations with data-driven insights
- Detect regressions when changing interpreter internals
- Validate JVM optimizations are working correctly
- Establish proper baselines for defensible performance claims

## Scope
- JVM-only using JMH with proper statistical analysis
- Microbenchmarks target hot paths in the effect system
- Baseline and validation benchmarks following JMH recommendations

## Available Benchmark Suites

### Core Performance Benchmarks
- **`EruMapFlatMapBench`**: Throughput comparison of map vs flatMap chains at depths 10/100/1000
- **`EruRuntimeBench`**: Average time measurements for core operations (composition, error handling, etc.)

### Validation & Baseline Benchmarks (New)
- **`BaselineBench`**: Minimum overhead measurements and raw function composition baselines
- **`ValidationBench`**: JVM optimization validation (dead code elimination, constant folding, blackhole effectiveness)

## Running Benchmarks

### Basic Usage
From the project root:
```bash
# Run all benchmarks with default settings
sbt bench

# Run all benchmarks with profiling
sbt benchWithProfiling
```

### Advanced Usage
```bash
# Run specific benchmark class
sbt "project eruBenchJVM; jmh:run .*EruMapFlatMapBench.*"

# Run with custom JMH settings
sbt "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 .*BaselineBench.*"

# Run baseline benchmarks only
sbt "project eruBenchJVM; jmh:run .*BaselineBench.*"

# Run validation benchmarks only
sbt "project eruBenchJVM; jmh:run .*ValidationBench.*"
```

### Profiling Integration
Following JMH recommendations for deeper analysis:

```bash
# GC profiling - shows allocation rates and collection behavior
sbt "project eruBenchJVM; jmh:run -prof gc .*EruRuntimeBench.*"

# Perfasm profiling - shows generated assembly code
sbt "project eruBenchJVM; jmh:run -prof perfasm .*BaselineBench.*"

# Async profiler integration (requires async-profiler setup)
sbt "project eruBenchJVM; jmh:run -prof async:output=flamegraph .*ValidationBench.*"

# Stack profiling - shows call stack samples
sbt "project eruBenchJVM; jmh:run -prof stack .*EruMapFlatMapBench.*"

# Multiple profilers
sbt "project eruBenchJVM; jmh:run -prof gc -prof stack .*EruRuntimeBench.*"
```

## Benchmark Validation Process

Before trusting performance results, validate using the new benchmark suites:

### Step 1: Run Baseline Benchmarks
```bash
sbt "project eruBenchJVM; jmh:run .*BaselineBench.*"
```
**Expected Results:**
- `absoluteBaseline`: Very fast (~1-5 ns) - establishes JMH overhead
- `rawFunctionComposition`: Baseline for function composition without effects
- `simpleEruBaseline`: Baseline overhead of simplest Eru operation

### Step 2: Run Validation Benchmarks
```bash
sbt "project eruBenchJVM; jmh:run .*ValidationBench.*"
```
**Expected Results:**
- `deadCodeEliminationTest`: Much faster than `deadCodeEliminationControl` (validates DCE works)
- `constantFoldingTest`: Faster than `constantFoldingControl` (validates constant folding)
- `blackholeValidationWithoutConsumption` vs `blackholeValidationWithConsumption`: Shows blackhole necessity

### Step 3: Run Core Benchmarks with Context
```bash
sbt "project eruBenchJVM; jmh:run .*EruMapFlatMapBench.* .*EruRuntimeBench.*"
```
**Interpretation:**
- Compare results against baselines from Step 1
- Validate optimization effectiveness using validation results from Step 2

## Interpreting Results

### Statistical Analysis
- All results include confidence intervals (±values)
- Look for statistical significance in comparisons
- Multiple runs reduce measurement noise

### Relative vs Absolute Performance
- **Prefer**: Relative comparisons across commits/branches
- **Caution**: Absolute numbers are environment-specific
- **Best Practice**: Compare against established baselines

### JVM Optimization Validation
- **Dead Code Elimination**: Unused results should show near-zero time
- **Constant Folding**: Compile-time constants should be very fast
- **Blackhole Effectiveness**: Results should differ significantly with/without blackhole

### Profiler Integration
Watch for:
- **GC Profiler**: High allocation rates, frequent collections
- **Perfasm Profiler**: Optimized assembly code, eliminated operations
- **Stack Profiler**: Hotspots and unexpected call patterns

## Development Policy

### Benchmark Usage
- Benchmarks are not run in CI (too resource-intensive)
- Include before/after results in PRs that change interpreter internals
- Validate optimizations using baseline and validation benchmarks

### Performance Claims
Before making public performance claims:
1. ✅ Run baseline benchmarks to establish overhead costs
2. ✅ Run validation benchmarks to confirm JVM optimizations work
3. ⏳ Use profilers to confirm benchmarks measure intended work
4. ⏳ Test across multiple environments for consistency

### Adding New Benchmarks
- Keep benchmarks simple and representative
- Add corresponding baseline benchmarks for new operation types
- Include validation benchmarks for new optimization strategies
- Document expected results and interpretation guidelines

## Environment Considerations

### Current Default Environment
- JDK 21.0.8, OpenJDK 64-Bit Server VM
- JVM Options: -server -Xms2G -Xmx2G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions
- Single-threaded execution for consistency

### Environmental Factors That Affect Results
- **Hardware**: CPU architecture, memory speed, thermal throttling
- **JVM Version**: Different optimization strategies across versions
- **GC Algorithm**: G1, Parallel, ZGC have different performance characteristics
- **System Load**: Other processes competing for resources

### Reproducibility
- Document exact environment for published results
- Use consistent JVM flags across benchmark runs
- Consider multiple environment validation for important claims
