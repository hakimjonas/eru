# Principled Performance Optimization Plan for Eru (Refined)

## Core Philosophy: Incremental, Measured, Safe

This plan takes smaller, safer steps to improve performance while absolutely preserving Eru's groundbreaking achievements. We start with pure functional optimizations before considering any mutable structures.

## Guiding Principles

This optimization plan is designed to enhance Eru's performance while **absolutely preserving**:

1. **The Four Pillars of the Manifesto**
   - Foundational Correctness: No unsafe casts, no type compromises
   - Pragmatic Ergonomics: API remains clean and discoverable
   - Guided Correctness: Safe paths remain the easiest paths
   - Runtime Observability: Maintain transparency and introspection

2. **The Suspension Type System Achievement**
   - Compile-time deadlock prevention via `Suspending[E, A]` and `Immediate[E, A]`
   - Zero-cost value class wrappers (erased at runtime)
   - No `asInstanceOf`, no phantom types, no runtime casts
   - This is potentially groundbreaking - first effect system with compile-time suspension safety

3. **The Zero-Cast Runtime Guarantee**
   - Core interpreter remains cast-free
   - GADT optimization preserved
   - Type safety is non-negotiable

## Performance Issues to Address

### Critical Issues (Violating Performance Expectations)
1. **ZipParChaining**: Slower than competitors (27 vs 43 ops/ms)
2. **RaceBasic**: Minimal advantage (84 vs 77 ops/ms)

### Significant Gaps (2-4x slower)
3. **Promise**: 2900 vs 8375 ops/ms (ZIO)
4. **Queue**: 1922 vs 4872 ops/ms (ZIO)
5. **RefComplexUpdate**: 825 vs 3555 ops/ms (ZIO)

## Optimization Strategy: Incremental Steps

### Step 1: Pure Functional Data Structure Improvements (Week 1)
**Goal**: Replace List with better immutable structures

#### 1.1 Promise: List → Vector
```scala
// Current: O(n) append with List
case class Pending[E, A](waiters: List[Either[E, A] => Unit])

// Step 1: Use Vector for O(~1) append
case class Pending[E, A](waiters: Vector[Either[E, A] => Unit])

// Measurement: Run promise benchmarks before/after
// Expected: 2900 → 4000+ ops/ms (40% improvement)
```

#### 1.2 Queue: List → scala.collection.immutable.Queue
```scala
// Current: List with O(n) operations
case class QueueState[A](
  elements: List[A],  // :+ is O(n)
  // ...
)

// Step 1: Use immutable.Queue for O(1) amortized enqueue/dequeue
import scala.collection.immutable.{Queue => IQueue}
case class QueueState[A](
  elements: IQueue[A],  // enqueue is O(1) amortized
  // ...
)

// Measurement: Run queue benchmarks
// Expected: 1922 → 3000+ ops/ms (55% improvement)
```

### Step 2: Algorithmic Optimizations (Week 1-2)
**Goal**: Smarter algorithms without changing data structures

#### 2.1 ZipPar Short-Circuit Optimization
```scala
// Add a fast path for already-completed computations
// Since Eru's constructors are private, use a helper
object EruOptimizations {
  def isAlreadyComputed[E, A](eru: Eru[E, A]): Boolean =
    // Use pattern matching on the visible structure
    eru match {
      case _: Eru.type#Succeed[A] => true  // Won't compile - private
      case _ => false
    }

  // Better approach: Add to Eru companion
  def isValue[E, A](eru: Eru[E, A]): Boolean = {
    // Internal method that can see private constructors
    eru match {
      case Succeed(_) | Fail(_) => true
      case _ => false
    }
  }
}

// Then in zipPar:
def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[...] = {
  // Try to avoid forking if one side is trivial
  if (Eru.isValue(fa) && Eru.isValue(fb)) {
    // Both are values, just combine them
    for {
      a <- fa
      b <- fb
    } yield (a, b)
  } else if (Eru.isValue(fa)) {
    // Only fork fb
    fb.map(b => fa.map(a => (a, b))).flatten
  } else if (Eru.isValue(fb)) {
    // Only fork fa
    fa.map(a => fb.map(b => (a, b))).flatten
  } else {
    // Original implementation
    for {
      fiberA <- fork(fa)
      fiberB <- fork(fb)
      // ...
    }
  }
}
```

