# Eru Implementation - File Reference Guide

## Overview

This document provides a comprehensive navigation guide for Eru's codebase, mapping key implementation files to their critical code paths, functions, and data structures. Use this as a reference for locating specific implementation details and understanding the flow between components.

## 1. Core Implementation Files

### 1.1 RuntimeBackend.scala

**Location:** `/home/user/eru/eru-runtime/shared/src/main/scala/net/ghoula/eru/RuntimeBackend.scala`

**File Size:** 530 lines

**Purpose:** Enum-based backend implementation for synchronous and virtual thread execution

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 7-9 | FiberScope class | Child fiber tracking structure |
| 10-85 | StructuredConcurrency object | ThreadLocal scope management |
| 87-530 | RuntimeBackend enum | Fork, race, sleep implementations |
| 122-281 | fork method | Fiber creation (fast and slow paths) |
| 292-374 | race method | Concurrent computation racing |
| 383-394 | sleep method | Duration-based delays |
| 434-470 | forkBatch method | Batch fiber creation optimization |
| 481-495 | awaitAll method | Batch fiber awaiting |
| 505-507 | cleanup method | Root fiber cleanup |

#### Key Data Structures

```scala
// Scope tracking for structured concurrency
FiberScope {
  childFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]]
}

// ThreadLocal scope propagation
private val currentScope: ThreadLocal[Option[FiberScope]]
```

#### Critical Code Paths

**Path 1: Fork Pure Value**
```
RuntimeBackend.fork()
  → Eru.Internals.view(fa)
    → VSucceed case
      → effectTotal
        → FiberId.fresh()
        → UnifiedFiber.completed()
        → observer event
```

**Path 2: Fork Effectful Value**
```
RuntimeBackend.fork()
  → Eru.Internals.view(fa)
    → default case
      → effectTotal
        → FiberId.fresh()
        → UnifiedFiber.active()
        → getCurrentScope() [scope capture]
        → addChildFiber()
        → Thread.startVirtualThread()
          → setThread()
          → setCurrentScope() [scope restore]
          → withNewScope()
            → executeWithFinalizers()
            → complete()
```

**Path 3: Scope Cleanup on Exit**
```
withNewScope()
  → while childFibers.poll()
    → fiber.interrupt(ParentTerminated)
    → fiber.await [synchronous]
```

#### Test Coverage

- `RuntimeBackendSpec.scala` (302 lines): Backend behavior tests
- `VTForkSpec.scala` (69 lines): Virtual thread fork operations
- `VirtualThreadsBackendSpec.scala` (199 lines): Backend-specific tests
- `StructuredConcurrencySpec.scala`: Scope propagation tests

---

### 1.2 UnifiedFiber.scala

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/UnifiedFiber.scala`

**File Size:** 188 lines

**Purpose:** Fiber state machine with active/completed states and coordination primitives

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 11-34 | UnifiedFiberState enum | Active/Completed state ADT |
| 47-110 | UnifiedFiber class | Fiber implementation |
| 60-73 | await method | Wait for fiber completion |
| 85-93 | interrupt method | Send interruption signal |
| 112-187 | UnifiedFiber object | Factory methods |
| 126-127 | completed method | Create completed fiber |
| 139-144 | active method | Create active fiber with latches |
| 156-167 | complete method | Transition to completed |
| 179-186 | setThread method | Store executing thread |

#### State Machine Diagram

```
Creation:
active[E, A](id) → Active(CountDownLatch(1), exitRef, threadRef)

During Execution:
setThread(fiber, thread) → threadRef set to Some(thread)

On Completion:
complete(fiber, exit) → exitRef.set(exit); latch.countDown()
                       → Threads waiting on latch wake up

State Property:
Completed(exit) ← immutable, final state
Active(...) → transitions to Completed once
```

#### Key Data Structures

```scala
enum UnifiedFiberState[+E, +A] {
  case Completed(exit: Exit[E, A])
  case Active[E, A](
    latch: CountDownLatch,           // Coordination for waiters
    exitRef: AtomicReference[Exit[E, A]], // Result storage
    threadRef: AtomicReference[Option[Thread]] // For interruption
  )
}

