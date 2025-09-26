# Queue Migration Plan: From Java Utilities to Pure Eru Primitives

## Executive Summary

We've successfully created a gold standard Queue implementation built entirely on Eru's own primitives (Ref and Promise), eliminating the dependency on Java's concurrent utilities. This document outlines the migration strategy.

## Current State

### Old Queue (`Queue.scala`)
- Uses `ConcurrentLinkedQueue` from Java
- Manual callback management
- No type-safe suspension encoding
- Methods: `offer`, `take`, `poll`, `size`, `isEmpty`

### New Queue (`GoldStandardQueue.scala` + `GoldStandardQueueImpl.scala`)
- Built purely on Eru primitives (Ref + Promise)
- Type-safe suspension encoding with `CanSuspend`/`NoSuspend`
- Comprehensive API with blocking/non-blocking/timeout variants
- Methods follow clear naming convention: `put`/`tryPut`/`putWithin`

## Migration Strategy

### Phase 1: Validation (CURRENT)
- [x] Implement gold standard Queue with pure functional state
- [x] Add suspension type markers
- [x] Create comprehensive API
- [x] Verify compilation
- [ ] Fix test helper compilation issues
- [ ] Run comprehensive test suite

### Phase 2: Parallel Existence
1. Keep both implementations temporarily
2. Add adapter trait to bridge APIs:
```scala
trait QueueAdapter[A] extends Queue[A] {
  def underlying: GoldStandardQueue[A]

  // Bridge old API to new
  def offer(a: A): Eru[Nothing, Unit] = underlying.put(a)
  def take: Eru[Nothing, A] = underlying.take
  def poll: Eru[Nothing, Option[A]] = underlying.tryTake
  // etc.
}
```

### Phase 3: Incremental Migration
1. Find all usages of old Queue
2. Update each usage to use new API patterns
3. Key changes:
   - `offer` → `put` (for blocking) or `tryPut` (for non-blocking)
   - `poll` → `tryTake`
   - Add proper suspension handling where needed

### Phase 4: Replacement
1. Rename `GoldStandardQueue` → `Queue`
2. Move old Queue to `LegacyQueue` (deprecated)
3. Update imports across codebase
4. Remove factory methods from old Queue object

### Phase 5: Cleanup
1. Remove `LegacyQueue` after deprecation period
2. Remove adapter traits
3. Clean up test helpers

## API Mapping

| Old Queue | New Queue | Suspension Type |
|-----------|-----------|-----------------|
| `offer(a)` | `put(a)` | CanSuspend |
| N/A | `tryPut(a)` | NoSuspend |
| `take` | `take` | CanSuspend |
| `poll` | `tryTake` | NoSuspend |
| N/A | `putWithin(a, timeout)` | NoSuspend |
| N/A | `takeWithin(timeout)` | NoSuspend |
| `size` | `size` | NoSuspend |
| `isEmpty` | `isEmpty` | NoSuspend |

## Test Migration

Current test issues to fix:
1. Pattern matching in `SuspensionSafetyHelpers`
2. Import conflicts between `RuntimeExtensions` and `prelude`
3. Specs2 integration

## Benefits After Migration

1. **No Java Dependencies**: Pure functional implementation
2. **Type Safety**: Compile-time suspension guarantees
3. **Better Performance**: Optimized Eru-native implementation
4. **Consistency**: Same pattern for all concurrency primitives
5. **Architectural Purity**: Truly compositional concurrency

## Next Steps

1. Fix remaining test compilation issues
2. Run full test suite to validate behavior
3. Create adapter for backward compatibility
4. Begin incremental migration of existing code
5. Apply same pattern to other primitives (Promise, Semaphore, Deferred)