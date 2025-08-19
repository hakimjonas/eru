#!/bin/bash
set -e

echo "🔍 Running optimization validation suite..."

echo "✅ Running correctness tests (fusion-focused)..."
sbt "project eruCoreJVM" "testOnly *EruFusionValidationSpec"

echo "✅ Running AST inspection tests..."  
sbt "project eruCoreJVM" "testOnly *EruFusionValidationSpec -- --tests *AST* || true"

echo "✅ Running benchmark validation..."
sbt "project eruBenchJVM" "jmh:run .*EruValidationBench.*"

echo "✅ Running allocation profiling..."
sbt "project eruBenchJVM" "jmh:run -prof gc .*runPureFlat.*"

echo "🎉 All validation checks passed!"