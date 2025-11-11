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

### Category 1: CRITICAL - Remove Overreaching Claims

**PRIORITY**: These files make bold mathematical claims that invite scrutiny

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `MathematicallyCorrectStructuredConcurrencyTest.scala` | `StructuredConcurrencyPropertySpec.scala` | Remove "MathematicallyCorrect" claim + standardize to Spec + indicate property-based nature |
| `FinalizerInterruptionMathematicalTest.scala` | `FinalizerInterruptionPropertySpec.scala` | Remove "Mathematical" claim + standardize to Spec + indicate property-based nature |

**Impact**: These are exactly the type of names that could invite ridicule if there's a single error. The tests verify important properties but don't need to make such bold claims in their file names.

### Category 2: Standardize Test → Spec for Consistency

**PRIORITY**: All test files should end with `Spec.scala` for consistency

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `MinimalTest.scala` | `MinimalSpec.scala` | Standardize to Spec suffix |

### Category 3: Laws Specifications (Remove redundant "Eru" prefix)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruMonadLawsSpec.scala` | `MonadLawsSpec.scala` | Tests monad laws for Eru type - "Eru" prefix is redundant |
| `EruResourceLawsSpec.scala` | `ResourceLawsSpec.scala` | Tests resource laws - "Eru" prefix is redundant |

### Category 4: Property-Based Tests (Consistency)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruPropertyBasedSpec.scala` | `EruPropertySpec.scala` | Match style of `FiberPropertySpec`, `RetryPolicyPropertySpec` |
| `ResultPropertyBasedSpec.scala` | `ResultPropertySpec.scala` | Match style of other property specs |

### Category 5: Verbose Names (Simplification)

| Current Name | New Name | Rationale |
|--------------|----------|-----------|
| `EruResourceSafetyExtensionsSpec.scala` | `ResourceSafetySpec.scala` | Shorter, clearer - tests resource safety functionality |

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

1. **Category 1 (CRITICAL)**: Remove mathematical claims (2 files) - **HIGHEST PRIORITY**
2. **Category 2**: Standardize Test → Spec (1 file)
3. **Category 3**: Laws specifications (2 files)
4. **Category 4**: Property-based tests (2 files)
5. **Category 5**: Verbose names (1 file)

## Testing Strategy

After each category of renames:
1. Update imports in related test files
2. Verify compilation: `sbt eruIntegrationTest/Test/compile` (for Category 1 & 2)
3. Verify compilation: `sbt eruCoreJVM/Test/compile` (for Category 3, 4, 5)
4. Run affected tests
5. Check for any missed references

Final validation:
- `sbt testJVM` - All JVM tests
- `sbt testNative` - All Native tests
- `sbt testIntegration` - Integration tests

## Potential Issues to Watch

1. **ScalaDoc references**: Check if any documentation refers to old class names
2. **Build configuration**: Check if `build.sbt` has any hardcoded test references
3. **IDE/tooling**: May need to reload project after renames
4. **Git history**: File renames will show as delete + add (this is fine)
5. **Package references**: The mathematical test files are in `userland` package - check for cross-references

## Summary

**Total files to rename**: 8 files
- **CRITICAL - Mathematical claims**: 2 files
- **Consistency - Test→Spec**: 1 file
- **Simplification - Laws specs**: 2 files
- **Consistency - Property specs**: 2 files
- **Simplification - Verbose names**: 1 file

**Key Achievement**: Removed all overreaching claims ("MathematicallyCorrect", "Mathematical") from file names while maintaining clear indication of property-based testing where appropriate.

This focused approach eliminates the most problematic names while improving overall consistency.
