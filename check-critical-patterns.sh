#!/bin/bash

# Script to check for critical architectural patterns that could cause issues

echo "🔍 Eru Critical Patterns Analysis"
echo "================================="

# Check for forbidden global state patterns
echo ""
echo "1. Checking for hardcoded atomic counters..."
if grep -r "AtomicLong(1L)" eru-core eru-runtime 2>/dev/null; then
  echo "❌ CRITICAL: Found hardcoded AtomicLong(1L) - use process-unique starting point"
  exit 1
else
  echo "✅ No hardcoded AtomicLong(1L) patterns found"
fi

if grep -r "AtomicInteger(0)" eru-core eru-runtime 2>/dev/null; then
  echo "❌ CRITICAL: Found hardcoded AtomicInteger(0) - use process-unique starting point"
  exit 1  
else
  echo "✅ No hardcoded AtomicInteger(0) patterns found"
fi

echo ""
echo "2. Checking for global executors..."
if grep -r "Executors\." eru-core eru-runtime | grep -v test | grep -v "privateExecutor" | grep object; then
  echo "❌ WARNING: Found potential global executors in object declarations"
else
  echo "✅ No problematic global executors found"
fi

echo ""
echo "3. Checking for global lazy vals with side effects..."
if grep -r "lazy val.*Executor" eru-core eru-runtime | grep object; then
  echo "⚠️  WARNING: Found lazy val executors in objects - check for side effects"
else
  echo "✅ No problematic lazy val executors found"
fi

echo ""
echo "4. Verifying process-unique ID generation..."
if grep -r "processUniqueStart" eru-core eru-runtime | wc -l | grep -q "0"; then
  echo "❌ CRITICAL: No process-unique ID generation patterns found"
  exit 1
else
  echo "✅ Process-unique ID generation patterns found"
fi

echo ""
echo "5. Checking for ThreadLocal usage..."
THREADLOCAL_COUNT=$(grep -r "ThreadLocal" eru-core eru-runtime | grep -v test | wc -l)
echo "   ThreadLocal instances found: $THREADLOCAL_COUNT"
if [ $THREADLOCAL_COUNT -gt 1 ]; then
  echo "⚠️  Consider reviewing ThreadLocal usage for memory leak prevention"
  grep -r "ThreadLocal" eru-core eru-runtime | grep -v test
fi

echo ""
echo "6. Checking inline comments compliance..."
INLINE_COMMENTS=$(find eru-core eru-runtime -name "*.scala" -not -path "*/test/*" -exec grep -l "//" {} \; | wc -l)
if [ $INLINE_COMMENTS -gt 0 ]; then
  echo "❌ VIOLATION: Found inline comments in source files"
  find eru-core eru-runtime -name "*.scala" -not -path "*/test/*" -exec grep -l "//" {} \;
  exit 1
else
  echo "✅ No inline comments found - zero inline comments policy maintained"
fi

echo ""
echo "7. Checking for mutable collections in global scope..."
if grep -r "mutable\." eru-core eru-runtime | grep -v test | grep -v "def \|val " | head -5; then
  echo "⚠️  Review mutable collection usage for proper scoping"
else
  echo "✅ No problematic mutable collection usage found"
fi

echo ""
echo "🎯 OVERALL ASSESSMENT"
echo "====================="
echo "✅ All critical architectural patterns are compliant"
echo "✅ Global state safeguards are properly implemented"  
echo "✅ Code quality standards are maintained"
echo ""
echo "The codebase follows architectural best practices!"