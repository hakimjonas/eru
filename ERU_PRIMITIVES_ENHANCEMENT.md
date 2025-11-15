# Eru Primitives Enhancement - Contention & Scalability

**Date**: November 13, 2025
**Branch**: Start from `main`
**Goal**: Enhance Eru's concurrent primitives to better handle high-contention scenarios and multi-keyed resource management

---

## Context

Eru currently has excellent primitives (`Ref`, `Semaphore`, `Queue`, `Promise`) that work well for typical use cases. However, analysis of the codebase and benchmarks reveals opportunities to better support:

1. **High-contention scenarios** - Multiple fibers competing for same resource
2. **Multi-keyed resources** - Independent resources that shouldn't contend (e.g., per-host limits, per-user quotas)
3. **Observability** - Understanding performance characteristics in production
4. **Fine-grained coordination** - Per-key synchronization without global bottlenecks

These enhancements will benefit the entire Eru ecosystem: connection pools, caches, rate limiters, resource managers, and multi-tenant systems.

---

## Current State Analysis

### Ref Implementation (eru-runtime/shared/src/main/scala/net/ghoula/eru/Ref.scala)

**Current approach:**
```scala
def modify[B](f: A => (A, B)): Eru[Nothing, B] =
  Eru.effectTotal {
    @annotation.tailrec
    def loop(): B = {
      val current = state.get()
      val (next, out) = f(current)
      if (state.compareAndSet(current, next)) out
      else loop()  // ⚠️ Immediate retry, no backoff
    }
    loop()
  }
```

**Issues:**
- Under high contention (100+ concurrent modifies), CAS failures spike
- Immediate retry causes CPU spinning and cache line thrashing
- No fairness - some operations can starve
- No observability into contention levels

**Benchmark evidence:**
- `ConcurrencyScalingBench.eruConcurrentStateScaling` tests up to 1000 concurrent Ref updates
- Performance is competitive with ZIO/Cats Effect but could be better with backoff

---

## Enhancement 1: Ref with Backoff Strategies

### Goal
Add contention management to Ref without breaking existing API.

### Design

#### Option A: Enhance RuntimeRef internally (RECOMMENDED)
```scala
private final class RuntimeRef[A](
  init: A,
  backoffStrategy: BackoffStrategy = BackoffStrategy.Adaptive
) extends Ref[A] {

  private val state = new java.util.concurrent.atomic.AtomicReference(init)

  def modify[B](f: A => (A, B)): Eru[Nothing, B] =
    Eru.effectTotal {
      var attempts = 0

      @annotation.tailrec
      def loop(): B = {
        val current = state.get()
        val (next, out) = f(current)

        if (state.compareAndSet(current, next)) {
          out
        } else {
          attempts += 1
          backoffStrategy.backoff(attempts)
          loop()
        }
      }
      loop()
    }
}

sealed trait BackoffStrategy {
  def backoff(attempt: Int): Unit
}

object BackoffStrategy {
  /** No backoff - current behavior (for compatibility) */
  case object None extends BackoffStrategy {
    def backoff(attempt: Int): Unit = ()
  }

  /** Adaptive backoff - yield after 3 attempts, then exponential */
  case object Adaptive extends BackoffStrategy {
    def backoff(attempt: Int): Unit = {
      if (attempt <= 3) {
        // First few attempts: just retry (optimistic)
        ()
      } else if (attempt <= 10) {
        // Thread.yield() - let other threads run
        Thread.`yield`()
      } else {
        // Exponential backoff with cap
        val delayNanos = Math.min(
          1000L * (1L << (attempt - 10)),
          100000L  // Max 100μs
        )
        java.util.concurrent.locks.LockSupport.parkNanos(delayNanos)
      }
    }
  }

  /** Exponential backoff from first retry */
  final case class Exponential(
    baseNanos: Long = 1000L,
    maxNanos: Long = 100000L
  ) extends BackoffStrategy {
    def backoff(attempt: Int): Unit = {
      val delayNanos = Math.min(
        baseNanos * (1L << attempt),
        maxNanos
      )
      java.util.concurrent.locks.LockSupport.parkNanos(delayNanos)
    }
  }
}
```

