# Phase 2: The New Interpreter (The "Stepper") - Implementation Prompt

## Objective
Implement the new fiber-aware interpreter that can execute fork and await operations while maintaining stack safety, deterministic behavior, and proper finalizer handling.

## Context
Phase 1 has established the pure data structures (`EruFiber`, `FiberContext`, `Fork`, `Await` cases). Now you need to implement the execution engine that can handle these new primitives while preserving all existing semantics and the critical FILO finalizer behavior.

## Implementation Tasks

### 1. Create the Fiber-Aware Stepper

Create `eru-core/src/main/scala/net/ghoula/eru/internal/FiberStepper.scala`:

- Implement a new interpreter that handles all existing Eru cases PLUS Fork/Await
- Must be stack-safe using `TailRec`
- Must maintain deterministic step-by-step execution
- Must preserve the existing finalizer threading semantics exactly

### 2. Fiber State Management

Create `eru-core/src/main/scala/net/ghoula/eru/internal/FiberRegistry.scala`:

- Implement a registry for tracking running fibers
- Each fiber has a unique `FiberId` and maintains its execution state
- Handle fiber lifecycle: creation, suspension, resumption, completion
- Support fiber interruption and cleanup

### 3. Update the Core Interpreter

Modify `eru-core/src/main/scala/net/ghoula/eru/Eru.scala` interpreter object:

- Add handling for `Fork` and `Await` cases in the `runLoop` method
- **CRITICAL**: Maintain exact FILO finalizer semantics across fiber boundaries
- Ensure parent fibers properly inherit child finalizers
- Handle interruption propagation correctly

```scala
case Fork(computation) =>
  // Create new fiber, register it, start execution
  // Return immediately with fiber handle
  // Thread finalizers correctly
  
case Await(fiber) =>
  // Wait for fiber completion
  // Merge fiber's finalizers with current context
  // Maintain FILO ordering
```

### 4. Enhanced Observer Integration

Extend the existing `Hooks` system to emit fiber-specific events:

- `FiberStarted(fiberId, parentId)`
- `FiberSuspended(fiberId, reason)`
- `FiberResumed(fiberId)`
- `FiberCompleted(fiberId, exit)`
- `FiberInterrupted(fiberId, cause)`

### 5. Interruption Handling

Implement cooperative interruption:

- Fibers check for interruption at each step boundary
- Interruption propagates through fiber hierarchy
- Interrupted fibers run their finalizers before terminating
- **CRITICAL**: Maintain finalizer FILO order during interruption

## Critical Finalizer Considerations

The existing interpreter has sophisticated finalizer management with `drainFinalizers` that processes finalizers in FILO order. You MUST preserve this exact behavior:

### Current Finalizer Semantics (MUST MAINTAIN):
1. Finalizers are collected in a `List[Finalizer]` threaded through execution
2. `Ensure` operations prepend to the list (FILO order)
3. `drainFinalizers` processes from front to back (preserving FILO)
4. Each finalizer can produce additional "inner" finalizers
5. Inner finalizers are prepended to remaining finalizers

### New Fiber-Aware Requirements:
1. **Parent-Child Finalizer Inheritance**: When a child fiber completes, its finalizers must be merged with the parent's finalizer list
2. **FILO Preservation Across Fibers**: Child finalizers should be prepended to parent finalizers (child cleans up before parent)
3. **Interruption Safety**: If a fiber is interrupted, it must still run its accumulated finalizers
4. **Concurrent Finalizer Isolation**: Multiple fibers' finalizers should not interfere with each other

### Implementation Strategy:
```scala
// When a child fiber completes
case Await(fiber) =>
  val (childResult, childFinalizers) = fiber.awaitCompletion()
  val mergedFinalizers = childFinalizers ++ currentFinalizers // Child first (FILO)
  (childResult, mergedFinalizers)
```

## Testing Requirements

Create comprehensive tests in `eru-core/src/test/scala/net/ghoula/eru/`:

1. **FiberStepperSpec.scala** - Test the new interpreter with fiber operations
2. **FiberFinalizerSpec.scala** - **CRITICAL** - Comprehensive finalizer ordering tests
3. **FiberInterruptionSpec.scala** - Test interruption and cleanup
4. **FiberObservabilitySpec.scala** - Test observer integration
5. **FiberRegressionSpec.scala** - Ensure all existing behavior is preserved

### Critical Finalizer Tests:
```scala
test("child fiber finalizers run before parent finalizers (FILO)") {
  // Verify that when child completes, its finalizers execute first
}

test("interrupted fibers still run their finalizers in FILO order") {
  // Verify finalizer cleanup during interruption
}

test("nested fiber finalizers maintain proper FILO ordering") {
  // Test complex nested fiber scenarios
}
```

## Pillar Alignment

### Pillar I (Correctness)
- **Deterministic Execution**: Same input always produces same result
- **Stack Safety**: Use TailRec throughout
- **Finalizer Correctness**: Absolutely critical - any deviation breaks resource safety
- **Lawful Behavior**: All existing monad laws must still hold

### Pillar II (Ergonomics)
- Fiber operations should be transparent to the user
- Error messages should be clear and actionable
- Performance should be excellent

### Pillar III (Guided Correctness)
- The stepper should make correct fiber usage natural
- Resource cleanup should be automatic and correct
- Structured concurrency should emerge naturally

### Pillar IV (Observability)
- Every fiber operation should be observable
- Rich debugging information should be available
- Step-by-step execution should be traceable

## Implementation Sequence

1. **Start with the simplest case**: Implement Fork that creates a new fiber but runs it synchronously
2. **Add basic Await**: Implement waiting for synchronous fiber completion
3. **Add finalizer threading**: Ensure proper finalizer inheritance
4. **Add interruption**: Implement cooperative interruption
5. **Add observer integration**: Emit all fiber lifecycle events
6. **Optimize**: Add any performance optimizations while maintaining correctness

## Success Criteria

1. **All existing tests pass** - No regressions in existing functionality
2. **Finalizer behavior is identical** - FILO ordering preserved across all scenarios
3. **New fiber operations work** - Fork and await execute correctly
4. **Observer integration works** - All fiber events are properly emitted
5. **Stack safety maintained** - No stack overflow in deep fiber nesting
6. **Interruption works correctly** - Cooperative interruption with proper cleanup

## CRITICAL WARNING

Finalizer handling is the most critical aspect of this phase. The existing test suite will catch any regressions, but you must be extremely careful to preserve the exact FILO semantics. Any deviation will break resource safety guarantees and violate Pillar I (Correctness).

The existing `drainFinalizers` implementation is battle-tested and correct. Study it carefully and ensure your fiber-aware version maintains identical behavior.
