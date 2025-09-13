#!/bin/bash

# Documentation Validation Script
# This script validates that all documentation examples compile successfully

set -e

echo "🔍 Validating Eru documentation examples..."
echo "================================================"

echo "📚 Compiling all mdoc examples..."
if sbt docs; then
    echo "✅ All documentation examples compile successfully!"
    echo ""
    echo "📊 Generated files:"
    find target/mdoc -name "*.md" | sort
    echo ""
    echo "🎉 Documentation validation passed!"
    echo "   All examples are guaranteed to work with the current Eru version."
else
    echo "❌ Documentation validation failed!"
    echo ""
    echo "Some examples have compilation errors. This means:"
    echo "• Users following the documentation will encounter broken code"
    echo "• The examples may be using non-existent APIs"
    echo "• Type signatures may be incorrect"
    echo ""
    echo "Please fix the errors above before merging."
    exit 1
fi