#### Option B: Add factory variants (SIMPLER)
```scala
object Ref {
  /** Create Ref with default (adaptive) backoff */
  def make[A](initial: A): Eru[Nothing, Ref[A]] =
    Eru.succeed(new RuntimeRef[A](initial, BackoffStrategy.Adaptive))

  /** Create Ref with custom backoff strategy */
  def makeWithBackoff[A](
    initial: A,
    strategy: BackoffStrategy
  ): Eru[Nothing, Ref[A]] =
    Eru.succeed(new RuntimeRef[A](initial, strategy))

  /** Create Ref optimized for low contention (no backoff) */
  def makeFast[A](initial: A): Eru[Nothing, Ref[A]] =
    Eru.succeed(new RuntimeRef[A](initial, BackoffStrategy.None))
}
```

### Implementation Tasks

1. **Add BackoffStrategy trait and implementations** (new file: `BackoffStrategy.scala`)
   - `BackoffStrategy.None` (current behavior)
   - `BackoffStrategy.Adaptive` (recommended default)
   - `BackoffStrategy.Exponential` (configurable)

2. **Enhance RuntimeRef** (modify: `Ref.scala`)
   - Add `backoffStrategy` parameter to constructor
   - Add backoff logic to `modify` and `update` methods
   - Keep existing behavior as default OR make Adaptive the new default

3. **Add factory methods** (modify: `Ref.scala`)
   - `Ref.make` - use Adaptive by default (or None for compatibility)
   - `Ref.makeWithBackoff` - custom strategy
   - `Ref.makeFast` - explicitly no backoff

4. **Update tests** (new file: `RefBackoffSpec.scala`)
   - Test under contention (fork 100 fibers, all modifying same Ref)
   - Measure CAS retry count with different strategies
   - Verify correctness (all updates applied)

5. **Update benchmarks** (modify: `StateManagementBench.scala`)
   - Add benchmark comparing None vs Adaptive vs Exponential
   - High contention scenario (100+ concurrent modifies)

6. **Documentation**
   - When to use which strategy
   - Performance characteristics
   - Migration guide (if changing default)

### Success Criteria
- ✅ Ref with Adaptive backoff has <50% CAS retries under 100 concurrent modifies
- ✅ All existing tests pass (backward compatibility)
- ✅ Benchmarks show improved performance under high contention
- ✅ No performance regression for low-contention scenarios

---

## Enhancement 2: RefMap - Per-Key State Management

### Goal
Provide efficient concurrent map where each key has independent CAS semantics (no cross-key contention).

### Use Cases
- Connection pools (per-host state)
- Caches (per-key entry)
- Rate limiters (per-user/API key limits)
- Resource quotas (per-tenant resources)
- Registries (per-service state)

### Design

```scala
/** Concurrent map where each key has independent Ref semantics.
  *
  * Unlike a single Ref[Map[K, V]], RefMap provides per-key isolation:
  * - Updates to different keys never contend
  * - Each key has its own CAS loop
  * - Memory-efficient (no global state copying)
  *
  * Thread-safe for concurrent access from multiple Virtual Threads.
  */
trait RefMap[K, V] {

  /** Get current value for key */
  def get(key: K): Eru[Nothing, Option[V]]

  /** Set value for key */
  def set(key: K, value: V): Eru[Nothing, Unit]

  /** Remove key and return previous value */
  def remove(key: K): Eru[Nothing, Option[V]]

  /** Atomically modify value at key
    *
    * The function receives current value (or None if key absent)
    * and returns (new value, result to return).
    *
    * If f returns (None, result), the key is removed.
    */
  def modify[B](key: K)(f: Option[V] => (Option[V], B)): Eru[Nothing, B]

  /** Atomically update value at key */
  def update(key: K)(f: Option[V] => Option[V]): Eru[Nothing, Option[V]]

  /** Get all keys currently in map */
  def keys: Eru[Nothing, Set[K]]

  /** Get number of keys in map */
  def size: Eru[Nothing, Int]

  /** Remove all keys */
  def clear: Eru[Nothing, Unit]
}

object RefMap {

  /** Create empty RefMap */
  def make[K, V]: Eru[Nothing, RefMap[K, V]] =
    Eru.succeed(new ConcurrentHashMapRefMap[K, V]())

  /** Create RefMap with initial entries */
  def makeWith[K, V](entries: Map[K, V]): Eru[Nothing, RefMap[K, V]] =
    for {
      refMap <- make[K, V]
      _ <- Eru.foreach(entries.toList) { case (k, v) =>
        refMap.set(k, v)
      }
    } yield refMap
}
```

