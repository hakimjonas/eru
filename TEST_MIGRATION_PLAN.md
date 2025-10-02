# Test Migration Plan for Suspension Type System

## Summary
With the new suspension type system, many existing tests are outdated or testing patterns that are no longer relevant. This document outlines which tests to keep, modify, or remove.

## Tests to Remove or Significantly Rewrite

### 1. CollectAllDeadlockSpec
**Status**: Remove or completely rewrite
**Reason**: This test was demonstrating deadlocks that can no longer be written at all. The compile-time safety makes the test's purpose obsolete.
**Action**: Either remove entirely or replace with a test that demonstrates the compile-time safety benefits.

### 2. QueueMigrationSpec (if exists)
**Status**: Remove
**Reason**: Was testing migration from old to new system, no longer needed.

## Tests to Simplify

### 3. PromiseConcurrencySpec, QueueConcurrencySpec, HubConcurrencySpec
**Status**: Simplify
**Reason**: Many of these tests are overly complex, testing coordination patterns that are now guaranteed safe by the type system.
**Action**:
- Keep basic concurrency tests
- Remove complex deadlock prevention tests (now prevented at compile time)
- Focus on testing actual functionality rather than safety

### 4. CoordinationConcurrencySpec
**Status**: Simplify
**Reason**: Complex coordination is now safer, tests can be simpler
**Action**: Keep basic coordination tests, remove timeout/deadlock checks

## Tests to Fix (Minor Updates Only)

### 5. Core functionality tests (QueueSpec, PromiseSpec, SemaphoreSpec, etc.)
**Status**: Fix compilation with .eru
**Reason**: These test actual functionality and just need syntax updates
**Action**: Add .eru accessors where needed

### 6. RuntimeHealthCheck, RuntimeExtensionsSpec
**Status**: Fix compilation
**Reason**: These test runtime behavior that's still relevant
**Action**: Update to use new suspension types correctly

## New Tests to Add

### 7. SuspensionSystemSpec (Already created)
**Purpose**: Comprehensive test of the new suspension type system
**Coverage**:
- Demonstrates compile-time safety
- Shows proper usage patterns
- Tests all primitives with new types
- Documents prevented deadlock patterns

### 8. SuspensionCompileTimeSpec (Consider adding)
**Purpose**: Use compile-time testing to verify that bad patterns don't compile
**Technique**: Use `shapeless.test.illTyped` or similar to test compilation failures

## Migration Strategy

1. **Phase 1**: Remove/disable tests that no longer make sense
   - CollectAllDeadlockSpec
   - Any migration tests

2. **Phase 2**: Fix simple compilation errors in core tests
   - Add .eru accessors
   - Update to use tryX methods where appropriate
   - Fix type comparisons in assertions

3. **Phase 3**: Simplify overly complex concurrency tests
   - Remove deadlock prevention checks (now compile-time)
   - Focus on functionality over safety demonstration

4. **Phase 4**: Add new comprehensive tests
   - SuspensionSystemSpec (done)
   - Compile-time safety tests

## Key Principles for Test Updates

1. **Don't test what the compiler already prevents** - No need for runtime deadlock tests
2. **Focus on functionality** - Test that operations work correctly, not that they're safe
3. **Demonstrate the benefits** - Show how the new system makes code safer and cleaner
4. **Keep it simple** - Complex coordination patterns are now safe by default

## Estimated Impact

- **Tests to remove**: ~2-3 files
- **Tests to simplify**: ~5-6 files
- **Tests to fix**: ~10-15 files
- **New tests**: 1-2 comprehensive suites

This approach will result in a cleaner, more focused test suite that properly validates the new suspension type system while removing obsolete safety checks that are now compile-time guaranteed.