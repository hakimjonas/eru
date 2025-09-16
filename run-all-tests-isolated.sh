#!/bin/bash

# NUCLEAR OPTION: Complete test isolation with individual JVM processes
# Every single test file runs in its own sbt invocation for maximum isolation
# This eliminates ANY possibility of test interaction or hanging issues

echo "=== Running Eru Test Suite (Nuclear Isolation) ==="
echo ""

# Function to run a single test file in complete isolation
run_isolated_test() {
    local name="$1"
    local test_class="$2"
    echo "🧪 Running $name..."

    if timeout 60s sbt "eruRuntimeJVM/testOnly $test_class" > /dev/null 2>&1; then
        echo "✅ $name PASSED"
        return 0
    else
        echo "❌ $name FAILED or TIMED OUT"
        return 1
    fi
}

# Track overall success
OVERALL_SUCCESS=true

echo "=== JVM Core Tests ==="
timeout 120s sbt eruCoreJVM/test > /dev/null 2>&1 && echo "✅ JVM Core PASSED" || { echo "❌ JVM Core FAILED"; OVERALL_SUCCESS=false; }

echo ""
echo "=== JVM Runtime Tests (Individual Isolation) ==="

# Shared runtime tests (these are also included in JVM runtime)
run_isolated_test "PromiseSpec" "net.ghoula.eru.PromiseSpec" || OVERALL_SUCCESS=false
run_isolated_test "DeferredSpec" "net.ghoula.eru.DeferredSpec" || OVERALL_SUCCESS=false
run_isolated_test "QueueSpec" "net.ghoula.eru.QueueSpec" || OVERALL_SUCCESS=false
run_isolated_test "CountDownLatchSpec" "net.ghoula.eru.CountDownLatchSpec" || OVERALL_SUCCESS=false
run_isolated_test "CyclicBarrierSpec" "net.ghoula.eru.CyclicBarrierSpec" || OVERALL_SUCCESS=false
run_isolated_test "HubSpec" "net.ghoula.eru.HubSpec" || OVERALL_SUCCESS=false

# JVM-specific runtime tests
run_isolated_test "ParallelSpec" "net.ghoula.eru.ParallelSpec" || OVERALL_SUCCESS=false
run_isolated_test "QueueAsyncSpec" "net.ghoula.eru.QueueAsyncSpec" || OVERALL_SUCCESS=false
run_isolated_test "QueueConcurrencySpec" "net.ghoula.eru.QueueConcurrencySpec" || OVERALL_SUCCESS=false
run_isolated_test "CollectAllDeadlockSpec" "net.ghoula.eru.CollectAllDeadlockSpec" || OVERALL_SUCCESS=false
run_isolated_test "CoordinationConcurrencySpec" "net.ghoula.eru.CoordinationConcurrencySpec" || OVERALL_SUCCESS=false
run_isolated_test "PromiseConcurrencySpec" "net.ghoula.eru.PromiseConcurrencySpec" || OVERALL_SUCCESS=false
run_isolated_test "HubConcurrencySpec" "net.ghoula.eru.HubConcurrencySpec" || OVERALL_SUCCESS=false
run_isolated_test "ConcurrencyStressSpec" "net.ghoula.eru.ConcurrencyStressSpec" || OVERALL_SUCCESS=false
run_isolated_test "TimersSpec" "net.ghoula.eru.TimersSpec" || OVERALL_SUCCESS=false
run_isolated_test "SuspendSpec" "net.ghoula.eru.SuspendSpec" || OVERALL_SUCCESS=false
run_isolated_test "VTForkSpec" "net.ghoula.eru.VTForkSpec" || OVERALL_SUCCESS=false
run_isolated_test "RetryPolicyPropertySpec" "net.ghoula.eru.RetryPolicyPropertySpec" || OVERALL_SUCCESS=false
run_isolated_test "ParallelDegreeLimitedSpec" "net.ghoula.eru.ParallelDegreeLimitedSpec" || OVERALL_SUCCESS=false
run_isolated_test "ValidationPatternsSpec" "net.ghoula.eru.ValidationPatternsSpec" || OVERALL_SUCCESS=false
run_isolated_test "RuntimeHealthCheck" "net.ghoula.eru.RuntimeHealthCheck" || OVERALL_SUCCESS=false

# Fiber tests - each in complete isolation
run_isolated_test "FiberStressSpec" "net.ghoula.eru.fiber.FiberStressSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberExecutionSpec" "net.ghoula.eru.fiber.FiberExecutionSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberErrorPropagationSpec" "net.ghoula.eru.fiber.FiberErrorPropagationSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberInterruptionSpec" "net.ghoula.eru.fiber.FiberInterruptionSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberFinalizerIntegrationSpec" "net.ghoula.eru.fiber.FiberFinalizerIntegrationSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberLifecycleSpec" "net.ghoula.eru.fiber.FiberLifecycleSpec" || OVERALL_SUCCESS=false
run_isolated_test "FiberPropertySpec" "net.ghoula.eru.fiber.FiberPropertySpec" || OVERALL_SUCCESS=false

echo ""
echo "=== Native Tests ==="
timeout 180s sbt eruCoreNative/test > /dev/null 2>&1 && echo "✅ Native Core PASSED" || { echo "❌ Native Core FAILED"; OVERALL_SUCCESS=false; }
timeout 240s sbt eruRuntimeNative/test > /dev/null 2>&1 && echo "✅ Native Runtime PASSED" || { echo "❌ Native Runtime FAILED"; OVERALL_SUCCESS=false; }

echo ""
echo "=== Integration Tests ==="
timeout 120s sbt eruIntegrationTest/test > /dev/null 2>&1 && echo "✅ Integration PASSED" || { echo "❌ Integration FAILED"; OVERALL_SUCCESS=false; }

echo ""
echo "=== Test Suite Summary ==="
if [ "$OVERALL_SUCCESS" = true ]; then
    echo "✅ ALL TESTS PASSED (Nuclear Isolation)"
    exit 0
else
    echo "❌ SOME TESTS FAILED"
    exit 1
fi