### Implementation Strategy

#### Option A: ConcurrentHashMap of Refs (RECOMMENDED)
```scala
private final class ConcurrentHashMapRefMap[K, V] extends RefMap[K, V] {
  import java.util.concurrent.ConcurrentHashMap

  // Each key gets its own Ref - perfect isolation!
  private val refs = new ConcurrentHashMap[K, Ref[V]]()

  def modify[B](key: K)(f: Option[V] => (Option[V], B)): Eru[Nothing, B] = {
    for {
      ref <- getOrCreateRef(key)
      result <- ref.modify { currentValue =>
        f(Some(currentValue)) match {
          case (Some(newValue), result) =>
            (newValue, (false, result))  // Keep key
          case (None, result) =>
            (currentValue, (true, result))  // Mark for removal
        }
      }
      (shouldRemove, output) = result
      _ <- if (shouldRemove) Eru.effectTotal(refs.remove(key)) else Eru.unit
    } yield output
  }

  private def getOrCreateRef(key: K): Eru[Nothing, Ref[V]] = {
    Eru.effectTotal {
      refs.computeIfAbsent(key, _ => {
        // This is tricky - need initial value...
        // Alternative: use Option[Ref[V]] or special "empty" marker
        ???
      })
    }
  }
}
```

**Challenge**: Ref needs initial value, but key might not exist yet.

#### Option B: ConcurrentHashMap with AtomicReference per key
```scala
private final class ConcurrentHashMapRefMap[K, V] extends RefMap[K, V] {
  import java.util.concurrent.ConcurrentHashMap
  import java.util.concurrent.atomic.AtomicReference

  private val map = new ConcurrentHashMap[K, AtomicReference[V]]()

  def modify[B](key: K)(f: Option[V] => (Option[V], B)): Eru[Nothing, B] = {
    Eru.effectTotal {
      @annotation.tailrec
      def loop(): B = {
        val atomicRef = map.get(key)
        val currentValue = Option(atomicRef).map(_.get())

        f(currentValue) match {
          case (Some(newValue), result) =>
            if (atomicRef == null) {
              // Key doesn't exist - try to create
              val newRef = new AtomicReference(newValue)
              if (map.putIfAbsent(key, newRef) == null) {
                // Successfully created
                result
              } else {
                // Another thread created it - retry
                loop()
              }
            } else {
              // Key exists - CAS update
              if (atomicRef.compareAndSet(currentValue.orNull, newValue)) {
                result
              } else {
                // CAS failed - retry
                loop()
              }
            }

          case (None, result) =>
            // Remove key
            if (atomicRef != null) {
              map.remove(key, atomicRef)
            }
            result
        }
      }
      loop()
    }
  }

  def get(key: K): Eru[Nothing, Option[V]] =
    Eru.effectTotal(Option(map.get(key)).map(_.get()))

  def set(key: K, value: V): Eru[Nothing, Unit] =
    Eru.effectTotal {
      val ref = map.computeIfAbsent(key, _ => new AtomicReference[V](value))
      ref.set(value)
    }

  def remove(key: K): Eru[Nothing, Option[V]] =
    Eru.effectTotal(Option(map.remove(key)).map(_.get()))

  def keys: Eru[Nothing, Set[K]] =
    Eru.effectTotal {
      import scala.jdk.CollectionConverters.*
      map.keySet().asScala.toSet
    }

  def size: Eru[Nothing, Int] =
    Eru.effectTotal(map.size())

  def clear: Eru[Nothing, Unit] =
    Eru.effectTotal(map.clear())
}
```

**This approach is cleaner** - no need for Ref wrapper, direct CAS per key.

### Implementation Tasks

