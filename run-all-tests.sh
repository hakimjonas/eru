#!/bin/bash

# Test runner that executes all test suites in separate JVM instances
# to prevent resource contention and hanging issues

echo "=== Running Eru Test Suite (Isolated) ==="
echo ""

# Function to run a command and report success/failure
run_test() {
    local name="$1"
    local command="$2"
    echo "🧪 Running $name..."
    
    if eval "$command"; then
        echo "✅ $name PASSED"
        echo ""
        return 0
    else
        echo "❌ $name FAILED"
        echo ""
        return 1
    fi
}

# Track overall success
OVERALL_SUCCESS=true

# Run each test suite in a separate sbt invocation with realistic timeouts
# Based on successful run: JVM ~2.15min, Native ~4.13min total
# Native tests include compilation time which can be significant
run_test "Native Core Tests" "timeout 180s sbt eruCoreNative/test" || OVERALL_SUCCESS=false
run_test "Native Runtime Tests" "timeout 240s sbt eruRuntimeNative/test" || OVERALL_SUCCESS=false

# JVM tests split for better isolation (concurrency tests can hang)
run_test "JVM Core Tests" "timeout 120s sbt eruCoreJVM/test" || OVERALL_SUCCESS=false
run_test "JVM Runtime Tests" "timeout 180s sbt eruRuntimeJVM/test" || OVERALL_SUCCESS=false

# Integration tests are lighter, allow 2 minutes
run_test "Integration Tests" "timeout 120s sbt eruIntegrationTest/test" || OVERALL_SUCCESS=false

echo "=== Test Suite Summary ==="
if [ "$OVERALL_SUCCESS" = true ]; then
    echo "✅ ALL TESTS PASSED"
    exit 0
else
    echo "❌ SOME TESTS FAILED"
    exit 1
fi