final class UnifiedFiber[+E, +A](
  val id: FiberId,
  private val state: UnifiedFiberState[E, A]
)
```

#### Coordination Protocol

```
Fork time (Active created):
- CountDownLatch(1) created [count=1]
- exitRef = AtomicReference() [null]
- threadRef = AtomicReference() [None]

Runtime sets thread:
- UnifiedFiber.setThread(fiber, Thread.currentThread())
- threadRef.set(Some(thread))

On completion (finish or interrupt):
- exitRef.set(exit)
- latch.countDown() [count becomes 0]

Awaiter blocked on:
- latch.await() [blocks until count=0]
- Then retrieves exitRef.get()
```

#### Test Coverage

- `UnifiedFiberSpec.scala`: State machine tests
- `FiberSpec.scala`: Fiber lifecycle tests

---

### 1.3 Exit.scala

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Exit.scala`

**File Size:** 516 lines

**Purpose:** Exit outcome types, FiberId generation, InterruptCause hierarchy

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 16-46 | Exit enum | Four outcome types |
| 49-115 | FiberId opaque type | Unique fiber identifier |
| 117-267 | InterruptCause enum | Interrupt reason taxonomy |
| 185-267 | InterruptCause cases | Cancelled, Timeout, ParentTerminated, ResourceExhausted, Custom |

#### FiberId Generation Strategy

```scala
opaque type FiberId = Long

object FiberId {
  // Layout: [0][15-bit processId][48-bit timestamp/counter]
  private val processUniqueStart = {
    val ProcessIdBits = 15
    val ProcessIdMask = (1L << ProcessIdBits) - 1
    val TimestampMask = (1L << 48) - 1
    
    val processId = ManagementFactory.getRuntimeMXBean
      .getName.hashCode.toLong & ProcessIdMask
    val timestamp = System.nanoTime() & TimestampMask
    
    (processId << 48) | timestamp
  }
  
  private val next = new AtomicLong(processUniqueStart)
  
  def fresh(): FiberId = next.getAndIncrement()
}
```

**Uniqueness Guarantee:**
- Process ID: 15 bits (32K unique processes)
- Timestamp: 48 bits (281 trillion IDs per process)
- Monotonic counter with AtomicLong
- No chance of collision in typical deployments

#### InterruptCause Hierarchy

| Type | Fields | Semantics |
|------|--------|-----------|
| Cancelled | reason: Option[String] | User/system cancellation |
| Timeout | duration: Duration, operation: Option[String] | Time exceeded |
| ParentTerminated | parentId: FiberId, parentExit: Exit | Scope exit |
| ResourceExhausted | resource: String, details: Option[String] | System limits |
| Custom | name: String, context: Option[String], metadata: Map | Application-specific |

#### Test Coverage

- `ExitSpec.scala`: Exit semantics
- `FiberId` generation guarantees tested indirectly

---

### 1.4 Eru.scala

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/Eru.scala`

**File Size:** 1738 lines

**Purpose:** Core effect type, interpreter, continuations, finalizers

#### Critical Sections (by complexity)

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 25-87 | Eru enum cases | Effect constructors (Succeed, Fail, Effect, Chain, etc.) |
| 99-118 | map method | Functor map with MapChain fusion |
| 131-165 | flatMap method | Monad flatMap with continuation stack |
| 175 | mapError method | Error channel transformation |
| 192 | zip method | Sequential combination |
| 71-72 | Ensure case | Finalizer registration |
| 73-74 | Suspend case | Async suspension |
| 76-77 | Fork case | Fiber spawning |
| 79-80 | Await case | Fiber joining |
| 86 | InterruptibleBlocking case | Interruptible blocking operations |

#### Effect Interpreter Loop (lines 800+)

```scala
private[eru] def executeWithFinalizers[E, A](
  computation: Eru[E, A]
): (Exit[E, A], List[() => Eru[Nothing, Unit]]) = {
  // Tail-recursive interpreter
  // Accumulates finalizers as it executes
  // Returns (exit, finalizers) for caller to execute
}
```

#### Continuation Stack (lines 1100+)

```scala
sealed trait Continuation[E0, From, To] {
  def andThen[E2, To2](next: Continuation[E0, To, To2]): Continuation[E0, From, To2]
}

