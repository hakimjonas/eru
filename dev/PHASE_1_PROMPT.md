# Phase 1: Foundational Data Structures - Implementation Prompt

## Objective
Implement the foundational data structures for Eru's fiber-based concurrency model while maintaining perfect alignment with the manifesto's four pillars.

## Context
You are implementing Phase 1 of the Eru fiber runtime evolution. The current Eru implementation uses a pure ADT design with cases like `Succeed`, `Fail`, `Effect`, `Chain`, etc. You need to extend this design with fiber-aware primitives while maintaining the existing architecture's purity and correctness.

## Implementation Tasks

### 1. Extend the Eru ADT with Fiber Primitives

Add two new cases to the `Eru[+E, +A]` enum in `eru-core/src/main/scala/net/ghoula/eru/Eru.scala`:

```scala
/** Represents forking a computation onto a separate fiber and returning a handle. */
private case Fork[E0, A0](computation: Eru[E0, A0]) extends Eru[Nothing, EruFiber[E0, A0]]

/** Represents awaiting the completion of a fiber. */
private case Await[E0, A0](fiber: EruFiber[E0, A0]) extends Eru[E0, Exit[E0, A0]]
```

### 2. Create the EruFiber Data Structure

Create a new file `eru-core/src/main/scala/net/ghoula/eru/EruFiber.scala`:

- Define `EruFiber[+E, +A]` as a pure, immutable data structure
- It should contain a `FiberId` and represent the handle to a running computation
- Include methods like `await: Eru[E, Exit[E, A]]` and `interrupt(cause: InterruptCause): Eru[Nothing, Unit]`
- This is a DESCRIPTION of a fiber, not the execution itself

### 3. Create the FiberContext System

Create `eru-core/src/main/scala/net/ghoula/eru/FiberContext.scala`:

- Define `FiberId` as an opaque type wrapping a unique identifier
- Define `FiberContext` containing state information (Running, Suspended, Done, etc.)
- Include fiber parentage information for structured concurrency
- All data structures must be immutable and pure

### 4. Add Public API Methods

Add these methods to the `Eru` companion object:

```scala
/** Forks a computation onto a separate fiber. */
def fork[E, A](computation: Eru[E, A]): Eru[Nothing, EruFiber[E, A]] = Fork(computation)

/** Creates an Eru that awaits the given fiber. */
def await[E, A](fiber: EruFiber[E, A]): Eru[E, Exit[E, A]] = Await(fiber)
```

### 5. Update the Internals.View

Add corresponding view cases to `Eru.Internals.View`:

```scala
case VFork[E0, A0](computation: Eru[E0, A0]) extends View[Nothing, EruFiber[E0, A0]]
case VAwait[E0, A0](fiber: EruFiber[E0, A0]) extends View[E0, Exit[E0, A0]]
```

## Critical Requirements

### Pillar I Alignment (Correctness)
- All new data structures must be pure and immutable
- Fork and Await operations are descriptions, not executions
- Maintain referential transparency throughout
- No side effects in constructors or methods

### Pillar II Alignment (Ergonomics)
- APIs should feel natural and discoverable
- Method names should be intuitive (`fork`, `await`)
- Type signatures should guide correct usage

### Pillar III Alignment (Guided Correctness)
- The easiest way to use these primitives should be the correct way
- Structured concurrency should be natural to achieve
- Resource cleanup should be automatic

### Pillar IV Alignment (Observability)
- All fiber operations should be observable via the existing EruObserver
- Include rich metadata in fiber contexts
- Maintain debugging capabilities

## Testing Requirements

Create comprehensive tests in `eru-core/src/test/scala/net/ghoula/eru/`:

1. **EruFiberSpec.scala** - Test fiber data structure properties
2. **FiberContextSpec.scala** - Test fiber context and ID management
3. **ForkAwaitSpec.scala** - Test the pure semantics of fork/await (construction-time behavior)

## Important Notes

- **DO NOT** implement execution logic in this phase - these are pure data structures
- **DO NOT** modify the existing interpreter - it should ignore the new cases for now
- **MAINTAIN** all existing functionality and tests
- **PRESERVE** the existing finalizer semantics completely
- **ENSURE** all new code has comprehensive Scaladoc
- **FOLLOW** all coding instructions (no inline comments, use modern Scala 3 features)

## Success Criteria

1. All existing tests continue to pass
2. New data structures are immutable and pure
3. Fork/Await cases exist but don't execute (interpreter ignores them)
4. Comprehensive test coverage for new data structures
5. Perfect Scaladoc documentation
6. Code passes all linting and formatting checks

This phase establishes the pure foundation without changing execution semantics. The interpreter will be updated in Phase 2.
