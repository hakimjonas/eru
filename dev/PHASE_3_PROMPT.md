# Phase 3: Integration and API Hookup - Implementation Prompt

## Objective
Re-implement high-level concurrency combinators (`zipPar`, `raceAll`, etc.) in terms of the new pure `fork` and `await` primitives, making complex concurrency operations transparent and composable.

## Context
Phases 1 and 2 have established the fiber data structures and execution engine. Now you need to rebuild the concurrency API to use these new primitives instead of the opaque backend implementations. This is a massive win for Guided Correctness - complex operations become readable Eru programs.

## Implementation Tasks

### 1. Re-implement EruRuntime Concurrency Operations

Update `eru-runtime/shared/src/main/scala/net/ghoula/eru/EruRuntime.scala`:

Replace the current backend-delegated implementations with pure Eru programs:

```scala
/** Parallel product - now implemented as pure Eru program */
def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
  for {
    fiberA <- Eru.fork(fa)
    fiberB <- Eru.fork(fb)
    exitA <- Eru.await(fiberA)
    exitB <- Eru.await(fiberB)
    resultA <- Eru.fromExit(exitA)
    resultB <- Eru.fromExit(exitB)
  } yield (resultA, resultB)

/** Race - implemented as pure Eru program with proper cancellation */
def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
  // Implementation using fork/await with cancellation logic
  ???

/** Parallel sequence - traverse a list with fork/await */
def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] =
  // Implementation using fork/await for each effect
  ???

/** Race all effects - first to complete wins */
def raceAll[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, (A, Int)] =
  // Implementation using fork/await with proper cancellation
  ???
```

### 2. Add Missing Eru Utilities

Add helper methods to `Eru` companion object for working with fibers:

```scala
/** Converts an Exit to an Eru, preserving error information */
def fromExit[E, A](exit: Exit[E, A]): Eru[E, A] = exit match {
  case Exit.Success(value) => Eru.succeed(value)
  case Exit.Failure(error) => Eru.fail(error)
  case Exit.Die(throwable) => Eru.effect(throw throwable)
  case Exit.Interrupt(cause) => Eru.effect(throw new InterruptedException(cause.toString))
}

/** Cancels a fiber (interrupts it) */
def cancel[E, A](fiber: EruFiber[E, A], cause: InterruptCause): Eru[Nothing, Unit] =
  fiber.interrupt(cause)

/** Forks an effect and returns both the fiber and a cancellation action */
def forkWithCancel[E, A](effect: Eru[E, A]): Eru[Nothing, (EruFiber[E, A], Eru[Nothing, Unit])] =
  for {
    fiber <- Eru.fork(effect)
    cancel = fiber.interrupt(InterruptCause.Cancelled)
  } yield (fiber, cancel)
```

### 3. Implement Structured Concurrency Patterns

Create `eru-runtime/shared/src/main/scala/net/ghoula/eru/StructuredConcurrency.scala`:

```scala
/** Scoped concurrent execution - all forked fibers are cancelled when scope exits */
def scoped[E, A](block: Eru[E, A]): Eru[E, A] =
  // Implementation that tracks all forked fibers and cancels them on scope exit
  ???

/** Supervisor pattern - restart failed fibers according to policy */
def supervised[E, A](effect: Eru[E, A], policy: RestartPolicy): Eru[Nothing, A] =
  // Implementation using fork/await with restart logic
  ???
```

### 4. Update Backend Interface

Simplify `eru-runtime/shared/src/main/scala/net/ghoula/eru/internal/ConcurrencyBackend.scala`:

- Remove complex concurrency operations from the backend
- Keep only primitive operations like scheduling and platform integration
- The backend should only handle the actual Virtual Thread creation and basic scheduling

```scala
private[eru] trait ConcurrencyBackend {
  def capabilities: BackendCapabilities
  
  /** Creates a new Virtual Thread for fiber execution */
  def createExecutionContext(): ExecutionContext
  
  /** Schedules a task for immediate execution */
  def schedule(task: Runnable): Unit
  
  /** Sleep for a duration */
  def sleep(duration: Duration): Eru[Nothing, Unit]
  
  // Remove: fork, zipPar, race, raceAll, parSequence, parTraverse
  // These are now pure Eru programs!
}
```

