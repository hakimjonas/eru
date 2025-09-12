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

# Run each test suite in a separate sbt invocation with aggressive timeouts
# Native tests should complete in under 2 minutes
run_test "Native Tests" "timeout 120s sbt testNative" || OVERALL_SUCCESS=false

# JVM tests may take longer due to concurrency tests, allow 3 minutes
run_test "JVM Tests" "timeout 180s sbt testJVM" || OVERALL_SUCCESS=false  

# Integration tests are lighter, allow 2 minutes
run_test "Integration Tests" "timeout 120s sbt testIntegration" || OVERALL_SUCCESS=false

echo "=== Test Suite Summary ==="
if [ "$OVERALL_SUCCESS" = true ]; then
    echo "✅ ALL TESTS PASSED"
    exit 0
else
    echo "❌ SOME TESTS FAILED"
    exit 1
fi