#!/bin/bash

# 🚀 Eru Performance Smoke Test
# Quick validation that Eru is competitive across all benchmark categories

set -e

# Color codes for beautiful terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}           🚀 ERU PERFORMANCE SMOKE TEST - COMPARATIVE VALIDATION            ${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)
RESULTS_DIR="benchmark-validation-results"
mkdir -p $RESULTS_DIR
SUMMARY_FILE="$RESULTS_DIR/smoke-test-$TIMESTAMP.md"

# Performance tracking
BENCHMARKS_RUN=0
BENCHMARKS_FAILED=0
FAILED_BENCHMARKS=()

# Function to run benchmark with beautiful output
run_benchmark() {
    local bench_name="$1"
    local bench_class="$2"
    local timeout_duration="${3:-180}"
    
    echo -e "\n${BOLD}${BLUE}▶ Running:${NC} ${BOLD}$bench_name${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    local log_file
    log_file="$RESULTS_DIR/$(echo "$bench_name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')-$TIMESTAMP.log"
    
    local start_time
    start_time=$(date +%s)
    
    # Run with quick settings: 1 warmup, 2 measurements for smoke test
    # Generate structured JSON output with unambiguous number formatting
    local json_file="$(pwd)/$RESULTS_DIR/$(echo "$bench_name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr -d '()' | tr -d ',')-$TIMESTAMP.json"

    # Store the command result properly
    if timeout ${timeout_duration} bash -c "LANG=C LC_ALL=C sbt 'eruBenchJVM/Jmh/run -rf json -rff $json_file -i 2 -wi 1 -f1 -t1 ${bench_class}'" 2>&1 | tee "$log_file"; then
        local exit_code=0
    else
        local exit_code=$?
    fi
    
    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))
    
    BENCHMARKS_RUN=$((BENCHMARKS_RUN + 1))
    
    if [[ $exit_code -eq 0 ]]; then
        echo -e "\n${BOLD}${GREEN}✅ COMPLETED${NC} - ${duration}s"
        echo -e "${BLUE}📄 JSON results:${NC} ${json_file}"
        echo -e "${BLUE}📋 Full log:${NC} ${log_file}"

        # Store results for final matrix
        echo "$bench_name" >> "$RESULTS_DIR/completed-benchmarks.txt"
        grep -E "thrpt.*Score" "$log_file" >> "$RESULTS_DIR/all-results.txt" || true
    else
        echo -e "\n${BOLD}${RED}❌ FAILED${NC}"
        BENCHMARKS_FAILED=$((BENCHMARKS_FAILED + 1))
        FAILED_BENCHMARKS+=("$bench_name")
    fi
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# Clean up previous temporary files
rm -f "$RESULTS_DIR/completed-benchmarks.txt" "$RESULTS_DIR/all-results.txt"

echo -e "${YELLOW}🔥 Starting comparative performance validation...${NC}\n"
echo -e "${YELLOW}Running quick smoke tests with minimal iterations${NC}"
echo -e "${YELLOW}Comparing: Eru vs ZIO vs Cats Effect${NC}\n"

# Core benchmarks that MUST show Eru competitiveness
echo -e "${BOLD}${MAGENTA}📊 PHASE 1: CORE OPERATIONS${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Core Operations (All Systems)" \
    "net.ghoula.eru.bench.fair.CoreOperationsBench" \
    240

echo -e "${YELLOW}Phase 1 complete, continuing to Phase 2...${NC}"

echo -e "\n${BOLD}${MAGENTA}🔥 PHASE 2: ERROR HANDLING${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Error Handling (All Systems)" \
    "net.ghoula.eru.bench.fair.ErrorHandlingBench" \
    240

echo -e "\n${BOLD}${MAGENTA}💾 PHASE 3: STATE MANAGEMENT${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "State Management (All Systems)" \
    "net.ghoula.eru.bench.fair.StateManagementBench" \
    240

echo -e "\n${BOLD}${MAGENTA}⚡ PHASE 4: CONCURRENCY & PARALLELISM${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Concurrency (All Systems)" \
    "net.ghoula.eru.bench.fair.ConcurrencyBench" \
    300

echo -e "\n${BOLD}${MAGENTA}🛡️ PHASE 5: RESOURCE MANAGEMENT${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Resource Management (All Systems)" \
    "net.ghoula.eru.bench.fair.ResourceManagementBench" \
    240

echo -e "\n${BOLD}${MAGENTA}📚 PHASE 6: STACK SAFETY${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Stack Safety (All Systems)" \
    "net.ghoula.eru.bench.fair.StackSafetyBench" \
    240

echo -e "\n${BOLD}${MAGENTA}🎯 PHASE 7: COLLECTION OPERATIONS${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Collections (All Systems)" \
    "net.ghoula.eru.bench.fair.CollectionOperationsBench" \
    240

echo -e "\n${BOLD}${MAGENTA}🔄 PHASE 8: COORDINATION PRIMITIVES${NC}"
echo -e "${MAGENTA}════════════════════════════════════════════════════════════════════════${NC}\n"

run_benchmark "Coordination (All Systems)" \
    "net.ghoula.eru.bench.fair.CoordinationBench" \
    240