1. **Create RefMap trait** (new file: `RefMap.scala`)
   - Define full API
   - Clear documentation with examples
   - Document performance characteristics

2. **Implement ConcurrentHashMapRefMap** (in `RefMap.scala`)
   - Use ConcurrentHashMap + AtomicReference per key
   - Implement all methods with proper CAS loops
   - Handle key creation/removal atomically

3. **Add factory methods** (in `RefMap.scala` companion object)
   - `RefMap.make[K, V]` - empty map
   - `RefMap.makeWith[K, V](Map[K, V])` - with initial entries

4. **Write comprehensive tests** (new file: `RefMapSpec.scala`)
   - Concurrent modify on different keys (should not contend)
   - Concurrent modify on same key (should work correctly)
   - Key creation and removal
   - Edge cases (empty, single key, many keys)
   - Stress test: 100 fibers, each updating different keys

5. **Add benchmarks** (new file: `RefMapBench.scala`)
   - Compare: single Ref[Map[K, V]] vs RefMap[K, V]
   - Different scenarios: same key, different keys, mixed
   - Measure contention reduction

6. **Documentation**
   - Usage examples (connection pool, cache, rate limiter)
   - When to use RefMap vs Ref[Map[K, V]]
   - Performance characteristics

### Success Criteria
- ✅ Updates to different keys show zero contention in benchmarks
- ✅ Updates to same key have same semantics as Ref
- ✅ 10x better throughput than Ref[Map[K, V]] for multi-key workloads
- ✅ Memory efficient (no global state copying)

---

## Enhancement 3: Instrumented Ref (Observability)

### Goal
Add optional instrumentation to Ref for debugging and performance tuning in production.

### Design

```scala
/** Ref with metrics tracking */
trait InstrumentedRef[A] extends Ref[A] {

  /** Get current metrics snapshot */
  def metrics: Eru[Nothing, RefMetrics]

  /** Reset metrics counters */
  def resetMetrics: Eru[Nothing, Unit]
}

case class RefMetrics(
  totalOperations: Long,
  totalRetries: Long,
  maxRetriesObserved: Int,
  currentValue: String  // toString of current value, for debugging
) {
  def avgRetriesPerOp: Double =
    if (totalOperations == 0) 0.0
    else totalRetries.toDouble / totalOperations.toDouble
}

object Ref {
  // ... existing methods ...

  /** Create instrumented Ref that tracks contention metrics */
  def makeInstrumented[A](initial: A): Eru[Nothing, InstrumentedRef[A]] =
    Eru.succeed(new InstrumentedRuntimeRef[A](initial))
}
```

### Implementation

```scala
private final class InstrumentedRuntimeRef[A](init: A) extends InstrumentedRef[A] {
  private val state = new java.util.concurrent.atomic.AtomicReference(init)

  // Metrics
  private val totalOps = new java.util.concurrent.atomic.AtomicLong(0)
  private val totalRetries = new java.util.concurrent.atomic.AtomicLong(0)
  private val maxRetries = new java.util.concurrent.atomic.AtomicInteger(0)

  def modify[B](f: A => (A, B)): Eru[Nothing, B] =
    Eru.effectTotal {
      var attempts = 0

      @annotation.tailrec
      def loop(): B = {
        val current = state.get()
        val (next, out) = f(current)

        if (state.compareAndSet(current, next)) {
          // Success - record metrics
          totalOps.incrementAndGet()
          if (attempts > 0) {
            totalRetries.addAndGet(attempts)
            updateMax(attempts)
          }
          out
        } else {
          attempts += 1
          loop()
        }
      }
      loop()
    }

  private def updateMax(observed: Int): Unit = {
    @annotation.tailrec
    def loop(): Unit = {
      val currentMax = maxRetries.get()
      if (observed > currentMax) {
        if (!maxRetries.compareAndSet(currentMax, observed)) {
          loop()
        }
      }
    }
    loop()
  }

  def metrics: Eru[Nothing, RefMetrics] =
    Eru.effectTotal {
      RefMetrics(
        totalOperations = totalOps.get(),
        totalRetries = totalRetries.get(),
        maxRetriesObserved = maxRetries.get(),
        currentValue = state.get().toString
      )
    }

  def resetMetrics: Eru[Nothing, Unit] =
    Eru.effectTotal {
      totalOps.set(0)
      totalRetries.set(0)
      maxRetries.set(0)
    }

  // Implement other Ref methods...
  def get: Eru[Nothing, A] = Eru.succeed(state.get())
  def set(a: A): Eru[Nothing, Unit] = Eru.effectTotal { state.set(a); () }
  def update(f: A => A): Eru[Nothing, A] = ??? // Similar to modify
}
```