case class Step[E0, From, To](
  f: From => Eru[E0, To],
  next: Continuation[E0, To, ?]
) extends Continuation[E0, From, To]
```

#### Key Methods

**map:** Lines 99-118
- Constructs MapChain for fusion
- Optimizes consecutive maps
- Inlines pure values immediately

**flatMap:** Lines 131-165
- Creates Chain with continuation
- Stack-safe via tail recursion
- Preserves type safety

**executeWithFinalizers:** Lines 800+
- Interprets effect tree
- Accumulates finalizers
- Returns (exit, finalizers) pair

**Internal View Pattern:** Lines 600+
```scala
object Internals {
  object View {
    def unapply[E, A](eru: Eru[E, A]): View[E, A]
  }
  
  enum View[+E, +A] {
    case VSucceed(value: A)
    case VFail(error: E)
    case VMapChain(source: Eru[E, ?], f: Any => A)
    // ... other patterns
  }
}
```

#### Test Coverage

- `EruRuntimeSpec.scala`: Runtime behavior
- `SuspensionSafetySpec.scala`: Suspension safety
- `ImmediateCompositionSpec.scala`: Immediate values
- `SuspendingCompositionSpec.scala`: Suspending values

---

### 1.5 EruObserver.scala

**Location:** `/home/user/eru/eru-core/src/main/scala/net/ghoula/eru/EruObserver.scala`

**File Size:** 516 lines

**Purpose:** Observer interface and event system for execution tracing

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 33-54 | ScopeId opaque type | Unique execution scope identifier |
| 78-109 | Outcome enum | Success, TypedFailure, Defect |
| 184-334 | EruEvent enum | 10+ event types |
| 224-232 | FiberStarted event | Fiber creation notification |
| 234-245 | FiberCompleted event | Fiber completion with exit |
| 247-258 | FiberInterrupted event | Interruption notification |
| 273-300 | Structured cleanup events | Cleanup lifecycle events |
| 336-398 | EruObserver trait | Observer interface |
| 400-521 | StructuredConcurrencyObserver | Specialized observer trait |

#### Event Type Reference

```scala
sealed trait EruEvent
  case ProgramStart(scopeId: ScopeId)
  case ProgramEnd(scopeId: ScopeId, outcome: Outcome)
  case Step(scopeId: ScopeId, label: String)
  case FiberStarted(fiberId: FiberId)
  case FiberCompleted(fiberId: FiberId, exit: Exit[Any, Any])
  case FiberInterrupted(fiberId: FiberId, cause: InterruptCause)
  case FiberForked(parentId: FiberId, childId: FiberId)
  case StructuredCleanupStarted(fiberId: FiberId, childCount: Int)
  case StructuredCleanupCompleted(fiberId: FiberId, interruptedCount: Int, completedCount: Int)
  case ChildInterruptionRequested(parentId: FiberId, childId: FiberId, cause: InterruptCause, childWasRunning: Boolean)
  case TraceSpan(span: net.ghoula.eru.trace.EruTrace.Span)
```

#### Observer Usage Pattern

```scala
val observer = new EruObserver {
  def onEvent(event: EruEvent): Unit = event match {
    case FiberStarted(id) => println(s"Fiber $id started")
    case FiberCompleted(id, exit) => println(s"Fiber $id completed with $exit")
    case _ => ()
  }
}

