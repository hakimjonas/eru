# Suspension-Aware Architecture for Eru

## Executive Summary

This document establishes the gold standard architectural pattern for handling suspension in Eru's concurrency primitives. The discovery of inconsistent suspension handling across the codebase revealed a fundamental architectural issue that was causing hanging tests and violating Eru's Four Pillars.

## The Problem

### Discovered Issues
1. **No type-level suspension encoding** - Operations that could suspend indefinitely had the same type signatures as non-blocking operations
2. **Inconsistent naming** - No clear convention distinguishing blocking from non-blocking operations
3. **Test hanging** - Developers unknowingly called suspending operations synchronously, causing test deadlocks
4. **Documentation gaps** - Suspension behavior was inconsistently documented

### Architectural Violation
This violated two of Eru's Four Pillars simultaneously:
- **Foundational Correctness**: The foundation didn't correctly encode suspension semantics
- **Guided Correctness**: The API didn't guide users away from dangerous usage patterns

## The Solution: Gold Standard Pattern

### Type-Level Suspension Encoding

```scala
import SuspensionMarkers.*

// Operations that may suspend indefinitely
def take: Eru[Nothing, A] & CanSuspend

// Operations that complete immediately
def tryTake: Eru[Nothing, Option[A]] & NoSuspend

// Operations with bounded wait times
def takeWithin(timeout: Duration): Eru[Nothing, Option[A]] & NoSuspend
```

### Naming Conventions

| Pattern | Blocking (CanSuspend) | Non-blocking (NoSuspend) | Timeout (NoSuspend) |
|---------|----------------------|-------------------------|-------------------|
| Add | `put` | `tryPut` | `putWithin` |
| Remove | `take` | `tryTake` | `takeWithin` |
| Add Multiple | `putAll` | `tryPutAll` | `putAllWithin` |
| Remove Multiple | `takeUpTo` | `tryTakeUpTo` | `takeUpToWithin` |

### Compile-Time Safety

```scala
// Test helpers enforce suspension safety at compile time
def safeSyncRun[A](effect: Eru[Nothing, A] & NoSuspend): A

// This compiles - tryTake is NoSuspend
safeSyncRun(queue.tryTake)

// This doesn't compile - take is CanSuspend
// safeSyncRun(queue.take)  // ❌ Compile error!
```

## Implementation Strategy

### Phase 1: Gold Standard Queue (COMPLETE)
- ✅ Created `SuspensionMarkers.scala` with type markers
- ✅ Implemented `GoldStandardQueue` as the template
- ✅ Created `SuspensionSafetyHelpers` for testing
- ✅ Documented the pattern (this document)

### Phase 2: Incremental Migration Plan

#### Promise (Priority 1 - Heavily used in tests)
```scala
trait Promise[E, A] {
  // Before
  def await: Eru[E, A]  // Can hang forever!

  // After (Gold Standard)
  def await: Eru[E, A] & CanSuspend
  def tryAwait: Eru[Nothing, Option[Exit[E, A]]] & NoSuspend
  def awaitWithin(timeout: Duration): Eru[TimeoutError | E, A] & NoSuspend
}
```

#### Semaphore (Priority 2 - Common hanging culprit)
```scala
trait Semaphore {
  // Before
  def acquire: Eru[Nothing, Unit]  // Can hang forever!

  // After (Gold Standard)
  def acquire: Eru[Nothing, Unit] & CanSuspend
  def tryAcquire: Eru[Nothing, Boolean] & NoSuspend
  def acquireWithin(timeout: Duration): Eru[Nothing, Boolean] & NoSuspend
}
```

#### Deferred (Priority 3)
```scala
trait Deferred[E, A] {
  // After (Gold Standard)
  def await: Eru[E, A] & CanSuspend
  def tryGet: Eru[Nothing, Option[A]] & NoSuspend
  def complete(a: A): Eru[Nothing, Boolean] & NoSuspend
}
```

### Phase 3: Test Suite Migration

Convert existing tests to use suspension-safe patterns:

```scala
// Before - Dangerous!
test("queue operations") {
  val queue = Queue.bounded[Int](3).unsafeRunSync()
  queue.offer(1).unsafeRunSync()  // Could hang if full!
  queue.take.unsafeRunSync()      // Could hang if empty!
}

// After - Safe!
test("queue operations") {
  val queue = GoldStandardQueue.bounded[Int](3).unsafeRunSync()

  // Non-blocking operations are safe for sync testing
  safeSyncRun(queue.tryPut(1))
  safeSyncRun(queue.tryTake)

  // Blocking operations require async coordination
  val result = (for {
    fiber <- queue.take.fork  // Fork suspending operation
    _ <- queue.put(42)         // Unblock it
    value <- fiber.await
  } yield value).unsafeRunSync()
}
```

## Benefits

### Immediate Benefits
1. **Compile-time safety** - Impossible to accidentally call suspending operations synchronously
2. **Clear API contracts** - Suspension behavior visible in type signatures
3. **Better testing** - Test helpers enforce proper async patterns
4. **Consistent naming** - Clear distinction between operation variants

### Long-term Benefits
1. **Architectural consistency** - All primitives follow the same pattern
2. **Documentation in types** - Suspension behavior is self-documenting
3. **Onboarding** - New developers can't make dangerous mistakes
4. **Maintenance** - Easier to reason about concurrent code

## Validation Criteria

### Success Metrics
- [ ] No more hanging tests
- [ ] Consistent API across all concurrency primitives
- [ ] Compile-time prevention of suspension bugs
- [ ] Clear migration path for existing code

### Quality Checks
- [ ] All suspending operations marked with `CanSuspend`
- [ ] All non-suspending operations marked with `NoSuspend`
- [ ] Naming conventions consistently applied
- [ ] Comprehensive test coverage using safe patterns

## Philosophical Alignment

This architecture directly supports Eru's Four Pillars:

1. **Foundational Correctness**: Suspension semantics encoded in the type system
2. **Radical Ergonomics**: Clear, consistent API with comprehensive coverage
3. **Guided Correctness**: Compiler prevents dangerous usage patterns
4. **Transparent Runtime**: Predictable suspension behavior

## Conclusion

The gold standard Queue API demonstrates that we can achieve type-safe suspension handling without sacrificing ergonomics or backward compatibility. This pattern should be applied systematically across all Eru concurrency primitives to eliminate the architectural inconsistency that was causing our test failures.

This is not just a bug fix - it's an architectural enhancement that makes Eru more correct, safer, and easier to use. It exemplifies our commitment to excellence even in the face of discovered inconsistencies.