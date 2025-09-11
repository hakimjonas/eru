#!/bin/bash

# Simple bash script to check for the exact problematic patterns we discovered
# These patterns caused the hanging test issues

echo "🔍 Checking for critical global state patterns..."

EXIT_CODE=0

# Check for the specific dangerous patterns we found
echo "Checking for AtomicLong(1L) patterns..."
if grep -rn "AtomicLong(1L)" eru-core eru-runtime --include="*.scala" | grep -v "// Use process-unique"; then
    echo "❌ CRITICAL: Found hardcoded AtomicLong(1L) - these cause resource contention!"
    EXIT_CODE=1
fi

echo "Checking for AtomicLong(1) patterns..."
if grep -rn "AtomicLong\(1\)" eru-core eru-runtime --include="*.scala" | grep -v "// Use process-unique"; then
    echo "❌ CRITICAL: Found hardcoded AtomicLong(1) - these cause resource contention!"
    EXIT_CODE=1
fi

echo "Checking for private val next = new java.util.concurrent.atomic.AtomicLong..."
if grep -rn "private val next = new java.util.concurrent.atomic.AtomicLong" eru-core eru-runtime --include="*.scala" | grep -v "processUniqueStart"; then
    echo "❌ CRITICAL: Found global 'next' counter - these cause resource contention!"
    EXIT_CODE=1
fi

echo "Checking for private val counter = new AtomicLong..."
if grep -rn "private val counter = new AtomicLong" eru-core eru-runtime --include="*.scala" | grep -v "processUniqueStart"; then
    echo "❌ CRITICAL: Found global 'counter' - these cause resource contention!"
    EXIT_CODE=1
fi

if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ No critical global state patterns detected!"
    echo "✅ All atomic counters appear to use process-unique starting points"
else
    echo ""
    echo "❌ CRITICAL ISSUES FOUND!"
    echo "These patterns can cause resource contention between multiple Eru applications."
    echo "See ARCHITECTURE-SAFEGUARDS.md for how to fix them."
fi

exit $EXIT_CODE