runtime.forkWithObserver(effect, observer)
```

#### Test Coverage

- Various test files use observer for assertions

---

### 1.6 SuspensionTypes.scala

**Location:** `/home/user/eru/eru-runtime/shared/src/main/scala/net/ghoula/eru/SuspensionTypes.scala`

**File Size:** 290 lines

**Purpose:** Type-level suspension safety via Suspending and Immediate wrappers

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 32-153 | Suspending class | Wraps indefinitely suspending effects |
| 155-287 | Immediate class | Wraps non-suspending effects |
| 47-125 | Suspending methods | map, flatMap, zip, recover, fork, race, timeout |
| 194-286 | Immediate methods | map, flatMap, zip, recover, unsafeRunSync, fork |

#### Type Safety Mechanism

**Suspending Type:**
```scala
final class Suspending[+E, +A](val eru: Eru[E, A]) extends AnyVal {
  // NO unsafeRunSync method - enforces async execution
  def fork(using runtime: EruRuntime): Eru[Nothing, Fiber[E, A]]
  def timeout(duration: Duration)(using runtime: EruRuntime): Immediate[E | Throwable, A]
}
```

**Immediate Type:**
```scala
final class Immediate[+E, +A](val eru: Eru[E, A]) extends AnyVal {
  // HAS unsafeRunSync method - can run synchronously
  def unsafeRunSync(): A
  def suspending: Suspending[E, A]  // Safe widening
}
```

**Zero Overhead:**
- Both are value classes (AnyVal)
- Compiler erases wrapper at bytecode level
- No runtime allocation cost

#### Test Coverage

- `SuspendingCompositionSpec.scala`: Suspending composition
- `ImmediateCompositionSpec.scala`: Immediate composition
- `SuspensionSystemSpec.scala`: Type safety enforcement

---

### 1.7 RuntimeBackendAdapter.scala

**Location:** `/home/user/eru/eru-runtime/jvm/src/main/scala/net/ghoula/eru/internal/RuntimeBackendAdapter.scala`

**File Size:** 177 lines

**Purpose:** Bridge between RuntimeBackend enum and ConcurrencyBackend trait interface

#### Critical Sections

| Line Range | Component | Purpose |
|-----------|-----------|---------|
| 16-36 | RuntimeBackendAdapter class | Constructor and capabilities |
| 18 | rootFibers field | Per-instance fiber queue |
| 21 | privateExecutor field | Lazy virtual thread executor |
| 38-57 | computeExit method | Execute effect and handle result |
| 59-66 | fork/forkBatch/awaitAll methods | Delegate to backend |
| 68-76 | race/sleep/timeout methods | Delegate to backend |
| 77-101 | retry method | Retry with exponential backoff |
| 103-157 | handleSuspend method | Async registration |
| 159-163 | cleanup method | Root fiber cleanup |

#### Key Design Decisions

**Per-Instance State:**
```scala
private val rootFibers: ConcurrentLinkedQueue[UnifiedFiber[?, ?]] = 
  new ConcurrentLinkedQueue()
```
- Each adapter has own fiber queue
- Enables test isolation
- No shared mutable state between runtimes

**Lazy Executor:**
```scala
private lazy val privateExecutor = 
  java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
```
- Created only when first used
- Avoids executor overhead for synchronous code
- Not closed (GC cleanup to avoid blocking)

#### Delegation Pattern

```scala
def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
  backend.fork(fa, observer, Some(rootFibers))
```

Passes rootFibers queue to backend for tracking.

---

## 2. Key Data Paths

### Fork Operation End-to-End

```
User Code:
  effect.fork(using runtime)

↓ EruRuntime:
  backend.fork(fa, None)

↓ RuntimeBackendAdapter:
  backend.fork(fa, None, Some(rootFibers))

↓ RuntimeBackend.fork():
  1. Eru.Internals.view(fa) - fast path check
  2. VirtualThreads case:
     a. FiberId.fresh()
     b. UnifiedFiber.active()
     c. getCurrentScope() - ThreadLocal access
     d. addChildFiber() - register in scope
     e. Thread.startVirtualThread():
        - setThread()
        - setCurrentScope()
        - withNewScope():
          - executeWithFinalizers()
          - complete()
          - observer events

