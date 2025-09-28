#!/bin/bash

# 🚀 Eru Comprehensive Benchmark Runner
# Unified script for all benchmark types with modular execution
#
# Usage:
#   ./tools/run-benchmarks.sh [mode] [options]
#
# Modes:
#   smoke        - Representative sampling across all categories (~2-3 min, default)
#   ci           - Comprehensive CI-friendly sampling (~5-8 min, CI/CD recommended)
#   comparative  - All comparative benchmarks vs ZIO/Cats Effect (~25-35 min, comprehensive)
#   scaling      - Parametric scaling analysis (~20-30 min)
#   memory       - Memory & GC analysis (~10-15 min)
#   full         - Complete benchmark suite (~60-90 min)
#
# Options:
#   --quick      - Fast execution (2 warmups, 3 measurements)
#   --thorough   - Thorough statistical run (3 warmups, 5 measurements)
#   --gc         - Include GC profiling
#   --output=X   - Output directory (default: benchmark-results)
#   --help       - Show this help

set -e

# Color codes for beautiful output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Default configuration
MODE="smoke"
WARMUP_ITERATIONS=3
MEASUREMENT_ITERATIONS=3
OUTPUT_DIR="benchmark-results"
INCLUDE_GC=false
TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)

# Validation flags
QUICK_SET=false
THOROUGH_SET=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        smoke|quick|ci|comparative|scaling|memory|full)
            MODE="$1"
            shift
            ;;
        --quick)
            if [[ "$THOROUGH_SET" == "true" ]]; then
                echo "Error: Cannot use both --quick and --thorough options"
                exit 1
            fi
            WARMUP_ITERATIONS=2
            MEASUREMENT_ITERATIONS=3
            QUICK_SET=true
            shift
            ;;
        --thorough)
            if [[ "$QUICK_SET" == "true" ]]; then
                echo "Error: Cannot use both --quick and --thorough options"
                exit 1
            fi
            WARMUP_ITERATIONS=3
            MEASUREMENT_ITERATIONS=5
            THOROUGH_SET=true
            shift
            ;;
        --gc)
            INCLUDE_GC=true
            shift
            ;;
        --output=*)
            OUTPUT_DIR="${1#*=}"
            shift
            ;;
        --help)
            echo "Eru Comprehensive Benchmark Runner"
            echo ""
            echo "Usage: $0 [mode] [options]"
            echo ""
            echo "MODES (what to benchmark):"
            echo "  smoke       - Representative sampling across all categories (~2-3 min) [default]"
            echo "  quick       - Fastest possible full picture (1 iteration, all benchmarks) (~5 min)"
            echo "  ci          - Comprehensive CI-friendly sampling (~5-8 min) [CI/CD recommended]"
            echo "  comparative - All comparative benchmarks vs ZIO/Cats Effect (~25-35 min) [comprehensive]"
            echo "  memory      - Memory & GC analysis (~10-15 min)"
            echo "  full        - Complete benchmark suite (~45-60 min)"
            echo ""
            echo "EXECUTION OPTIONS (how to run benchmarks):"
            echo "  --quick     - Fast execution (2 warmups, 3 measurements) [-20% time]"
            echo "  --thorough  - Thorough statistical run (3 warmups, 5 measurements) [+30% time]"
            echo "  --gc        - Include GC profiling [+10% time]"
            echo ""
            echo "OUTPUT OPTIONS (where to save results):"
            echo "  --output=DIR - Save results to DIR instead of benchmark-results/"
            echo ""
            echo "OTHER OPTIONS:"
            echo "  --help      - Show this help"
            echo ""
            echo "Examples:"
            echo "  $0 smoke --quick                    # Fast representative sample"
            echo "  $0 ci                               # Comprehensive CI benchmarks"
            echo "  $0 comparative --thorough           # Thorough comparative analysis"
            echo "  $0 full --quick                     # Complete suite, fast execution"
            echo "  $0 ci --output=ci-results           # CI benchmarks to ci-results/"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Record system information for reproducibility
