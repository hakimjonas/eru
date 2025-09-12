#!/bin/bash

# Phase 2: Memory & GC Analysis Benchmark Runner
# 
# Runs comprehensive memory and garbage collection benchmarks for Eru vs ZIO vs Cats Effect
# Focus on allocation rates, GC pressure, and memory efficiency patterns

set -e

echo "🧠 Phase 2: Memory & GC Analysis Benchmarks"
echo "=========================================="
echo ""

# Configuration
TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)
RESULTS_DIR="benchmark-results/phase2-memory"
RESULTS_FILE="$RESULTS_DIR/memory-analysis-$TIMESTAMP.json"
SUMMARY_FILE="$RESULTS_DIR/memory-summary-$TIMESTAMP.md"

# Ensure results directory exists
mkdir -p $RESULTS_DIR

# JVM settings optimized for memory analysis
JVM_OPTS=(
    "-Xms4g"                          # Start with 4GB heap
    "-Xmx8g"                          # Max 8GB heap for memory pressure testing
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+UseG1GC"                    # G1 for low-latency GC
    "-XX:MaxGCPauseMillis=200"        # Target max GC pause
    "-XX:+PrintGC"                    # Enable GC logging
    "-XX:+PrintGCDetails"
    "-XX:+PrintGCTimeStamps"
    "-XX:+PrintGCApplicationStoppedTime"
    "-XX:+LogVMOutput"
    "-XX:LogFile=gc-$TIMESTAMP.log"
    "-Deru.benchmark.memory.verbose=true"  # Enable memory metrics logging
)

echo "📊 Memory Benchmark Configuration:"
echo "  - Heap Size: 4GB-8GB"
echo "  - GC: G1 with detailed logging"
echo "  - Memory tracking: Enabled"
echo "  - Results: $RESULTS_FILE"
echo ""

# Function to run a specific benchmark class
run_benchmark_class() {
    local bench_class=$1
    local description=$2
    local output_file="$RESULTS_DIR/${bench_class,,}-$TIMESTAMP.json"
    
    echo "🔬 Running $description..."
    echo "   Class: $bench_class"
    echo "   Output: $output_file"
    
    sbt "eruBenchJVM/Jmh/run -prof gc -rf json -rff $output_file -i 3 -wi 2 -f1 -t1 $bench_class" \
        -J-Xms4g -J-Xmx8g \
        "${JVM_OPTS[@]/#/-J}" || {
        echo "❌ Failed to run $bench_class"
        return 1
    }
    
    echo "✅ Completed $description"
    echo ""
}

echo "🚀 Starting Memory & GC Analysis Benchmarks..."
echo ""

# Run each memory benchmark class
run_benchmark_class "AllocationRateBench" "Memory Allocation Rate Analysis"
run_benchmark_class "GCPressureBench" "Garbage Collection Pressure Testing"  
run_benchmark_class "MemoryEfficiencyBench" "Memory Efficiency Patterns"

echo "📈 Generating Memory Analysis Summary..."

# Create comprehensive summary report
cat > $SUMMARY_FILE << 'EOF'
# Phase 2: Memory & GC Analysis Results

## Overview

This report presents memory allocation and garbage collection analysis for Eru, ZIO, and Cats Effect across multiple scenarios.

## Key Metrics Measured

### 1. Allocation Rate Analysis
- **Bytes allocated per operation** - Direct memory efficiency measurement
- **Chain allocation patterns** - How allocation scales with effect composition
- **Collection processing** - Memory usage for data transformation operations
- **Resource management overhead** - Allocation cost of resource lifecycle
- **Error handling allocation** - Memory cost of error recovery patterns
- **State management efficiency** - Allocation overhead of concurrent state

### 2. GC Pressure Testing  
- **High allocation rate performance** - Behavior under memory pressure
- **Parallel allocation patterns** - Concurrent memory allocation efficiency
- **Long-running process behavior** - Memory usage over extended execution
- **Memory-intensive state operations** - GC impact of large state management
- **Post-GC recovery performance** - Performance characteristics after GC events

### 3. Memory Efficiency Patterns
- **Zero-allocation scenarios** - Identifying minimal allocation operations
- **Efficient looping patterns** - Memory-friendly iteration strategies
- **Efficient error handling** - Low-overhead error recovery
- **Minimal state management** - Lightweight state operation patterns
- **Efficient resource management** - Low-allocation resource lifecycle
- **Stack safety with minimal allocation** - Memory-safe recursive patterns

## Analysis Framework

The benchmarks use custom memory tracking infrastructure that captures:
- Heap usage before and after operations
- GC event counts and timing
- Memory allocation rates per operation
- GC overhead and pause times

This provides detailed insight into the memory characteristics of each effect system under various operational patterns.

## Expected Insights

### Eru Advantages
- **Zero-allocation interpretation** should show minimal allocation per operation
- **Efficient effect composition** with reduced allocation overhead
- **Optimized resource management** with automatic cleanup
- **Stack-safe operations** without allocation penalties

### Comparative Analysis
- **Allocation efficiency** across different operation types
- **GC behavior** under memory pressure scenarios  
- **Memory scalability** with increasing operation complexity
- **Resource lifecycle costs** for different management patterns

EOF

echo "📋 Memory benchmark results summary created: $SUMMARY_FILE"
echo "📁 Detailed results available in: $RESULTS_DIR/"
echo "📄 GC logs: gc-$TIMESTAMP.log"
echo ""
echo "✨ Phase 2: Memory & GC Analysis Complete!"
echo ""
echo "🔍 Next Steps:"
echo "  1. Analyze allocation rate differentials"  
echo "  2. Review GC pressure behavior patterns"
echo "  3. Identify memory efficiency opportunities"
echo "  4. Proceed to Phase 3: Native Platform benchmarks"