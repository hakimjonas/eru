#!/bin/bash

# Matrix Benchmark Runner
# Runs parametric benchmarks across multiple dimensions with proper statistical analysis
#
# Usage:
#   ./run-matrix-benchmarks.sh [category] [options]
#
# Categories:
#   concurrency  - Concurrency scaling benchmarks
#   depth        - Depth scaling benchmarks  
#   data         - Data size scaling benchmarks
#   all          - Run all matrix benchmarks
#
# Options:
#   --quick      - Quick run (reduced parameters, faster execution)
#   --full       - Full statistical run (all parameters, high confidence)
#   --gc         - Include GC profiling
#   --output=X   - Output file prefix (default: matrix-results)

set -e

# Default configuration
MEASUREMENT_ITERATIONS=10
WARMUP_ITERATIONS=5
FORKS=3
OUTPUT_PREFIX="matrix-results"
CATEGORY=""
INCLUDE_GC=false
PROFILE_OPTIONS=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    concurrency|depth|data|all)
      CATEGORY="$1"
      shift
      ;;
    --quick)
      MEASUREMENT_ITERATIONS=3
      WARMUP_ITERATIONS=2
      FORKS=1
      shift
      ;;
    --full)
      MEASUREMENT_ITERATIONS=15
      WARMUP_ITERATIONS=10
      FORKS=5
      shift
      ;;
    --gc)
      INCLUDE_GC=true
      PROFILE_OPTIONS="-prof gc:verbose"
      shift
      ;;
    --output=*)
      OUTPUT_PREFIX="${1#*=}"
      shift
      ;;
    -h|--help)
      echo "Matrix Benchmark Runner"
      echo ""
      echo "Usage: $0 [category] [options]"
      echo ""
      echo "Categories:"
      echo "  concurrency  - Thread count, fiber count, concurrency level scaling"
      echo "  depth        - Chain depth, nesting level scaling"
      echo "  data         - Collection size, payload size scaling"
      echo "  all          - All matrix benchmarks (~2+ hours with --full)"
      echo ""
      echo "Options:"
      echo "  --quick      - Quick run for development (5-10 min per category)"
      echo "  --full       - Full statistical analysis (30-60 min per category)" 
      echo "  --gc         - Include GC profiling"
      echo "  --output=X   - Output file prefix"
      echo ""
      echo "Examples:"
      echo "  $0 concurrency --quick"
      echo "  $0 all --full --gc --output=production-results"
      echo "  $0 depth --output=depth-analysis"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Default to all if no category specified
if [ -z "$CATEGORY" ]; then
  CATEGORY="all"
fi

echo "Matrix Benchmark Runner"
echo "======================"
echo "Category: $CATEGORY"
echo "Iterations: $WARMUP_ITERATIONS warmup, $MEASUREMENT_ITERATIONS measurement" 
echo "Forks: $FORKS"
echo "Output prefix: $OUTPUT_PREFIX"
if [ "$INCLUDE_GC" = true ]; then
  echo "GC profiling: enabled"
fi
echo ""

# Function to run benchmark with proper configuration
run_benchmark() {
  local bench_class="$1"
  local output_suffix="$2"
  local bench_name="$3"
  
  echo "Running $bench_name benchmarks..."
  echo "Class: $bench_class"
  echo "Output: ${OUTPUT_PREFIX}-${output_suffix}.json"
  echo ""
  
  local jmh_args="-wi $WARMUP_ITERATIONS -i $MEASUREMENT_ITERATIONS -f $FORKS -t 1"
  jmh_args="$jmh_args -rf json -rff ${OUTPUT_PREFIX}-${output_suffix}.json"
  
  if [ -n "$PROFILE_OPTIONS" ]; then
    jmh_args="$jmh_args $PROFILE_OPTIONS"
  fi
  
  # Run the benchmark
  sbt "eruBenchMatrix/Jmh/run $jmh_args $bench_class"
  
  echo ""
  echo "$bench_name benchmarks completed!"
  echo "Results saved to: ${OUTPUT_PREFIX}-${output_suffix}.json"
  echo ""
}

# Function to analyze results
analyze_results() {
  echo "Analyzing results..."
  
  # Basic analysis if jq is available
  if command -v jq >/dev/null 2>&1; then
    for file in ${OUTPUT_PREFIX}-*.json; do
      if [ -f "$file" ]; then
        echo "=== Results from $file ==="
        jq -r '.[] | select(.benchmark | contains("eru")) | "\(.benchmark): \(.primaryMetric.score | floor) \(.primaryMetric.scoreUnit)"' "$file" 2>/dev/null | head -10
        echo ""
      fi
    done
  else
    echo "Install jq for automatic results analysis"
  fi
}

# Run benchmarks based on category
case "$CATEGORY" in
  concurrency)
    run_benchmark ".*ConcurrencyScalingBench.*" "concurrency" "Concurrency Scaling"
    ;;
  depth)
    run_benchmark ".*DepthScalingBench.*" "depth" "Depth Scaling" 
    ;;
  data)
    run_benchmark ".*DataSizeScalingBench.*" "data" "Data Size Scaling"
    ;;
  all)
    echo "Running comprehensive matrix benchmark suite..."
    echo "This will take significant time with full configuration."
    echo ""
    
    run_benchmark ".*ConcurrencyScalingBench.*" "concurrency" "Concurrency Scaling"
    run_benchmark ".*DepthScalingBench.*" "depth" "Depth Scaling"
    run_benchmark ".*DataSizeScalingBench.*" "data" "Data Size Scaling"
    ;;
  *)
    echo "Unknown category: $CATEGORY"
    exit 1
    ;;
esac

analyze_results

echo "Matrix benchmarking complete!"
echo ""
echo "Next steps:"
echo "1. Analyze results files: ${OUTPUT_PREFIX}-*.json"
echo "2. Look for performance regression patterns"
echo "3. Identify optimal parameter ranges"
echo "4. Compare scaling characteristics across frameworks"
echo ""
echo "For detailed analysis, consider:"
echo "- Statistical analysis of variance across forks"
echo "- Performance regression detection"
echo "- Scaling law identification" 
echo "- Memory allocation pattern analysis"