↓ Returns:
  Eru[Nothing, Fiber[E, A]]
```

### Await Operation End-to-End

```
User Code:
  fiber.await

↓ UnifiedFiber.await:
  state match:
    case Completed(exit) → Eru.succeed(exit)
    case Active(latch, exitRef, _) →
      Eru.interruptibleBlocking {
        latch.await()  // Block until countDown()
        exitRef.get()
      }

↓ Returns:
  Eru[Nothing, Exit[E, A]]
```

### Interrupt Operation End-to-End

```
User Code:
  fiber.interrupt(cause)

↓ UnifiedFiber.interrupt:
  state match:
    case Completed(_) → Eru.unit
    case Active(_, _, threadRef) →
      threadRef.get().foreach(_.interrupt())
      // Thread.interrupt() called

↓ In VirtualThread:
  Running interruptibleBlocking?
    → InterruptedException thrown
    → Caught by interpreter
    → InterruptedWithFinalizers created
    → Exit.Interrupt produced
    → Finalizers executed

↓ Returns:
  Eru[Nothing, Unit]
```

---

## 3. Critical Code Paths with Line Numbers

### Path 1: Pure Value Fork Optimization

**File:** RuntimeBackend.scala
```
line 122: def fork() method entry
line 151:   case VirtualThreads branch
line 152:     Eru.Internals.view(fa) match
line 154:       case VSucceed(value) - fast path start
line 155:         Eru.effectTotal {
line 156:           val id = FiberId.fresh()
line 157:           observer.foreach(_.onEvent(...FiberStarted...))
line 158:           val exit = Exit.Success(value)
line 159:           observer.foreach(_.onEvent(...FiberCompleted...))
line 160:           UnifiedFiber.completed(id, exit)
line 161:         }
```

### Path 2: Effectful Value Fork

**File:** RuntimeBackend.scala
```
line 244:   case _ (default, needs execution)
line 245:     Eru.effectTotal {
line 246:       val id = FiberId.fresh()
line 246:       val fiber = UnifiedFiber.active[E, A](id)
line 247:       val parentScope = StructuredConcurrency.getCurrentScope()
line 249:       StructuredConcurrency.addChildFiber(fiber, rootFibers)
line 251:       observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))
line 253:       Thread.startVirtualThread { () =>
line 254:         UnifiedFiber.setThread(fiber, Thread.currentThread())
line 256:         StructuredConcurrency.setCurrentScope(parentScope)
line 258:         StructuredConcurrency.withNewScope { _ =>
line 259:           val (exit, finalizers) = Eru.executeWithFinalizers(fa)
line 261:           finalizers.foreach { finalizer =>
line 262:             try finalizer().unsafeRunSync()
line 263:             catch case _: Exception => ()
line 264:           }
line 266:           UnifiedFiber.complete(fiber, exit)
line 267:           observer.foreach(_.onEvent(...FiberCompleted...))
line 268:         }
line 269:       }
line 271:       fiber: Fiber[E, A]
line 272:     }
```

### Path 3: Scope Cleanup on Exit

**File:** RuntimeBackend.scala
```
line 17:  def withNewScope[A](action: FiberScope => A): A = {
line 18:    val newScope = new FiberScope(...)
line 20:    setCurrentScope(Some(newScope))
line 22:      action(newScope)
line 24:      while (child.nonEmpty) {
line 25:        val fiber = child.get
line 27:          fiber.interrupt(InterruptCause.ParentTerminated(...))
line 29:          fiber.await.attempt.unsafeRunSync()
line 35:      setCurrentScope(oldScope)
```

### Path 4: Race CAS Winner

**File:** RuntimeBackend.scala
```
line 308:   def trySet(thunk, cancelOther) =
line 309:     if (resultRef.compareAndSet(None, Some(thunk))) {
line 310:       cancelOther()
line 311:       latch.countDown()
line 312:     }
line 356: Thread.startVirtualThread(runLeft)
line 357: Thread.startVirtualThread(runRight)
line 358: try {
line 359:   latch.await()
line 360:   resultRef.get()
```

---

## 4. Test Coverage Mapping

### Structured Concurrency Tests

| Test File | Location | Coverage |
|-----------|----------|----------|
| StructuredConcurrencySpec | jvm/src/test | Parent-child relationships, auto-join |
| FiberLifecycleSpec | jvm/src/test | Fiber creation, completion, interruption |
| FiberInterruptionSpec | jvm/src/test | Interrupt mechanisms, causes |
| FiberFinalizerIntegrationSpec | jvm/src/test | Finalizer ordering, FILO semantics |
| RuntimeBackendSpec | jvm/src/test | Backend-specific behavior |
| VirtualThreadsBackendSpec | jvm/src/test | Virtual thread integration |

### Virtual Thread Fork Tests

| Test File | Focus |
|-----------|-------|
| VTForkSpec | Fork operation on virtual threads |
| ForkSpec (shared) | Fork semantics |
| FiberExecutionSpec | Fiber execution patterns |
| FiberPropertySpec | Property-based testing |

### Suspension Tests

| Test File | Focus |
|-----------|-------|
| SuspensionSystemSpec | Suspension mechanism |
| SuspendingCompositionSpec | Suspending type composition |
| ImmediateCompositionSpec | Immediate type composition |
| SuspensionSafetySpec | Type-level safety |

---

## 5. Integration Points

### EruRuntime ↔ Backend

**File:** EruRuntime.scala
```scala
def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] =
  backend.fork(fa, None)
```

### Backend ↔ RuntimeBackend

**File:** RuntimeBackendAdapter.scala
```scala
def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver]): Eru[Nothing, Fiber[E, A]] =
  backend.fork(fa, observer, Some(rootFibers))