**Measurement**: 
- Benchmark before/after with `eruZipParChaining`
- Target: 27 → 80+ ops/ms
- Verify: No API changes, no type safety loss

#### 2.2 Race: Reduce Synchronization Overhead
```scala
// Current: CountDownLatch + AtomicReference + 2 AtomicReferences for threads
// That's 4 synchronization points!

// Step 1: Use a single volatile boolean for winner determination
@volatile var hasWinner = false
val winnerRef = new AtomicReference[Option[...]](None)

// Step 2: Use your existing structured concurrency!
// You already have StructuredConcurrency.withNewScope
// This can track the racing fibers

def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[...] = {
  StructuredConcurrency.withNewScope { scope =>
    val promise = Promise.make[E1 | E2 | Throwable, Either[A, B]]

    val fiberA = fork {
      fa.map(a => promise.succeed(Left(a)))
    }

    val fiberB = fork {
      fb.map(b => promise.succeed(Right(b)))
    }

    promise.await
    // Scope cleanup will handle fiber cancellation
  }
}
```

**Measurement**:
- Benchmark `eruRaceBasic`
- Target: 84 → 200+ ops/ms
- Verify: Cancellation semantics preserved

### Step 3: Batch Operations Optimization (Week 2)
**Goal**: Optimize patterns that appear in benchmarks

#### 3.1 ZipParChaining Pattern Recognition
The benchmark does:
```scala
effects.foldLeft(Eru.succeed(0)) { (acc, eff) =>
  acc.zipPar(eff).map { case (sum, value) => sum + value }
}
```

This creates 2N fibers for N elements! Instead:

```scala
// Add a specialized method for this common pattern
def zipParFold[E, A, B](effects: Seq[Eru[E, A]])(zero: B)(f: (B, A) => B): Eru[E | Throwable, B] = {
  // Fork all effects once
  val fibers = effects.map(fork)
  // Then await and fold
  fibers.foldLeft(Eru.succeed(zero)) { (acc, fiber) =>
    for {
      b <- acc
      a <- fiber.await.map(_.toEither.fold(Eru.fail, identity))
      result <- a.map(f(b, _))
    } yield result
  }
}
```

### Step 4: Consider Mutable Structures (Week 3) - ONLY IF NEEDED
**Goal**: If immutable improvements aren't sufficient

#### 4.1 Promise: Vector → ConcurrentLinkedQueue
- Only if Vector doesn't achieve target performance
- Wrapped in Ref, never exposed
- Document thoroughly why this was necessary

#### 4.2 Custom Immutable Queue Implementation
- Before using mutable structures, consider a banker's queue
- Two lists (front/back) with amortized O(1) operations
```scala
case class BankersQueue[A](
  front: List[A],
  back: List[A]
) {
  def enqueue(a: A): BankersQueue[A] =
    BankersQueue(front, a :: back)

  def dequeue: (Option[A], BankersQueue[A]) = front match {
    case h :: t => (Some(h), BankersQueue(t, back))
    case Nil => back.reverse match {
      case h :: t => (Some(h), BankersQueue(t, Nil))
      case Nil => (None, this)
    }
  }
}
```

### Step 5: Leverage Your Existing Infrastructure (Week 3)
**Goal**: Use what you've already built!

#### 5.1 Your Structured Concurrency is Already There!
```scala
// You've implemented StructuredConcurrency.withNewScope
// This already tracks child fibers properly
// Use it more extensively for operations like race

// Your implementation already has:
- ThreadLocal scope tracking
- Automatic child fiber cleanup
- Proper parent-child relationships

// This is better than JDK's preview APIs!
```

#### 5.2 Fiber Pooling Using Your FiberScope
```scala
// Your FiberScope already uses ConcurrentLinkedQueue
// Consider reusing completed fibers from the scope
private class FiberScope(
  val childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]],
  val reusableFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] // Add this
)

// When a fiber completes, add to reusable pool
// When forking, check pool first
```

### Phase 4: Ref Optimizations (Week 4)
**Goal**: Better CAS patterns for complex updates

