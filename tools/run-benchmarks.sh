#!/bin/bash

# 🚀 Eru Comprehensive Benchmark Runner
# Unified script for all benchmark types with modular execution
#
# Usage:
#   ./tools/run-benchmarks.sh [mode] [options]
#
# Modes:
#   smoke        - Quick smoke test (default, 3 warmups, 3 measurements)  
#   fair         - Fair benchmarks with JSON output (all categories)
#   matrix       - Parametric scaling benchmarks
#   memory       - Memory & GC analysis benchmarks
#   native       - Native platform benchmarks
#   full         - Complete benchmark suite (all modes)
#
# Options:
#   --quick      - Fast execution (2 warmups, 3 measurements)
#   --full       - Full statistical run with high iterations
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

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        smoke|fair|matrix|memory|native|full)
            MODE="$1"
            shift
            ;;
        --quick)
            WARMUP_ITERATIONS=2
            MEASUREMENT_ITERATIONS=3
            shift
            ;;
        --full)
            WARMUP_ITERATIONS=3
            MEASUREMENT_ITERATIONS=5
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
            echo "Modes:"
            echo "  smoke    - Quick smoke test (default)"
            echo "  fair     - Fair benchmarks with JSON output"
            echo "  matrix   - Parametric scaling benchmarks"
            echo "  memory   - Memory & GC analysis"
            echo "  native   - Native platform benchmarks"
            echo "  full     - Complete benchmark suite"
            echo ""
            echo "Options:"
            echo "  --quick     - Fast execution (2 warmups, 3 measurements)"
            echo "  --full      - Full statistical run (3 warmups, 5 measurements)"
            echo "  --gc        - Include GC profiling"
            echo "  --output=X  - Output directory"
            echo "  --help      - Show this help"
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

    if timeout ${timeout_duration} bash -c "LANG=C LC_ALL=C sbt 'eruBenchJVM/Jmh/run -rf json -rff $json_file -i $MEASUREMENT_ITERATIONS -wi $WARMUP_ITERATIONS -f1 -t1 $gc_option $bench_class'" 2>&1 | tee "$log_file"; then
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
    smoke)
        echo -e "${YELLOW}🔥 Running smoke test (quick validation)${NC}\n"
        run_benchmark "Core Operations Smoke Test" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        ;;
        
    fair)
        echo -e "${YELLOW}📊 Running fair benchmarks (all categories)${NC}\n"
        run_benchmark "Core Operations" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        run_benchmark "Error Handling" "net.ghoula.eru.bench.fair.ErrorHandlingBench" 
        run_benchmark "State Management" "net.ghoula.eru.bench.fair.StateManagementBench"
        run_benchmark "Concurrency" "net.ghoula.eru.bench.fair.ConcurrencyBench" 300
        run_benchmark "Resource Management" "net.ghoula.eru.bench.fair.ResourceManagementBench"
        run_benchmark "Stack Safety" "net.ghoula.eru.bench.fair.StackSafetyBench"
        run_benchmark "Collection Operations" "net.ghoula.eru.bench.fair.CollectionOperationsBench"
        run_benchmark "Coordination" "net.ghoula.eru.bench.fair.CoordinationBench"
        ;;
        
    matrix)
        echo -e "${YELLOW}📈 Running matrix benchmarks (parametric scaling)${NC}\n"
        run_benchmark "Concurrency Scaling" "net.ghoula.eru.bench.matrix.ConcurrencyScalingBench" 400
        run_benchmark "Depth Scaling" "net.ghoula.eru.bench.matrix.DepthScalingBench" 400
        run_benchmark "Data Size Scaling" "net.ghoula.eru.bench.matrix.DataSizeScalingBench" 400
        ;;
        
    memory)
        echo -e "${YELLOW}🧠 Running memory benchmarks (GC analysis)${NC}\n"
        INCLUDE_GC=true
        run_benchmark "Core Operations (Memory)" "net.ghoula.eru.bench.fair.CoreOperationsBench" 300
        run_benchmark "Concurrency (Memory)" "net.ghoula.eru.bench.fair.ConcurrencyBench" 360
        ;;
        
    native)
        echo -e "${YELLOW}🏃 Running native benchmarks (cross-platform)${NC}\n"
        echo -e "${MAGENTA}Running Native tests...${NC}"
        sbt "eruCoreNative/test; eruRuntimeNative/test" 2>&1 | tee "$OUTPUT_DIR/native-tests-$TIMESTAMP.log"
        echo -e "${GREEN}Native platform validation complete${NC}"
        ;;
        
    full)
        echo -e "${YELLOW}🎯 Running complete benchmark suite (all modes)${NC}\n"
        # Full mode should use full statistical iterations
        WARMUP_ITERATIONS=3
        MEASUREMENT_ITERATIONS=5

        echo -e "${BOLD}${MAGENTA}Phase 1: Smoke Test${NC}"
        run_benchmark "Core Operations Smoke" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        
        echo -e "${BOLD}${MAGENTA}Phase 2: Fair Benchmarks${NC}"
        run_benchmark "Core Operations" "net.ghoula.eru.bench.fair.CoreOperationsBench"
        run_benchmark "Error Handling" "net.ghoula.eru.bench.fair.ErrorHandlingBench"
        run_benchmark "Concurrency" "net.ghoula.eru.bench.fair.ConcurrencyBench" 300
        
        echo -e "${BOLD}${MAGENTA}Phase 3: Matrix Benchmarks${NC}"
        run_benchmark "Concurrency Scaling" "net.ghoula.eru.bench.matrix.ConcurrencyScalingBench" 400
        
        if [[ "$INCLUDE_GC" == "true" ]]; then
            echo -e "${BOLD}${MAGENTA}Phase 4: Memory Analysis${NC}"
            run_benchmark "Memory Analysis" "net.ghoula.eru.bench.fair.CoreOperationsBench" 300
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
echo -e "${BOLD}Results saved to:${NC} ${CYAN}$OUTPUT_DIR/*-$TIMESTAMP.log${NC}"
echo -e "${BOLD}Mode:${NC} $MODE"
echo -e "${BOLD}Config:${NC} $WARMUP_ITERATIONS warmups, $MEASUREMENT_ITERATIONS measurements (minimum 2-3 warmups for reliable results)"
if [[ "$INCLUDE_GC" == "true" ]]; then
    echo -e "${BOLD}GC Profiling:${NC} ${GREEN}Enabled${NC}"
fi
echo ""