### Implementation Tasks

1. **Create RefMetrics case class** (in `Ref.scala` or new file)
2. **Create InstrumentedRef trait** (in `Ref.scala`)
3. **Implement InstrumentedRuntimeRef** (in `Ref.scala`)
4. **Add factory method** (`Ref.makeInstrumented`)
5. **Write tests** (add to `RefSpec.scala` or new file)
6. **Add example usage** in documentation

### Success Criteria
- ✅ Metrics accurately track CAS retries
- ✅ <5% performance overhead vs regular Ref
- ✅ Useful for debugging production issues

---

## Enhancement 4: KeyedSemaphore (Optional - Advanced)

### Goal
Semaphore where permits are tracked per-key, enabling per-resource limits without global contention.

### Use Cases
- Connection pool: max N connections per host
- Rate limiter: max M requests per user per second
- Resource quotas: max K operations per tenant

### Design

```scala
/** Semaphore with independent permit tracking per key */
trait KeyedSemaphore[K] {

  /** Acquire permit for key, suspending until available */
  def acquire(key: K): Suspending[Nothing, Unit]

  /** Try to acquire permit for key without suspending */
  def tryAcquire(key: K): Immediate[Nothing, Boolean]

  /** Release permit for key */
  def release(key: K): Immediate[Nothing, Unit]

  /** Execute effect with permit for key */
  def withPermit[E, A](key: K)(fa: => Eru[E, A]): Suspending[E, A]

  /** Get available permits for key */
  def available(key: K): Immediate[Nothing, Long]
}

object KeyedSemaphore {

  /** Create KeyedSemaphore with same permit count for all keys */
  def make[K](permitsPerKey: Long)(using runtime: EruRuntime): Eru[Nothing, KeyedSemaphore[K]] =
    for {
      semaphores <- RefMap.make[K, Semaphore]
    } yield new RefMapKeyedSemaphore[K](permitsPerKey, semaphores, runtime)

  /** Create KeyedSemaphore with per-key permit counts */
  def makeWith[K](permits: Map[K, Long])(using runtime: EruRuntime): Eru[Nothing, KeyedSemaphore[K]] =
    ???
}
```

### Implementation

```scala
private final class RefMapKeyedSemaphore[K](
  defaultPermits: Long,
  semaphores: RefMap[K, Semaphore],
  runtime: EruRuntime
) extends KeyedSemaphore[K] {

  def acquire(key: K): Suspending[Nothing, Unit] = new Suspending({
    for {
      sem <- getOrCreateSemaphore(key)
      _ <- sem.acquire.eru
    } yield ()
  })

  def tryAcquire(key: K): Immediate[Nothing, Boolean] = new Immediate({
    for {
      sem <- getOrCreateSemaphore(key)
      acquired <- sem.tryAcquire.eru
    } yield acquired
  })

  def release(key: K): Immediate[Nothing, Unit] = new Immediate({
    for {
      sem <- getOrCreateSemaphore(key)
      _ <- sem.release.eru
    } yield ()
  })

  def withPermit[E, A](key: K)(fa: => Eru[E, A]): Suspending[E, A] = new Suspending({
    for {
      sem <- getOrCreateSemaphore(key)
      result <- sem.withPermit(fa).eru
    } yield result
  })

  def available(key: K): Immediate[Nothing, Long] = new Immediate({
    for {
      sem <- getOrCreateSemaphore(key)
      avail <- sem.available.eru
    } yield avail
  })

  private def getOrCreateSemaphore(key: K): Eru[Nothing, Semaphore] = {
    semaphores.modify(key) {
      case Some(sem) => (Some(sem), sem)
      case None =>
        // Create new semaphore for this key
        val sem = Semaphore.make(defaultPermits)(using runtime).unsafeRunSync()
        (Some(sem), sem)
    }
  }
}
```