# Generate comprehensive result matrix
generate_result_matrix() {
    echo -e "\n${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BOLD}${CYAN}                     📊 PERFORMANCE COMPARISON MATRIX                       ${NC}"
    echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
    
    # Parse and display results for each benchmark category
    for log_file in "$RESULTS_DIR"/*-$TIMESTAMP.log; do
        if [[ -f "$log_file" ]]; then
            category=$(basename "$log_file" | sed "s/-$TIMESTAMP.log//")
            echo -e "${BOLD}${BLUE}$(echo $category | tr '-' ' ' | tr '[:lower:]' '[:upper:]')${NC}"
            echo -e "${CYAN}────────────────────────────────────────────────────────────────────────${NC}"
            
            # Extract scores for each system - fix regex patterns
            eru_scores=$(grep -E "eru.*thrpt" "$log_file" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+\.[0-9]+$/ || $i ~ /^[0-9]+,[0-9]+$/) {gsub(/,/, "", $i); print $i}}')
            zio_scores=$(grep -E "zio.*thrpt" "$log_file" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+\.[0-9]+$/ || $i ~ /^[0-9]+,[0-9]+$/) {gsub(/,/, "", $i); print $i}}')
            io_scores=$(grep -E "(io[A-Z]|IO).*thrpt" "$log_file" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+\.[0-9]+$/ || $i ~ /^[0-9]+,[0-9]+$/) {gsub(/,/, "", $i); print $i}}')
            
            if [[ -n "$eru_scores" || -n "$zio_scores" || -n "$io_scores" ]]; then
                # Calculate averages with fallback to 0
                eru_avg=$(echo "$eru_scores" | awk 'BEGIN{sum=0; count=0} {sum+=$1; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
                zio_avg=$(echo "$zio_scores" | awk 'BEGIN{sum=0; count=0} {sum+=$1; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
                io_avg=$(echo "$io_scores" | awk 'BEGIN{sum=0; count=0} {sum+=$1; count++} END {if(count>0) printf "%.0f", sum/count; else print "0"}')
                
                # Determine winner - convert to numeric for comparison
                max_score=$(echo -e "$eru_avg\n$zio_avg\n$io_avg" | sort -rn | head -1)
                
                if [[ "$eru_avg" == "$max_score" && "$eru_avg" != "0" ]]; then
                    eru_display="${GREEN}${eru_avg}${NC} 🏆"
                    zio_display="$zio_avg"
                    io_display="$io_avg"
                elif [[ "$zio_avg" == "$max_score" && "$zio_avg" != "0" ]]; then
                    eru_display="$eru_avg"
                    zio_display="${GREEN}${zio_avg}${NC} 🏆"
                    io_display="$io_avg"
                elif [[ "$io_avg" != "0" ]]; then
                    eru_display="$eru_avg"
                    zio_display="$zio_avg"
                    io_display="${GREEN}${io_avg}${NC} 🏆"
                else
                    eru_display="$eru_avg"
                    zio_display="$zio_avg"
                    io_display="$io_avg"
                fi
                
                printf "  %-15s: %b ops/ms\n" "Eru" "$eru_display"
                printf "  %-15s: %b ops/ms\n" "ZIO" "$zio_display"
                printf "  %-15s: %b ops/ms\n" "Cats Effect" "$io_display"
            else
                echo "  No results available"
            fi
            echo ""
        fi
    done
}

# Generate summary
generate_result_matrix

echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${CYAN}                         📊 SMOKE TEST SUMMARY                              ${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

echo -e "${BOLD}Benchmarks Run:${NC} $BENCHMARKS_RUN"
echo -e "${BOLD}Failed:${NC} $BENCHMARKS_FAILED"

if [[ $BENCHMARKS_FAILED -eq 0 ]]; then
    echo -e "\n${BOLD}${GREEN}✅ All benchmark categories completed successfully${NC}"
    echo -e "${GREEN}Eru has been validated across all performance categories${NC}"
    echo -e "${GREEN}Check individual logs for detailed performance comparisons${NC}\n"
else
    echo -e "\n${BOLD}${YELLOW}⚠️ Some benchmarks failed:${NC}"
    for failed in "${FAILED_BENCHMARKS[@]}"; do
        echo -e "  ${RED}• $failed${NC}"
    done
    echo -e "\n${YELLOW}Review logs for details${NC}\n"
fi

# Generate markdown summary
cat > "$SUMMARY_FILE" << EOF
# Eru Performance Smoke Test Results

**Date**: $(date)
**Type**: Comparative Smoke Test (Eru vs ZIO vs Cats Effect)

## Summary

- **Total Categories**: 8
- **Successful**: $((BENCHMARKS_RUN - BENCHMARKS_FAILED))
- **Failed**: $BENCHMARKS_FAILED

## Categories Tested

1. Core Operations - Basic effect creation, composition, chaining
2. Error Handling - Failure, recovery, attempt patterns
3. State Management - Ref operations and state updates
4. Concurrency - Parallel execution, racing, forking
5. Resource Management - Bracket, ensure, finalizers
6. Stack Safety - Deep recursion and composition
7. Collection Operations - Traverse, sequence, parallel collections
8. Coordination - Deferred, Semaphore, Promise patterns

## Notes

This is a smoke test with minimal iterations (1 warmup, 2 measurements).
For production benchmarking, use the full benchmark suite with more iterations.

EOF

echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}Report Files:${NC}"
echo -e "  📄 Summary: ${CYAN}$SUMMARY_FILE${NC}"
echo -e "  📁 Detailed Logs: ${CYAN}$RESULTS_DIR/*.log${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

if [[ $BENCHMARKS_FAILED -eq 0 ]]; then
    echo -e "${BOLD}${GREEN}🎉 Smoke test complete - Eru ready for detailed benchmarking!${NC}\n"
    exit 0
else
    echo -e "${BOLD}${YELLOW}⚠️ Review failed benchmarks before proceeding${NC}\n"
    exit 1
fi