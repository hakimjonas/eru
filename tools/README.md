# Tools Directory

This directory contains development and benchmarking tools for Eru.

## Benchmarking Tools

### run-benchmarks.sh

Main benchmark runner with multiple execution modes.

**Usage:**
```bash
./tools/run-benchmarks.sh [mode] [options]
```

**Modes:**
- `smoke` - Representative sampling across all categories (2-3 min, default)
- `ci` - Comprehensive CI-friendly sampling (5-8 min, recommended for CI/CD)
- `comparative` - All comparative benchmarks across effect systems (25-35 min)
- `scaling` - Parametric scaling analysis (20-30 min)
- `memory` - Memory and GC analysis (10-15 min)
- `full` - Complete benchmark suite (60-90 min)

**Options:**
- `--quick` - Fast execution (2 warmups, 3 measurements)
- `--thorough` - Thorough statistical run (3 warmups, 5 measurements)
- `--gc` - Include GC profiling
- `--output=X` - Output directory (default: benchmark-results)
- `--help` - Show help message

**Examples:**
```bash
# Quick smoke test
./tools/run-benchmarks.sh smoke --quick

# CI benchmarks with thorough sampling
./tools/run-benchmarks.sh ci --thorough

# Full comparative analysis with GC profiling
./tools/run-benchmarks.sh comparative --gc
```

### analyze-benchmarks.sh

Shell-based benchmark result analyzer for quick summaries.

**Usage:**
```bash
./tools/analyze-benchmarks.sh <timestamp>
./tools/analyze-benchmarks.sh latest
```

**Description:**
Provides quick analysis of benchmark results by timestamp. Requires `jq` for detailed analysis, falls back to basic output without it.

**Example:**
```bash
# Analyze latest results
./tools/analyze-benchmarks.sh latest

# Analyze specific run
./tools/analyze-benchmarks.sh 2025-09-28_08-55-33
```

### analyze-benchmarks.scala

Scala-based comprehensive benchmark analyzer.

**Usage:**
```bash
scala tools/analyze-benchmarks.scala [timestamp]
```

**Description:**
Provides detailed statistical analysis including:
- Per-category performance comparisons
- Cross-framework throughput ratios
- Top performers identification
- Performance concerns and recommendations

**Example:**
```bash
scala tools/analyze-benchmarks.scala 2025-09-28_08-55-33
```

### analyze-benchmarks.py

Python-based benchmark analyzer with statistical analysis.

**Usage:**
```bash
python3 tools/analyze-benchmarks.py <timestamp>
```

**Description:**
Python implementation of benchmark analysis with:
- Framework performance extraction
- Ratio calculations
- Statistical summaries

Requires Python 3.6+ with standard library only (no external dependencies).

## Development Tools

### eru-api-helper.scala

API exploration and validation tool.

**Usage:**
```bash
scala tools/eru-api-helper.scala [command] [args]
```

**Commands:**
- `--list-methods` - List all public methods in Eru
- `--validate <code>` - Validate code snippet against Eru API
- `--imports <method>` - Show required imports for method
- `--example <pattern>` - Generate working example for pattern
- `--help` - Show help message

**Examples:**
```bash
# List all Eru methods
scala tools/eru-api-helper.scala --list-methods

# Validate a code snippet
scala tools/eru-api-helper.scala --validate "parTraverse(list)(f)"

# Show required imports
scala tools/eru-api-helper.scala --imports parTraverse

# Generate example code
scala tools/eru-api-helper.scala --example parallel-processing
```

## Notes

### Benchmark Results

Benchmark results are stored in `benchmark-results/` with timestamped filenames. All generated files are gitignored to prevent repository bloat.

### CI Integration

Benchmarks are not run in CI. They are available for local development use via `./tools/run-benchmarks.sh`.

### Performance Analysis

For performance analysis:
1. Run benchmarks with appropriate mode
2. Use one of the analyzer tools to summarize results
3. Compare with previous runs using timestamps
4. Document significant findings in performance documentation

### Dependencies

- `run-benchmarks.sh` - Requires sbt and JDK 21+
- `analyze-benchmarks.sh` - Optional: jq (for detailed analysis)
- `analyze-benchmarks.scala` - Requires Scala CLI or scala-cli
- `analyze-benchmarks.py` - Requires Python 3.6+
- `eru-api-helper.scala` - Requires Scala CLI or scala-cli