### Implementation Tasks

1. **Create KeyedSemaphore trait** (new file: `KeyedSemaphore.scala`)
2. **Implement RefMapKeyedSemaphore** (in `KeyedSemaphore.scala`)
3. **Add factory methods**
4. **Write tests** (new file: `KeyedSemaphoreSpec.scala`)
   - Concurrent acquire on different keys (no contention)
   - Concurrent acquire on same key (proper limiting)
   - Release unblocks waiters
5. **Add benchmarks** (compare to manual approach)
6. **Documentation with examples**

### Success Criteria
- ✅ Different keys have zero contention
- ✅ Same key properly enforces limits
- ✅ Cleaner API than manual limit tracking

**Note**: This is optional - might be overkill. Consider implementing only if RefMap proves insufficient for use cases.

---

## Implementation Priority

### Phase 1: Core Enhancements (HIGH PRIORITY)
1. **Ref with Backoff** - Most impactful for existing code
2. **RefMap** - Enables new use cases

### Phase 2: Observability (MEDIUM PRIORITY)
3. **InstrumentedRef** - Helpful for debugging

### Phase 3: Advanced (LOW PRIORITY - Optional)
4. **KeyedSemaphore** - Only if needed

---

## Testing Strategy

### Unit Tests
- Each primitive has comprehensive test suite
- Test concurrent access (100+ fibers)
- Test correctness (all operations succeed)
- Test edge cases (empty, single element, etc.)

### Integration Tests
- Combine primitives (RefMap + Semaphore)
- Real-world patterns (connection pool, cache, rate limiter)

### Benchmarks
- Compare new primitives vs existing approaches
- Measure contention reduction
- Ensure no regression in low-contention scenarios

### Stress Tests
- 1000+ concurrent operations
- Sustained load over time
- Memory leak detection

---

## Success Metrics

### Performance
- ✅ Ref with backoff: <50% CAS retries under high contention vs current
- ✅ RefMap: 10x throughput vs Ref[Map[K, V]] for multi-key workload
- ✅ No regression in low-contention scenarios (<5% overhead)

### Correctness
- ✅ All existing tests pass
- ✅ New tests pass with 1000+ concurrent operations
- ✅ No race conditions or deadlocks

### Usability
- ✅ Clean, composable APIs
- ✅ Clear documentation with examples
- ✅ Migration path from existing code

---

## Documentation Requirements

For each new primitive:

1. **ScalaDoc** - Full API documentation
2. **Usage examples** - At least 3 realistic scenarios
3. **Performance characteristics** - When to use vs alternatives
4. **Migration guide** - How to adopt in existing code

---

## Non-Goals

- ❌ Change existing Ref API (maintain backward compatibility)
- ❌ Distributed coordination (these are single-JVM primitives)
- ❌ Persistence (state is in-memory only)
- ❌ STM (Software Transactional Memory) - out of scope

---

## Questions to Resolve

1. **Ref backoff default behavior**:
   - Option A: Keep None as default (backward compatible)
   - Option B: Make Adaptive the new default (better perf, slight breaking change)
   - **Recommendation**: Start with Option A for safety

2. **RefMap null handling**:
   - Should `Option[V]` be used everywhere?
   - Or allow null values (Java compatibility)?
   - **Recommendation**: Use Option[V] for safety

3. **KeyedSemaphore complexity**:
   - Is this needed or is RefMap + manual logic sufficient?
   - **Recommendation**: Defer until concrete use case demands it

---

## Next Steps

1. **Review this design** - Gather feedback
2. **Start with Phase 1** - Implement Ref backoff + RefMap
3. **Write comprehensive tests** - Ensure correctness
4. **Benchmark against existing code** - Validate improvements
5. **Document patterns** - Help users adopt new primitives
6. **Iterate based on real usage** - Dogfooding will reveal gaps

---

**Remember**: The goal is to make Eru's primitives excellent for real-world use cases. These enhancements should feel natural, composable, and performant. Quality over quantity!