SYSTEM_INFO_FILE="$OUTPUT_DIR/system-info-$TIMESTAMP.json"
echo "Recording system information to $SYSTEM_INFO_FILE"
cat > "$SYSTEM_INFO_FILE" << EOF
{
  "timestamp": "$(date -Iseconds)",
  "hostname": "$(hostname)",
  "os": {
    "name": "$(uname -s)",
    "release": "$(uname -r)",
    "machine": "$(uname -m)"
  },
  "cpu": {
    "model": "$(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs || echo 'N/A')",
    "cores": "$(nproc || echo 'N/A')",
    "threads": "$(grep -c ^processor /proc/cpuinfo || echo 'N/A')"
  },
  "memory": {
    "total_gb": "$(free -g | awk '/^Mem:/{print $2}' || echo 'N/A')",
    "available_gb": "$(free -g | awk '/^Mem:/{print $7}' || echo 'N/A')"
  },
  "java": {
    "version": "$(java -version 2>&1 | head -1 || echo 'N/A')",
    "vendor": "$(java -version 2>&1 | tail -1 || echo 'N/A')"
  },
  "benchmark": {
    "mode": "$MODE",
    "warmup_iterations": $WARMUP_ITERATIONS,
    "measurement_iterations": $MEASUREMENT_ITERATIONS,
    "gc_profiling": $INCLUDE_GC,
    "output_directory": "$OUTPUT_DIR"
  }
}
EOF

echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}                    🚀 ERU BENCHMARK RUNNER - $MODE MODE                     ${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Function to run a benchmark category
run_benchmark() {
    local bench_name="$1"
    local bench_class="$2"
    local timeout_duration="${3:-240}"
    
    echo -e "\n${BOLD}${BLUE}▶ Running:${NC} ${BOLD}$bench_name${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    local log_file="$OUTPUT_DIR/$(echo "$bench_name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')-$TIMESTAMP.log"
    local start_time=$(date +%s)
    
    local gc_option=""
    if [[ "$INCLUDE_GC" == "true" ]]; then
        gc_option="-prof gc"
    fi
    
    # Generate structured JSON output with unambiguous number formatting
    local json_file="$(pwd)/$OUTPUT_DIR/$(echo "$bench_name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr -d '()' | tr -d ',')-$TIMESTAMP.json"

    # Determine which project to use based on the benchmark class
    local project="eruBenchJVM"
    if [[ "$bench_class" == *"matrix"* ]]; then
        project="eruBenchMatrix"
    fi

    if timeout ${timeout_duration} bash -c "LANG=C LC_ALL=C sbt '$project/Jmh/run -rf json -rff $json_file -i $MEASUREMENT_ITERATIONS -wi $WARMUP_ITERATIONS -f1 -t1 $gc_option $bench_class'" 2>&1 | tee "$log_file"; then
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        echo -e "\n${BOLD}${GREEN}✅ COMPLETED${NC} - ${duration}s"
        echo -e "${BLUE}📄 JSON results:${NC} ${json_file}"
        echo -e "${BLUE}📋 Full log:${NC} ${log_file}"
        return 0
    else
        echo -e "\n${BOLD}${RED}❌ FAILED${NC}"
        return 1
    fi
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# Mode-specific execution
case $MODE in
    quick)
        echo -e "${YELLOW}⚡ Running quick full picture (minimal iterations, all categories)${NC}\n"
        WARMUP_ITERATIONS=1
        MEASUREMENT_ITERATIONS=1
        run_benchmark "Core Operations" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        run_benchmark "Error Handling" "net.ghoula.eru.bench.fair.ErrorHandlingBench"
        run_benchmark "State Management" "net.ghoula.eru.bench.fair.StateManagementBench"
        run_benchmark "Concurrency" "net.ghoula.eru.bench.fair.ConcurrencyBench"
        run_benchmark "Resource Management" "net.ghoula.eru.bench.fair.ResourceManagementBench"
        run_benchmark "Stack Safety" "net.ghoula.eru.bench.fair.StackSafetyBench"
        run_benchmark "Collection Operations" "net.ghoula.eru.bench.fair.CollectionOperationsBench"
        run_benchmark "Coordination" "net.ghoula.eru.bench.fair.CoordinationBench"
        ;;

    smoke)
        echo -e "${YELLOW}🔥 Running smoke test (representative sampling across all categories)${NC}\n"
        run_benchmark "Core Operations (Sample)" "net.ghoula.eru.bench.fair.CoreOperationsBench.eruSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.zioSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.ioSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.eruFlatMap|net.ghoula.eru.bench.fair.CoreOperationsBench.zioFlatMap|net.ghoula.eru.bench.fair.CoreOperationsBench.ioFlatMap"
        run_benchmark "Error Handling (Sample)" "net.ghoula.eru.bench.fair.ErrorHandlingBench.eruSuccessfulAttempt|net.ghoula.eru.bench.fair.ErrorHandlingBench.zioSuccessfulEither|net.ghoula.eru.bench.fair.ErrorHandlingBench.ioSuccessfulAttempt"
        run_benchmark "Concurrency (Sample)" "net.ghoula.eru.bench.fair.ConcurrencyBench.eruForkAwait|net.ghoula.eru.bench.fair.ConcurrencyBench.zioForkAwait|net.ghoula.eru.bench.fair.ConcurrencyBench.ioForkAwait"
        run_benchmark "Collection Operations (Sample)" "net.ghoula.eru.bench.fair.CollectionOperationsBench.eruForeachDiscard|net.ghoula.eru.bench.fair.CollectionOperationsBench.zioForeachDiscard|net.ghoula.eru.bench.fair.CollectionOperationsBench.ioForeachDiscard"
        run_benchmark "Coordination (Sample)" "net.ghoula.eru.bench.fair.StateManagementBench.eruRefBasic|net.ghoula.eru.bench.fair.StateManagementBench.zioRefBasic|net.ghoula.eru.bench.fair.StateManagementBench.ioRefBasic"
        ;;

    ci)
        echo -e "${YELLOW}🚀 Running CI benchmarks (comprehensive sampling for continuous integration)${NC}\n"
        # Core operations - basic + one challenging pattern
        run_benchmark "Core Operations (CI)" "net.ghoula.eru.bench.fair.CoreOperationsBench.eruSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.zioSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.ioSucceed|net.ghoula.eru.bench.fair.CoreOperationsBench.eruLongChain|net.ghoula.eru.bench.fair.CoreOperationsBench.zioLongChain|net.ghoula.eru.bench.fair.CoreOperationsBench.ioLongChain"
        # Error handling - both success and failure paths
        run_benchmark "Error Handling (CI)" "net.ghoula.eru.bench.fair.ErrorHandlingBench.eruSuccessfulAttempt|net.ghoula.eru.bench.fair.ErrorHandlingBench.zioSuccessfulEither|net.ghoula.eru.bench.fair.ErrorHandlingBench.ioSuccessfulAttempt|net.ghoula.eru.bench.fair.ErrorHandlingBench.eruFailRecover|net.ghoula.eru.bench.fair.ErrorHandlingBench.zioFailRecover|net.ghoula.eru.bench.fair.ErrorHandlingBench.ioFailRecover"
        # Concurrency - fundamental + parallel patterns
        run_benchmark "Concurrency (CI)" "net.ghoula.eru.bench.fair.ConcurrencyBench.eruForkAwait|net.ghoula.eru.bench.fair.ConcurrencyBench.zioForkAwait|net.ghoula.eru.bench.fair.ConcurrencyBench.ioForkAwait|net.ghoula.eru.bench.fair.ConcurrencyBench.eruZipPar|net.ghoula.eru.bench.fair.ConcurrencyBench.zioZipPar|net.ghoula.eru.bench.fair.ConcurrencyBench.ioZipPar"
        # Collections - traverse + parallel traverse
        run_benchmark "Collection Operations (CI)" "net.ghoula.eru.bench.fair.CollectionOperationsBench.eruTraverseBasic|net.ghoula.eru.bench.fair.CollectionOperationsBench.zioTraverseBasic|net.ghoula.eru.bench.fair.CollectionOperationsBench.ioTraverseBasic|net.ghoula.eru.bench.fair.CollectionOperationsBench.eruParTraverse|net.ghoula.eru.bench.fair.CollectionOperationsBench.zioParTraverse|net.ghoula.eru.bench.fair.CollectionOperationsBench.ioParTraverse"
        # State management - basic ref operations
        run_benchmark "State Management (CI)" "net.ghoula.eru.bench.fair.StateManagementBench.eruRefBasic|net.ghoula.eru.bench.fair.StateManagementBench.zioRefBasic|net.ghoula.eru.bench.fair.StateManagementBench.ioRefBasic|net.ghoula.eru.bench.fair.StateManagementBench.eruRefContention|net.ghoula.eru.bench.fair.StateManagementBench.zioRefContention|net.ghoula.eru.bench.fair.StateManagementBench.ioRefContention"
        # Resource management - a category where performance might be closer
        run_benchmark "Resource Management (CI)" "net.ghoula.eru.bench.fair.ResourceManagementBench.eruBracketSuccess|net.ghoula.eru.bench.fair.ResourceManagementBench.zioBracketSuccess|net.ghoula.eru.bench.fair.ResourceManagementBench.ioBracketSuccess|net.ghoula.eru.bench.fair.ResourceManagementBench.eruComplexResource|net.ghoula.eru.bench.fair.ResourceManagementBench.zioComplexResource|net.ghoula.eru.bench.fair.ResourceManagementBench.ioComplexResource"
        ;;

    comparative)
        echo -e "${YELLOW}📊 Running comparative benchmarks vs ZIO/Cats Effect (all categories)${NC}\n"
        run_benchmark "Core Operations" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        run_benchmark "Error Handling" "net.ghoula.eru.bench.fair.ErrorHandlingBench"
        run_benchmark "State Management" "net.ghoula.eru.bench.fair.StateManagementBench"
        run_benchmark "Concurrency" "net.ghoula.eru.bench.fair.ConcurrencyBench" 300
        run_benchmark "Resource Management" "net.ghoula.eru.bench.fair.ResourceManagementBench"
        run_benchmark "Stack Safety" "net.ghoula.eru.bench.fair.StackSafetyBench"
        run_benchmark "Collection Operations" "net.ghoula.eru.bench.fair.CollectionOperationsBench"
        run_benchmark "Coordination" "net.ghoula.eru.bench.fair.CoordinationBench"
        ;;

    scaling)
        echo -e "${YELLOW}📈 Running scaling benchmarks (parametric analysis)${NC}\n"
        echo -e "${RED}Scaling benchmarks not yet implemented${NC}"
        echo "Run 'comparative' mode for full comparison benchmarks"
        exit 0
        ;;

    memory)
        echo -e "${YELLOW}🧠 Running memory benchmarks (GC analysis)${NC}\n"
        INCLUDE_GC=true
        run_benchmark "Core Operations (Memory)" "net.ghoula.eru.bench.fair.CoreOperationsBench" 300
        run_benchmark "Concurrency (Memory)" "net.ghoula.eru.bench.fair.ConcurrencyBench" 360
        ;;
        
    full)
        echo -e "${YELLOW}🎯 Running complete benchmark suite (all modes)${NC}\n"
        # Full mode should use thorough statistical iterations by default
        WARMUP_ITERATIONS=3
        MEASUREMENT_ITERATIONS=5

        echo -e "${BOLD}${MAGENTA}Phase 1: Quick Validation${NC}"
        run_benchmark "Core Operations Smoke" "net.ghoula.eru.bench.fair.CoreOperationsBench"

        echo -e "${BOLD}${MAGENTA}Phase 2: Comparative Benchmarks${NC}"
        run_benchmark "Core Operations" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        run_benchmark "Error Handling" "net.ghoula.eru.bench.fair.ErrorHandlingBench"
        run_benchmark "State Management" "net.ghoula.eru.bench.fair.StateManagementBench"
        run_benchmark "Concurrency" "net.ghoula.eru.bench.fair.ConcurrencyBench" 300
        run_benchmark "Resource Management" "net.ghoula.eru.bench.fair.ResourceManagementBench"
        run_benchmark "Stack Safety" "net.ghoula.eru.bench.fair.StackSafetyBench"
        run_benchmark "Collection Operations" "net.ghoula.eru.bench.fair.CollectionOperationsBench"
        run_benchmark "Coordination" "net.ghoula.eru.bench.fair.CoordinationBench"

        echo -e "${BOLD}${MAGENTA}Phase 3: Additional Patterns${NC}"
        run_benchmark "Pattern Benchmarks" "net.ghoula.eru.bench.fair.PatternBench"
        run_benchmark "API Coverage" "net.ghoula.eru.bench.fair.ComprehensiveAPIBench"

        if [[ "$INCLUDE_GC" == "true" ]]; then
            echo -e "${BOLD}${MAGENTA}Phase 4: Memory Analysis${NC}"
            run_benchmark "Core Operations (Memory)" "net.ghoula.eru.bench.fair.CoreOperationsBench" 300
            run_benchmark "Concurrency (Memory)" "net.ghoula.eru.bench.fair.ConcurrencyBench" 360
        fi
        ;;
        
    *)
        echo -e "${RED}Unknown mode: $MODE${NC}"
        echo "Use --help for usage information"
        exit 1
        ;;
esac

echo -e "\n${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}                              🎉 BENCHMARKS COMPLETE                         ${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}Results saved to:${NC} ${CYAN}$OUTPUT_DIR/*-$TIMESTAMP.{json,log}${NC}"
echo -e "${BOLD}System info:${NC} ${CYAN}$SYSTEM_INFO_FILE${NC}"
echo -e "${BOLD}Mode:${NC} $MODE"
echo -e "${BOLD}Config:${NC} $WARMUP_ITERATIONS warmups, $MEASUREMENT_ITERATIONS measurements (minimum 2-3 warmups for reliable results)"
if [[ "$INCLUDE_GC" == "true" ]]; then
    echo -e "${BOLD}GC Profiling:${NC} ${GREEN}Enabled${NC}"
fi
echo ""