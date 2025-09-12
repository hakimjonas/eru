# Valar Integration Guide: Migrating to Eru

This guide provides step-by-step instructions for rebasing Valar on Eru's effect system.

## Phase 1: Dependencies Setup

### 1. Add Eru SNAPSHOT Dependencies

```scala
// In build.sbt - add SNAPSHOT resolver
resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

// Add Eru dependencies  
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "0.9-SNAPSHOT",
  "net.ghoula" %% "eru-runtime" % "0.9-SNAPSHOT"
)
```

### 2. Import Pattern
```scala
// Replace existing Valar effect imports with:
import net.ghoula.eru.prelude.*

// This provides:
// - Eru[E, A] effect type
// - Extension methods (fork, zipPar, race, timeout, etc.)
// - Runtime constructors (ref, semaphore, queue, etc.)  
// - Observer integration
```

## Phase 2: Effect Type Migration

### 1. Replace Effect Type
```scala
// Before: Custom Valar effect type
type ValarEffect[E, A] = ...

// After: Use Eru directly
type ValarEffect[E, A] = Eru[E, A]
// Or just use Eru[E, A] directly throughout codebase
```

### 2. Constructor Replacements
```scala
// Success values
// Before: ValarEffect.pure(value)
// After:  Eru.succeed(value)

// Failures  
// Before: ValarEffect.fail(error)
// After:  Eru.fail(error)

// Side effects
// Before: ValarEffect.effect(thunk)  
// After:  Eru.effect(thunk)

// Blocking operations
// Before: ValarEffect.blocking(thunk)
// After:  Eru.blocking(thunk) or Eru.interruptibleBlocking(thunk)
```

## Phase 3: Concurrency Migration

### 1. Runtime Setup
```scala
// Create runtime instance (typically once per application)
given runtime: EruRuntime = EruRuntime.create()

// In tests, use isolated runtime:
val runtime = EruRuntime.create()
// ... test logic ...
runtime.cleanup() // Clean up after test
```

### 2. Parallel Operations
```scala
// Fork operations
// Before: ValarEffect.fork(effect)  
// After:  effect.fork (extension method)

// Parallel composition
// Before: ValarEffect.zipPar(fa, fb)
// After:  fa.zipPar(fb) (extension method)

// Racing
// Before: ValarEffect.race(fa, fb) 
// After:  fa.race(fb) (extension method)

// Parallel sequences  
// Before: ValarEffect.parSequence(effects)
// After:  runtime.parSequence(effects) (runtime method)

// Bounded parallelism
// Before: ValarEffect.foreachParN(n, inputs)(f)
// After:  runtime.foreachParN(n, inputs)(f)
```

### 3. Timeouts and Retries
```scala
// Timeouts
// Before: ValarEffect.timeout(duration, effect)
// After:  effect.timeout(duration) (extension method)

// Timeout with fallback
// Before: ValarEffect.timeoutTo(duration, fallback, effect)  
// After:  effect.timeoutTo(duration, fallback)

// Retries
// Before: ValarEffect.retry(policy, effect)
// After:  effect.retry(policy) or effect.retryN(maxRetries)
```

## Phase 4: Resource Management

### 1. Finalizers
```scala
// Before: ValarEffect.ensure(effect, finalizer)
// After:  effect.ensure(finalizer) (extension method)

// Before: ValarEffect.bracket(acquire, use, release)  
// After:  acquire.bracket(release)(use) (extension method)
```

### 2. Concurrent Primitives
```scala
// References
// Before: ValarRef.make(initial)
// After:  Eru.ref(initial) (via extension)

// Semaphores  
// Before: ValarSemaphore.make(permits)
// After:  Eru.semaphore(permits)

// Queues
// Before: ValarQueue.bounded(capacity) 
// After:  Eru.queue(capacity)

// Deferred/Promise
// Before: ValarDeferred.make[A]
// After:  Eru.deferred[A] (requires implicit runtime)
```

## Phase 5: Execution