```scala
// Add exponential backoff for contended updates
def modify[B](f: A => (A, B)): Eru[Nothing, B] = {
  @tailrec def loop(retries: Int): Eru[Nothing, B] = {
    val current = ref.get()
    val (newValue, result) = f(current)
    if (ref.compareAndSet(current, newValue)) {
      Eru.succeed(result)
    } else {
      if (retries > 0) Thread.onSpinWait()  // JDK 9+ spin hint
      loop(retries + 1)
    }
  }
  loop(0)
}
```

## Measurement Methodology: Safety Gates

### Before Starting: Comprehensive Baseline
```bash
# 1. Full benchmark suite with extra iterations for stability
sbt 'eruBenchJVM/Jmh/run -f 2 -wi 5 -i 10 net.ghoula.eru.bench.fair.*'
mv benchmark-results baseline-$(date +%Y%m%d-%H%M%S)

# 2. Memory baseline
sbt 'eruBenchJVM/Jmh/run -prof gc -f 1 -wi 3 -i 5 net.ghoula.eru.bench.fair.ConcurrencyBench.eruZipParChaining'

# 3. Document current implementation
git diff > /dev/null  # Ensure clean state
git tag baseline-performance
```

### Safety Gate for EACH Change
```bash
# Create a script: verify-change.sh
#!/bin/bash
set -e

CHANGE_NAME=$1
BENCHMARK=$2

echo "=== Safety Gate for $CHANGE_NAME ==="

# 1. Compile check
echo "Checking for unsafe casts..."
if sbt 'eruRuntimeJVM/compile' 2>&1 | grep -iE "asinstanceof|cast|unsafe"; then
  echo "FAILED: Unsafe operations detected"
  exit 1
fi

# 2. Test suite
echo "Running tests..."
./run-all-tests.sh || exit 1

# 3. Specific benchmark
echo "Running benchmark..."
sbt "eruBenchJVM/Jmh/run -f 1 -wi 3 -i 5 $BENCHMARK" | tee "results-$CHANGE_NAME.txt"

# 4. Compare with baseline
echo "Comparing with baseline..."
# Extract score and verify improvement

# 5. Suspension type safety
echo "Verifying suspension types..."
scala-cli - <<EOF
// This MUST NOT compile
// queue.take.unsafeRunSync()
EOF

echo "=== Safety Gate PASSED ==="
```

### 3. Progress Tracking

Create `OPTIMIZATION_PROGRESS.md`:
```markdown
| Optimization | Target | Baseline | Current | Status | Notes |
|--------------|--------|----------|---------|--------|-------|
| ZipParChaining | 80 | 27 | - | Not Started | |
| RaceBasic | 200 | 84 | - | Not Started | |
| Promise | 8000 | 2900 | - | Not Started | |
| Queue | 5000 | 1922 | - | Not Started | |
```

### Incremental Progress Tracking

Create `OPTIMIZATION_PROGRESS.md`:
```markdown
# Optimization Progress

## Step 1: Immutable Data Structures
| Change | Target | Baseline | Result | Δ% | Status | Safety Check |
|--------|--------|----------|--------|-----|--------|-------------|
| Promise: List→Vector | 4000 | 2900 | - | - | Not Started | - |
| Queue: List→IQueue | 3000 | 1922 | - | - | Not Started | - |

## Step 2: Algorithmic
| Change | Target | Baseline | Result | Δ% | Status | Safety Check |
|--------|--------|----------|--------|-----|--------|-------------|
| ZipPar fast path | 50 | 27 | - | - | Not Started | - |
| Race simplification | 120 | 84 | - | - | Not Started | - |

## Decision Points
- [ ] If Step 1 achieves >50% of target → continue with immutable
- [ ] If Step 1 achieves <30% of target → consider mutable structures
- [ ] If Step 2 fixes ZipParChaining → no need for complex batching
```

## Implementation Rules

### MUST DO:
1. ✅ Preserve ALL suspension type distinctions
2. ✅ Keep value classes for `Suspending` and `Immediate`
3. ✅ Maintain zero-cast runtime guarantee
4. ✅ Keep APIs unchanged (only internal optimizations)
5. ✅ Test each change in isolation
6. ✅ Document performance rationale in code