### 5. Add Cancellation Support

Implement proper cancellation semantics:

- When one fiber in a race completes, cancel all others
- When a parent scope exits, cancel all child fibers
- Cancellation should be cooperative and allow finalizers to run
- Cancelled fibers should complete with `Exit.Interrupt`

### 6. Enhanced Error Handling

Improve error propagation in concurrent scenarios:

```scala
/** Enhanced error handling that preserves stack traces across fibers */
def preserveErrorContext[E, A](effect: Eru[E, A]): Eru[E, A] =
  // Capture and preserve error context across fiber boundaries
  ???

/** Collect errors from multiple concurrent operations */
def collectErrors[E, A](effects: List[Eru[E, A]]): Eru[List[E], List[A]] =
  // Run all effects and collect both successes and failures
  ???
```

## Critical Implementation Requirements

### Guided Correctness (Pillar III)
This phase is the ultimate expression of Pillar III. By implementing concurrency operations as pure Eru programs:

1. **Transparency**: Users can read and understand how `zipPar` works
2. **Composability**: Complex patterns can be built from simple primitives
3. **Debuggability**: Every step is observable and traceable
4. **Customizability**: Users can implement their own patterns using the same primitives

### Resource Safety
- All forked fibers must be properly tracked and cancelled
- Cancellation must allow finalizers to run in FILO order
- Resource leaks are impossible by construction

### Performance Considerations
- The pure implementations should be as fast as the old backend versions
- Consider fiber pooling and reuse for hot paths
- Minimize allocations in concurrent operations

## Testing Requirements

Create comprehensive test suites:

1. **ConcurrencyOperationsSpec.scala** - Test all re-implemented operations
2. **StructuredConcurrencySpec.scala** - Test scoped execution and cancellation
3. **CancellationSpec.scala** - Test proper cancellation semantics
4. **ErrorPropagationSpec.scala** - Test error handling in concurrent scenarios
5. **PerformanceRegressionSpec.scala** - Ensure performance is maintained
6. **TransparencySpec.scala** - Verify operations are implemented as pure programs

### Key Test Scenarios:
```scala
test("zipPar cancels the loser when one side fails") {
  // Verify proper cancellation behavior
}

test("raceAll cancels all losers when winner completes") {
  // Verify cancellation of multiple fibers
}

test("structured scope cancels all child fibers on exit") {
  // Verify scope-based cancellation
}

test("cancelled fibers run their finalizers in FILO order") {
  // Critical finalizer test for concurrent scenarios
}

test("concurrent operations preserve error stack traces") {
  // Verify error context preservation
}
```

## Migration Strategy

### Backward Compatibility
- All existing APIs should continue to work
- No breaking changes to public interfaces
- Existing tests should pass without modification

### Performance Validation
- Benchmark the new implementations against the old ones
- Ensure no significant performance regression
- Optimize hot paths if needed

### Documentation Updates
- Update all documentation to reflect the new transparent implementations
- Add examples showing how users can build custom concurrency patterns
- Highlight the transparency benefits

## Success Criteria

1. **All concurrency operations work identically** - Same semantics as before
2. **Operations are transparent** - Implemented as readable Eru programs
3. **No performance regression** - Maintain or improve performance
4. **Proper cancellation** - All concurrent operations handle cancellation correctly
5. **Resource safety** - No fiber leaks, proper finalizer execution
6. **Backward compatibility** - Existing code continues to work
7. **Enhanced observability** - All operations are fully traceable

## The Big Win

This phase delivers on the core promise of Eru: making the complex simple through purity and composition. Instead of hiding concurrency logic in opaque backends, it's now expressed as pure, composable, and understandable Eru programs. This is the epitome of Guided Correctness - the easiest path (using the provided combinators) is also the most correct, observable, and customizable path.
