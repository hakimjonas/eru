#!/bin/bash

# Fair Benchmark Runner
# Runs modular benchmark categories with structured JSON output
#
# Usage:
#   ./run-fair-benchmarks.sh [categories...] [options]
#
# Categories:
#   core         - Core Operations (succeed, map, flatMap, chains)
#   errors       - Error Handling (fail, attempt, recover)
#   state        - State Management (Ref operations)
#   coord        - Coordination Primitives (Deferred, Semaphore)
#   concurrency  - Concurrency & Parallelism (race, fork, zipPar)
#   resources    - Resource Management (bracket, ensure, finalizers)
#   stack        - Stack Safety (deep chains, nested composition)
#   collections  - Collection Operations (traverse, sequence, parallel)
#   all          - Run all categories
#
# Options:
#   --quick     - Quick run (1 warmup, 3 measurements)
#   --full      - Full statistical run (3 warmups, 5 measurements)
#   --output=X  - Output JSON file (default: fair-benchmark-results.json)

set -e

# Default configuration
WARMUP_ITERATIONS=3
MEASUREMENT_ITERATIONS=5
OUTPUT_FILE="fair-benchmark-results.json"
CATEGORIES=()

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    core|errors|state|coord|concurrency|resources|stack|collections)
      CATEGORIES+=("$1")
      shift
      ;;
    all)
      CATEGORIES=("core" "errors" "state" "coord" "concurrency" "resources" "stack" "collections")
      shift
      ;;
    --quick)
      WARMUP_ITERATIONS=1
      MEASUREMENT_ITERATIONS=3
      shift
      ;;
    --full)
      WARMUP_ITERATIONS=5
      MEASUREMENT_ITERATIONS=10
      shift
      ;;
    --output=*)
      OUTPUT_FILE="${1#*=}"
      shift
      ;;
    -h|--help)
      echo "Fair Benchmark Runner"
      echo ""
      echo "Usage: $0 [categories...] [options]"
      echo ""
      echo "Categories:"
      echo "  core         - Core Operations (~2min)"
      echo "  errors       - Error Handling (~2min)" 
      echo "  state        - State Management (~3min)"
      echo "  coord        - Coordination Primitives (~4min)"
      echo "  concurrency  - Concurrency & Parallelism (~5min)"
      echo "  resources    - Resource Management (~4min)"
      echo "  stack        - Stack Safety (~3min)"
      echo "  collections  - Collection Operations (~4min)"
      echo "  all          - All categories (~27min)"
      echo ""
      echo "Options:"
      echo "  --quick      - Quick run (less statistical confidence)"
      echo "  --full       - Full statistical run (more confidence)"
      echo "  --output=X   - Output JSON file"
      echo ""
      echo "Examples:"
      echo "  $0 core errors --quick"
      echo "  $0 all --output=results.json"
      echo "  $0 state coord"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Default to core if no categories specified
if [ ${#CATEGORIES[@]} -eq 0 ]; then
  CATEGORIES=("core")
fi

# Map categories to class names
declare -A CATEGORY_CLASSES
CATEGORY_CLASSES[core]="net.ghoula.eru.bench.fair.CoreOperationsBench"
CATEGORY_CLASSES[errors]="net.ghoula.eru.bench.fair.ErrorHandlingBench"
CATEGORY_CLASSES[state]="net.ghoula.eru.bench.fair.StateManagementBench"
CATEGORY_CLASSES[coord]="net.ghoula.eru.bench.fair.CoordinationBench"
CATEGORY_CLASSES[concurrency]="net.ghoula.eru.bench.fair.ConcurrencyBench"
CATEGORY_CLASSES[resources]="net.ghoula.eru.bench.fair.ResourceManagementBench"
CATEGORY_CLASSES[stack]="net.ghoula.eru.bench.fair.StackSafetyBench"
CATEGORY_CLASSES[collections]="net.ghoula.eru.bench.fair.CollectionOperationsBench"

# Build class list
CLASSES=()
for category in "${CATEGORIES[@]}"; do
  if [[ -n "${CATEGORY_CLASSES[$category]}" ]]; then
    CLASSES+=("${CATEGORY_CLASSES[$category]}")
  else
    echo "Unknown category: $category"
    exit 1
  fi
done

# Join classes with spaces
CLASS_LIST=$(IFS=" "; echo "${CLASSES[*]}")

echo "Running Fair Benchmarks:"
echo "  Categories: ${CATEGORIES[*]}"
echo "  Classes: ${#CLASSES[@]} benchmark classes"
echo "  Warmup: $WARMUP_ITERATIONS iterations"
echo "  Measurement: $MEASUREMENT_ITERATIONS iterations"
echo "  Output: $OUTPUT_FILE"
echo ""

# Run the benchmarks
sbt "eruBenchJVM/Jmh/run -wi $WARMUP_ITERATIONS -i $MEASUREMENT_ITERATIONS -f 1 -t 1 -rf json -rff $OUTPUT_FILE $CLASS_LIST"

echo ""
echo "Benchmarks complete! Results saved to: $OUTPUT_FILE"
echo ""
echo "Quick analysis:"
jq -r '.[] | select(.benchmark | contains("eru")) | "\(.benchmark): \(.primaryMetric.score | floor) ops/ms"' "$OUTPUT_FILE" 2>/dev/null || echo "Install jq for quick results analysis"