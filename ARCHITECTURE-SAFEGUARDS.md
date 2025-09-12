# Eru Architecture Safeguards: Preventing Global State Issues

This document establishes architectural safeguards to prevent the global shared state issues that can compromise Eru's ability to run multiple concurrent applications.

## The Problem We're Preventing

**Global shared state** creates resource contention between multiple Eru application instances, making it impossible to run concurrent Eru applications. This completely undermines Eru's promise as a reliable effect system.

### Examples of Problematic Patterns

```scala
// ❌ FORBIDDEN: Global atomic counters
object BadId {
  private val counter = new AtomicLong(1L) // SHARED ACROSS ALL ERU INSTANCES!
  def fresh(): BadId = counter.getAndIncrement()
}

// ❌ FORBIDDEN: Global mutable collections  
object BadRegistry {
  private val globalCache = new ConcurrentHashMap[String, Any]() // SHARED STATE!
}

// ❌ FORBIDDEN: Global lazy vals with side effects
object BadSingleton {
  private lazy val executor = Executors.newFixedThreadPool(10) // SHARED EXECUTOR!
}
```

## The Solution: Process-Unique Starting Points

For any global ID generation, use process-unique starting points:

```scala
// ✅ CORRECT: Process-unique starting points
object GoodId {
  private val processUniqueStart = {
    val processId = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.hashCode & 0xffffL
    val nanoTime = (System.nanoTime() >> 16) & 0xffffffffffffL
    (processId.toLong << 48) | (nanoTime & 0xffffL) | 0x1000L // Unique offset per ID type
  }
  private val counter = new AtomicLong(processUniqueStart)
  def fresh(): GoodId = counter.getAndIncrement()
}
```

## Architectural Safeguards

### 1. Code Review Checklist

**Before merging any PR, verify:**

- [ ] No `AtomicLong(1L)`, `AtomicInteger(0)`, or similar with hardcoded starting values
- [ ] No global `ConcurrentHashMap`, `ConcurrentLinkedQueue` without runtime-local scoping  
- [ ] No shared thread pools or executors in `object` declarations
- [ ] No `lazy val` with side effects at object level
- [ ] Any ID generation uses process-unique starting points

### 2. Automated Detection Rules

**Add to build.sbt:**

```scala
// Static analysis rules to catch global state issues
scalacOptions ++= Seq(
  "-Wunused:nowarn", // Catch unused global state
  "-Xlint:adapted-args", // Catch problematic patterns
)

wartremoverErrors ++= Seq(
  Wart.MutableDataStructures, // Catch global mutables
  Wart.Var,                   // Catch global vars
)
```

**Scalafix rules to add:**

```scala
// Custom rule to detect problematic atomic patterns
rules = [
  DisallowGlobalAtomics,
  DisallowHardcodedCounters
]
```

### 3. Testing Strategies

**Multi-Instance Test Pattern:**

```scala
test("multiple Eru applications run concurrently without interference") {
  val numApps = 3
  val barrier = new CyclicBarrier(numApps)
  val results = Array.ofDim[Boolean](numApps)
  
  val threads = (0 until numApps).map { i =>
    new Thread(s"EruApp-$i") {
      override def run(): Unit = {
        given runtime: EruRuntime = EruRuntime.create()
        try {
          barrier.await() // Ensure truly concurrent execution
          
          // Stress test ID generation and concurrent operations
          val operations = (1 to 10000).map { _ =>
            runtime.fork(Eru.succeed("test")).flatMap(_.await)
          }
          
          val result = runtime.parSequence(operations).unsafeRunSync()
          results(i) = result.size == 10000
          
        } finally {
          runtime.cleanup()
        }
      }
    }
  }
  
  threads.foreach(_.start())
  threads.foreach(_.join())
  
  assert(results.forall(identity), "All Eru instances should succeed independently")
}
```

### 4. Architecture Patterns

**Runtime-Local Pattern:**

```scala
// ✅ CORRECT: Runtime-local state
final class EruRuntime(backend: ConcurrencyBackend) {
  private val localIdGenerator = IdGenerator.create() // Each runtime gets its own
  
  def createFiber[E, A](effect: Eru[E, A]): Fiber[E, A] = {
    val id = localIdGenerator.next() // Runtime-local ID
    // ...
  }
}
```

**Dependency Injection Pattern:**

```scala
// ✅ CORRECT: Inject dependencies rather than global singletons
trait IdGenerator {
  def next(): Long
}

class ProcessUniqueIdGenerator(offset: Long) extends IdGenerator {
  private val processUniqueStart = {
    val processId = ManagementFactory.getRuntimeMXBean.getName.hashCode & 0xffffL
    val nanoTime = System.nanoTime() & 0xffffffffffffL
    (processId.toLong << 48) | (nanoTime & 0xffffL) | offset
  }
  private val counter = new AtomicLong(processUniqueStart)
  
  def next(): Long = counter.getAndIncrement()
}
```

### 5. Build-Time Verification

**Add to CI pipeline:**

```bash
# Grep for forbidden patterns
if grep -r "AtomicLong(1L)" eru-core eru-runtime; then
  echo "❌ FORBIDDEN: Found hardcoded AtomicLong(1L) - use process-unique starting point"
  exit 1
fi

if grep -r "AtomicInteger(0)" eru-core eru-runtime; then
  echo "❌ FORBIDDEN: Found hardcoded AtomicInteger(0) - use process-unique starting point"  
  exit 1
fi

if grep -r "lazy val.*Executor" eru-core eru-runtime; then
  echo "❌ FORBIDDEN: Found global lazy executor - use runtime-local executors"
  exit 1
fi
```

### 6. Documentation Requirements

**All new ID types must document:**

1. **Isolation**: How the ID generation avoids global contention
2. **Uniqueness**: The entropy sources used for process uniqueness  
3. **Testing**: Evidence that multiple instances don't interfere

**Example documentation:**

```scala
/** Process-unique fiber identifier generation.
 * 
 * ISOLATION: Each process gets a unique starting range to prevent contention
 * between multiple concurrent Eru applications.
 * 
 * UNIQUENESS: Combines process ID hash, nanosecond timing, and type-specific
 * offset to ensure uniqueness across processes and ID types.
 * 
 * TESTING: Verified in MultipleEruInstancesSpec that concurrent applications
 * generate non-overlapping IDs without performance degradation.
 */
object FiberId { ... }
```

## Implementation Checklist

- [x] Fixed FiberId global counter with process-unique starting point
- [x] Fixed SpanId global counter with process-unique starting point  
- [x] Fixed TraceId global counter with process-unique starting point
- [x] Fixed ScopeId global counter with process-unique starting point
- [x] Verified all concurrency tests pass without hanging
- [ ] Add automated detection rules to build
- [ ] Add multi-instance tests to CI pipeline  
- [ ] Document all existing ID generation patterns
- [ ] Create code review checklist for maintainers

## Emergency Response Plan

If global state issues are discovered:

1. **Immediate**: Add process-unique starting points using the established pattern
2. **Short-term**: Create regression test demonstrating the fix
3. **Long-term**: Analyze how the issue passed code review and strengthen safeguards

This systematic approach ensures Eru remains a truly reliable effect system capable of running multiple concurrent applications without interference.