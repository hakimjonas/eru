# Phase 2: File & Test Naming Audit - Renaming Plan

## Overview

This document tracks the systematic renaming of test files to ensure professional, clear, and appropriately scoped naming conventions.

## Renaming Strategy

### Principles
1. Remove redundant "Eru" prefix where the file clearly tests Eru functionality
2. Simplify "PropertyBased" to "Property" for consistency
3. Simplify "Laws" specifications (e.g., "EruMonadLawsSpec" → "MonadLawsSpec")
4. Keep names descriptive but not overreaching
5. Maintain clear indication of what is being tested

## Files to Rename

### Category 1: Laws Specifications (Remove redundant "Eru" prefix)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruMonadLawsSpec.scala` | `MonadLawsSpec.scala` | Tests monad laws for Eru type - "Eru" prefix is redundant |
| `EruResourceLawsSpec.scala` | `ResourceLawsSpec.scala` | Tests resource laws - "Eru" prefix is redundant |

### Category 2: Property-Based Tests (Consistency)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruPropertyBasedSpec.scala` | `EruPropertySpec.scala` | Match style of `FiberPropertySpec`, `RetryPolicyPropertySpec` |
| `ResultPropertyBasedSpec.scala` | `ResultPropertySpec.scala` | Match style of other property specs |

### Category 3: Verbose Names (Simplification)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruResourceSafetyExtensionsSpec.scala` | `ResourceSafetySpec.scala` | Shorter, clearer - tests resource safety functionality |

### Category 4: Observer Specs (Clarification Needed)

**Issue**: Two observer specs with unclear differentiation:
- `EruObserverSpec.scala` - Claims to be "comprehensive test suite"
- `EruObserverComprehensiveSpec.scala` - Tests "functionality and integration behavior"

**Decision**: Keep both for now, but consider renaming `EruObserverComprehensiveSpec` to better reflect its focus (e.g., `EruObserverIntegrationSpec` or `EruObserverBehaviorSpec`)

**Action**: Investigate further before renaming

## Files That Look Good (No Changes)

### Well-Named Test Files
- `FiberPropertySpec.scala` - Consistent "Property" style
- `RetryPolicyPropertySpec.scala` - Consistent "Property" style
- `ExtremeStackSafetySpec.scala` - Descriptive and appropriate
- `FiberInterruptionSpec.scala` - Clear focus
- `StructuredConcurrencySpec.scala` - Clear focus
- All integration test specs - Clear and professional

### Benchmark Files
All benchmark file names are appropriate:
- Focus on what is measured (CoreOperationsBench, ConcurrencyBench)
- No comparative claims in names
- Professional and descriptive

## Implementation Order

1. **Category 1**: Laws specifications (2 files)
2. **Category 2**: Property-based tests (2 files)
3. **Category 3**: Verbose names (1 file)
4. **Category 4**: Observer specs (deferred - needs investigation)

## Testing Strategy

After each category of renames:
1. Update imports in related test files
2. Verify compilation: `sbt eruCoreJVM/Test/compile`
3. Run affected tests
4. Check for any missed references

Final validation:
- `sbt testJVM` - All JVM tests
- `sbt testNative` - All Native tests
- `sbt testIntegration` - Integration tests

## Potential Issues to Watch

1. **ScalaDoc references**: Check if any documentation refers to old class names
2. **Build configuration**: Check if `build.sbt` has any hardcoded test references
3. **IDE/tooling**: May need to reload project after renames
4. **Git history**: File renames will show as delete + add (this is fine)

## Summary

**Total files to rename**: 5 files (initially)
- Laws specs: 2
- Property specs: 2
- Verbose names: 1

**Files deferred**: 1 (Observer comprehensive spec - needs investigation)

This is a conservative, focused approach that improves clarity without being disruptive.