```

### RuntimeBackend ↔ Fiber

**File:** RuntimeBackend.scala
```scala
UnifiedFiber.active[E, A](id)  // Create
UnifiedFiber.setThread(fiber, thread)  // Set thread ref
UnifiedFiber.complete(fiber, exit)  // Complete
```

### UnifiedFiber ↔ Coordination

**File:** UnifiedFiber.scala
```scala
latch.countDown()  // From completion
exitRef.set(exit)  // From completion
threadRef.set(Some(thread))  // From setThread
```

---

## 6. Key Algorithms

### FiberId Generation

**File:** Exit.scala, lines 78-88
```scala
Layout: [0][15-bit processId][48-bit timestamp]
Process ID: hash of JVM process name
Timestamp: nanoTime based
Counter: Atomic increment
```

### Scope Cleanup

**File:** RuntimeBackend.scala, lines 17-37
```scala
Algorithm: LIFO queue drain with await
Guarantee: All children interrupted and awaited
Atomicity: Scope switch is atomic
```

### Race Winner Selection

**File:** RuntimeBackend.scala, lines 308-312
```scala
Algorithm: CAS on AtomicReference
Winner: First compareAndSet succeeds
Loser: Interrupted and discarded
```

### Incremental Cleanup

**File:** RuntimeBackend.scala, lines 54-63
```scala
Algorithm: One fiber cleanup per fork
Cost: O(1) amortized
Space: O(k) where k = max active root fibers
```

---

## 7. Configuration and Constants

### Default Configurations

**Virtual Thread Executor:**
```scala
Executors.newVirtualThreadPerTaskExecutor()
```

**CountDownLatch:**
```scala
CountDownLatch(1)  // Single-use, efficient
```

**ConcurrentLinkedQueue:**
```scala
ConcurrentLinkedQueue[UnifiedFiber[?, ?]]()
```

---

## Conclusion

This reference guide maps Eru's implementation across key files and code locations. Use it to:

1. **Locate implementations** for specific features
2. **Follow code paths** through the system
3. **Understand data structures** and their relationships
4. **Trace execution flows** for debugging
5. **Find test coverage** for validation

The system's architecture around ThreadLocal scope propagation, atomic coordination, and virtual thread integration creates a powerful foundation for structured concurrent programming.

