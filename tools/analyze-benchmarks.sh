#!/bin/bash

# Benchmark Analysis Tool for Eru
# Quickly analyze and summarize benchmark results

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# Find the latest benchmark results by timestamp
if [ -z "$1" ]; then
    echo "Usage: $0 <timestamp> or 'latest'"
    echo "Example: $0 2025-09-28_08-55-33"
    echo "Example: $0 latest"
    exit 1
fi

TIMESTAMP="$1"
if [ "$TIMESTAMP" == "latest" ]; then
    # Find the most recent timestamp from system-info files
    TIMESTAMP=$(ls -t benchmark-results/system-info-*.json 2>/dev/null | head -1 | sed 's/.*system-info-//' | sed 's/.json//')
    if [ -z "$TIMESTAMP" ]; then
        echo "No benchmark results found"
        exit 1
    fi
    echo -e "${YELLOW}Using latest benchmark: ${TIMESTAMP}${NC}"
fi

echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}${BLUE}                 ERU BENCHMARK ANALYSIS - ${TIMESTAMP}${NC}"
echo -e "${BOLD}${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"

# Function to extract and compare benchmark results
analyze_category() {
    local category="$1"
    local file="benchmark-results/${category}-${TIMESTAMP}.json"

    if [ ! -f "$file" ]; then
        echo -e "${RED}Missing: ${category}${NC}"
        return
    fi

    echo -e "${BOLD}${category}:${NC}"

    # Extract key metrics using jq
    if command -v jq &> /dev/null; then
        # Get Eru, ZIO, and Cats Effect scores
        eru_scores=$(jq -r '.[] | select(.benchmark | contains("eru")) | "\(.benchmark | split(".")[-1]): \(.primaryMetric.score | tostring[0:8]) ops/ms"' "$file" 2>/dev/null | head -3)
        zio_scores=$(jq -r '.[] | select(.benchmark | contains("zio")) | "\(.benchmark | split(".")[-1]): \(.primaryMetric.score | tostring[0:8]) ops/ms"' "$file" 2>/dev/null | head -3)

        if [ ! -z "$eru_scores" ]; then
            echo -e "  ${GREEN}Eru:${NC}"
            echo "$eru_scores" | sed 's/^/    /'
        fi

        if [ ! -z "$zio_scores" ]; then
            echo -e "  ZIO:"
            echo "$zio_scores" | sed 's/^/    /'
        fi
    else
        # Fallback without jq - just show that results exist
        echo "  Results saved (install jq for detailed analysis)"
    fi
    echo ""
}

# Analyze each category
for category in core-operations error-handling concurrency collection-operations state-management resource-management stack-safety coordination; do
    analyze_category "$category"
done

# Summary statistics
echo -e "${BOLD}${GREEN}Summary:${NC}"
total_files=$(ls benchmark-results/*-${TIMESTAMP}.json 2>/dev/null | wc -l)
echo "  Total benchmark files: $total_files"
echo "  Timestamp: $TIMESTAMP"

if [ -f "benchmark-results/system-info-${TIMESTAMP}.json" ]; then
    if command -v jq &> /dev/null; then
        jvm_version=$(jq -r '.jvm.version' "benchmark-results/system-info-${TIMESTAMP}.json" 2>/dev/null)
        cpu_model=$(jq -r '.cpu.model' "benchmark-results/system-info-${TIMESTAMP}.json" 2>/dev/null | head -1)
        echo "  JVM: $jvm_version"
        echo "  CPU: $cpu_model"
    fi
fi

echo -e "\n${BOLD}Quick Performance Indicators:${NC}"
echo "  🚀 Concurrency: Check fork/await and zipPar scores"
echo "  ⚡ Core Ops: Check succeed and flatMap scores"
echo "  🛡️ Error Handling: Check attempt and recover scores"
echo "  📊 Collections: Check traverse and parallel traverse scores"

echo -e "\n${BOLD}For detailed analysis:${NC}"
echo "  - View JSON files: benchmark-results/*-${TIMESTAMP}.json"
echo "  - View logs: benchmark-results/*-${TIMESTAMP}.log"
echo "  - Compare with previous runs using timestamps"