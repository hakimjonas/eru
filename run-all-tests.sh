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

# JVM tests with targeted isolation only for problematic specs
run_test "JVM Core Tests" "timeout 120s sbt eruCoreJVM/test" || OVERALL_SUCCESS=false

# JVM Runtime tests - each in individual sbt isolation for maximum reliability
echo ""
echo "=== JVM Runtime Tests (Individual Isolation) ==="
run_test "JVM Runtime - PromiseSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.PromiseSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - DeferredSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.DeferredSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - QueueSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.QueueSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - CountDownLatchSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.CountDownLatchSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - CyclicBarrierSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.CyclicBarrierSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - HubSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.HubSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - ParallelSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.ParallelSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - QueueAsyncSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.QueueAsyncSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - CollectAllDeadlockSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.CollectAllDeadlockSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - CoordinationConcurrencySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.CoordinationConcurrencySpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - PromiseConcurrencySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.PromiseConcurrencySpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - HubConcurrencySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.HubConcurrencySpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - QueueConcurrencySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.QueueConcurrencySpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - ConcurrencyStressSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.ConcurrencyStressSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - TimersSpec" "timeout 90s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.TimersSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - SuspendSpec" "timeout 90s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.SuspendSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - VTForkSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.VTForkSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - RetryPolicyPropertySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.RetryPolicyPropertySpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - ParallelDegreeLimitedSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.ParallelDegreeLimitedSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - ValidationPatternsSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.ValidationPatternsSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - RuntimeHealthCheck" "timeout 30s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.RuntimeHealthCheck'" || OVERALL_SUCCESS=false

echo ""
echo "=== JVM Runtime Fiber Tests (Individual Isolation) ==="
run_test "JVM Runtime - FiberStressSpec" "timeout 90s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberStressSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - FiberExecutionSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberExecutionSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - FiberErrorPropagationSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberErrorPropagationSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - FiberInterruptionSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberInterruptionSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - FiberLifecycleSpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberLifecycleSpec'" || OVERALL_SUCCESS=false
run_test "JVM Runtime - FiberPropertySpec" "timeout 60s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberPropertySpec'" || OVERALL_SUCCESS=false

# Finalizer integration test - rewritten for correctness and reliability
run_test "JVM Runtime - FiberFinalizerIntegrationSpec" "timeout 90s sbt 'eruRuntimeJVM/testOnly net.ghoula.eru.fiber.FiberFinalizerIntegrationSpec'" || OVERALL_SUCCESS=false

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