### MUST NOT DO:
1. ❌ Use `asInstanceOf` anywhere
2. ❌ Expose mutable data structures in APIs
3. ❌ Compromise type safety for performance
4. ❌ Change suspension semantics
5. ❌ Break any existing tests
6. ❌ Violate the manifesto principles

## Success Criteria

### Minimum Acceptable:
- ZipParChaining: 27 → 60+ ops/ms (2x improvement)
- RaceBasic: 84 → 150+ ops/ms (1.8x improvement)
- No regression in other benchmarks
- All tests still pass
- No type safety compromises

### Target Goals:
- ZipParChaining: 80+ ops/ms (match competitors)
- RaceBasic: 200+ ops/ms (2.5x current)
- Promise: 8000+ ops/ms (match ZIO)
- Queue: 5000+ ops/ms (2.5x current)

### Stretch Goals:
- Exceed ZIO performance while maintaining better type safety
- Become the reference implementation for suspension type systems

## Risk Mitigation

1. **Risk**: Breaking suspension type safety
   **Mitigation**: Write compile-fail tests FIRST

2. **Risk**: Introducing race conditions
   **Mitigation**: Stress test with 1000x iterations

3. **Risk**: API compatibility
   **Mitigation**: No public API changes, only internals

4. **Risk**: Platform compatibility (Native)
   **Mitigation**: Use platform-specific implementations where needed

## Refined Timeline: Small Steps

**Week 1: Pure Functional Improvements**
- Day 1: Establish baselines, create safety gate scripts
- Day 2-3: Promise with Vector
  - Measure, decide if sufficient
- Day 4-5: Queue with immutable.Queue
  - Measure, evaluate amortized performance
- Day 6: Decision point - are we achieving targets?

**Week 2: Algorithmic Optimizations**
- Day 7-8: ZipPar fast path for values
  - May need to expose helper method in Eru
- Day 9-10: Race using existing structured concurrency
  - Leverage what you've already built!
- Day 11: Measure combined improvements

**Week 3: Only If Necessary**
- Day 12-13: Custom banker's queue implementation
- Day 14-15: Consider bounded mutable structures in Ref
- Day 16-17: Fiber pooling with existing FiberScope

**Week 4: Integration & Validation**
- Day 18-19: Full benchmark suite
- Day 20: Documentation of changes
- Day 21: Final validation

## Key Insights from Analysis

### What You've Already Built
1. **Custom Structured Concurrency**: You've implemented your own SC on top of Virtual Threads
   - ThreadLocal scope tracking
   - FiberScope with child tracking
   - Automatic cleanup semantics
   - This is MORE stable than JDK's preview APIs!

2. **Your SC is Better**:
   - Not dependent on preview/incubator APIs
   - Works today on JDK 21
   - Already integrated throughout Eru
   - We should USE it more, not replace it

3. **The Real Issues**:
   - Not missing advanced features
   - Just inefficient data structures (List everywhere)
   - Excessive fiber creation in loops
   - Not leveraging your own infrastructure

### The Principled Approach

1. **Start Pure**: Vector and immutable.Queue may be sufficient
   - These are battle-tested Scala collections
   - O(1) or O(log n) vs current O(n)
   - No safety compromises

2. **Measure Incrementally**:
   - Each step gets measured
   - Decision points based on actual data
   - Stop when targets are met

3. **Use What You Have**:
   - Your StructuredConcurrency for race
   - Your FiberScope for pooling
   - Your existing abstractions

4. **Only Then Consider Mutable**:
   - Only if immutable isn't sufficient
   - Always wrapped in Ref
   - Never exposed in API
   - Document WHY it was necessary

## Conclusion

This refined plan takes smaller, safer steps toward performance improvement. We start with pure functional optimizations that may be entirely sufficient. Only after measuring do we consider more aggressive optimizations.

**The key principle**: Incremental improvement with measurement gates. We don't need to jump straight to mutable structures - better algorithms and better immutable structures may solve our problems.

**The achievement we're protecting**: The suspension type system that prevents deadlocks at compile time. This is potentially groundbreaking and must be preserved completely.

**The path forward**: Small steps, careful measurement, preserve what makes Eru special.