### 1. Running Effects
```scala  
// Synchronous execution
// Before: effect.unsafeRunSync(customRuntime)
// After:  effect.unsafeRunSync() (uses global runtime)

// With observer
// Before: effect.unsafeRunSyncWith(runtime, observer)
// After:  effect.runWith(observer) (extension method)

// Exit-based execution
// Before: effect.unsafeRunToExit(runtime)
// After:  effect.runExit() (extension method)
```

### 2. Error Handling
```scala
// Attempt (Result wrapper)
// Before: effect.attempt.unsafeRunSync()  
// After:  effect.attempt.unsafeRunSync() (same)

// Or use Exit-based:
effect.runExit() match {
  case Exit.Success(value) => // handle success
  case Exit.Failure(error) => // handle typed error  
  case Exit.Die(throwable) => // handle defect
  case Exit.Interrupt(_, _) => // handle interruption
}
```

## Phase 6: Testing Integration

### 1. Test Structure
```scala
class ValarSpec extends munit.FunSuite {
  // Create isolated runtime per test
  override def munitFixtures = List(runtimeFixture)

  val runtimeFixture = new Fixture[EruRuntime]("runtime") {
    def apply() = EruRuntime.create()
    override def afterEach(runtime: EruRuntime): Unit = runtime.cleanup()
  }

  test("some test") {
    val runtime = runtimeFixture()
    // Test logic using runtime
  }
}
```

### 2. Assertions
```scala
// Test successful effects
val result = effect.unsafeRunSync()
assertEquals(result, expected)

// Test failures  
val result = effect.attempt.unsafeRunSync()
assert(result.isFailure)

// Test with Exit
val exit = effect.runExit()
assert(exit.isSuccess)
```

## Phase 7: Performance Validation

### 1. Benchmark Migration
```scala
// Update JMH benchmarks to use Eru
@Benchmark
def valarEffect(): Int = {
  val effect = for {
    ref <- Eru.ref(0)
    _ <- ref.update(_ + 1).repeatN(1000)
    value <- ref.get
  } yield value
  
  effect.unsafeRunSync()
}
```

### 2. Expected Performance Gains
Based on Eru benchmarks:
- **Ref operations**: 4-10x improvement  
- **Semaphore operations**: 3-8x improvement
- **Parallel operations**: Consistent high performance
- **Memory usage**: Reduced allocation overhead

## Migration Checklist

### Core Migration
- [ ] Replace effect type with `Eru[E, A]`  
- [ ] Update imports to use `net.ghoula.eru.prelude.*`
- [ ] Migrate constructors (`succeed`, `fail`, `effect`)
- [ ] Update error handling patterns

### Concurrency Migration  
- [ ] Setup `EruRuntime` instances
- [ ] Migrate parallel operations (`zipPar`, `race`)
- [ ] Update bounded parallelism (`foreachParN`)
- [ ] Migrate timeout and retry logic

### Resource Management
- [ ] Migrate finalizers and bracket patterns
- [ ] Update concurrent primitives (Ref, Semaphore, Queue)
- [ ] Verify resource cleanup in failure scenarios

### Testing & Validation
- [ ] Update test infrastructure  
- [ ] Migrate benchmarks
- [ ] Validate performance improvements
- [ ] Ensure no behavior regressions

## Troubleshooting

### Common Issues

**1. "runtime not found" errors**
```scala  
// Solution: Add implicit runtime parameter or import
given runtime: EruRuntime = EruRuntime.create()
// Or pass explicitly to methods that need it
```

**2. "method not found on Eru" errors**  
```scala
// Solution: Use runtime methods for operations like parSequence
runtime.parSequence(effects) // Not Eru.parSequence(effects)
```

**3. Type inference issues**
```scala
// Solution: Add explicit type annotations  
val effect: Eru[String, Int] = computeValue()
```

**4. Resource cleanup in tests**
```scala
// Solution: Always cleanup runtimes in tests
val runtime = EruRuntime.create() 
try {
  // test logic  
} finally {
  runtime.cleanup()
}
```

## Performance Expectations

After migration, expect:
- **40k+ ops/ms** for Ref operations (vs ~8k current)
- **Consistent performance** across concurrent operations  
- **Lower memory allocation** due to optimized runtime
- **Better scaling** with increased parallelism

The migration preserves all existing semantics while providing significant performance improvements.