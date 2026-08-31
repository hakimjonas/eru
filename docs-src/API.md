# Eru API Reference

This document is a reference to Eru's public API, organized by functionality area.

## Core effect type

### `Eru[E, A]`

The foundational effect type representing a computation that may fail with error `E` or succeed with value `A`.

```scala
// Core constructors
Eru.succeed[A](value: A): Eru[Nothing, A]
Eru.fail[E](error: E): Eru[E, Nothing]
Eru.effect[A](computation: => A): Eru[Throwable, A]
Eru.blocking[A](computation: => A): Eru[Throwable, A]

// Iterative builders (stack-safe)
Eru.iterate[E, A](initial: A)(f: A => Eru[E, A])(predicate: A => Boolean): Eru[E, A]
Eru.iterateN[E, A](start: A, n: Int)(step: A => Eru[E, A]): Eru[String | E, A]
Eru.unfold[E, A, B](seed: A)(f: A => Eru[E, Option[(B, A)]]): Eru[E, List[B]]
Eru.sequence[E, A](effects: List[Eru[E, A]]): Eru[E, List[A]]
Eru.traverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, List[B]]

// Collection operations
Eru.collectAll[E, A](effects: Iterable[Eru[E, A]]): Eru[E, List[A]]
Eru.collectAllDiscard[E, A](effects: Iterable[Eru[E, A]]): Eru[E, Unit]
Eru.foreach[E, A, B](inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E, List[B]]
Eru.foreachDiscard[E, A, B](inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E, Unit]
Eru.partition[E, A](inputs: Iterable[A])(f: A => Eru[E, Boolean]): Eru[E, (List[A], List[A])]

// Aggregation operations
Eru.foldLeft[E, A, S](inputs: Iterable[A])(zero: S)(f: (S, A) => Eru[E, S]): Eru[E, S]
Eru.foldRight[E, A, S](inputs: Iterable[A])(zero: S)(f: (A, S) => Eru[E, S]): Eru[E, S]

// Conditional operations
Eru.when[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit]
Eru.unless[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit]
Eru.cond[A](condition: Boolean, onTrue: A, onFalse: A): Eru[Nothing, A]

// Control flow
Eru.forever[E](effect: Eru[E, Unit]): Eru[E, Nothing]
Eru.repeatN[E, A](n: Int)(effect: Eru[E, A]): Eru[E, Unit]
Eru.repeatUntil[E, A](effect: Eru[E, A])(predicate: A => Boolean): Eru[E, A]

// Transformation
map[B](f: A => B): Eru[E, B]
flatMap[E1 >: E, B](f: A => Eru[E1, B]): Eru[E1, B]
zip[E2, B](that: Eru[E2, B]): Eru[E | E2, (A, B)]

// Error handling  
recover[A1 >: A](pf: PartialFunction[E, A1]): Eru[E, A1]
recoverWith[E2, A1 >: A](pf: PartialFunction[E, Eru[E2, A1]]): Eru[E | E2, A1]
attempt: Eru[Nothing, Result[E, A]]
orElse[E2, A1 >: A](that: => Eru[E2, A1]): Eru[E | E2, A1]

// Execution
unsafeRunSync(): A  // May throw
unsafeRunSyncWith(observer: EruObserver): A
```

## Iterative construction

Stack-safe builders for common iteration patterns:

```scala
// Iterate until a predicate is satisfied
def iterate[E, A](initial: A)(f: A => Eru[E, A])(predicate: A => Boolean): Eru[E, A]

// Iterate exactly N times
def iterateN[E, A](start: A, n: Int)(step: A => Eru[E, A]): Eru[String | E, A]

// Build a list by unfolding from a seed value
def unfold[E, A, B](seed: A)(f: A => Eru[E, Option[(B, A)]]): Eru[E, List[B]]

// Execute effects sequentially, collecting results
def sequence[E, A](effects: List[Eru[E, A]]): Eru[E, List[A]]

// Map and sequence in one operation
def traverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, List[B]]
```

**Usage Examples:**

```scala
// Generate first 10 squares
val squares = Eru.iterateN(0, 10)(i => Eru.succeed(i + 1)).map(_ * _)

// Generate Fibonacci sequence up to 1000
val fibs = Eru.unfold((0, 1)) { case (a, b) =>
  if (a > 1000) Eru.succeed(None)
  else Eru.succeed(Some((a, (b, a + b))))
}

// Process list of items safely
val processed = Eru.traverse(items)(item => processItem(item))
```

## Concurrency & fibers

### Core fiber operations

```scala
// Fork computation onto new fiber
def fork: Eru[Nothing, Fiber[E, A]]

// Extension methods (via RuntimeExtensions)
def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]]
```

### `Fiber[E, A]`

Handle to a running or completed computation.

```scala
trait Fiber[+E, +A] {
  def id: FiberId
  def await: Eru[Nothing, Exit[E, A]]  
  def interrupt(cause: InterruptCause): Eru[Nothing, Unit]
}
```

### `EruRuntime` operations

```scala
object EruRuntime {
  // Basic concurrency
  def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]]
  def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]]

  // Parallel composition
  def zipPar[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)]
  def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]]
  def parTraverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]]

  // Degree-limited parallel operations
  def foreachParN[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]]
  def foreachParNDiscard[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, Unit]

  // Racing
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]]
  def raceAll[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, (A, Int)]

  // Time-based operations
  def sleep(duration: java.time.Duration)(using net.ghoula.eru.time.Monotonic): Eru[Nothing, Unit]
  def timeout[E, A](duration: java.time.Duration)(fa: Eru[E, A])(using
    net.ghoula.eru.time.Monotonic
  ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A]

  // Retry operations (policy-based; retryN / retryWithBackoff are extensions, see below)
  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A]

  // Async boundaries
  def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A]

  // Validation patterns
  def validatePar[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[List[E], List[A]]]
  def validateFirst[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[E | Throwable, List[A]]]
}
```

Constructors and scheduling primitives live on the `Eru` companion and are available via the prelude:

```scala
// Coordination primitives
Eru.ref[A](initial: A): Eru[Nothing, Ref[A]]
Eru.deferred[A]: Eru[Nothing, Deferred[A]]
Eru.promise[E, A]: Eru[Nothing, Promise[E, A]]
Eru.semaphore(n: Long): Eru[Nothing, Semaphore]
Eru.queue[A](capacity: Int): Eru[Nothing, Queue[A]]
Eru.unboundedQueue[A]: Eru[Nothing, Queue[A]]
Eru.hub[A](capacity: Int): Eru[Nothing, Hub[A]]
Eru.unboundedHub[A]: Eru[Nothing, Hub[A]]
Eru.countDownLatch(count: Int): Eru[Nothing, CountDownLatch]
Eru.cyclicBarrier(parties: Int): Eru[Nothing, CyclicBarrier]

// High-density primitives
RefMap.make[K, V]: Eru[Nothing, RefMap[K, V]]                       // Per-key CAS concurrent map
RefMap.from[K, V](entries: Iterable[(K, V)]): Eru[Nothing, RefMap[K, V]]  // Pre-populated concurrent map
KeyedSemaphore.make[K](permitsPerKey: Long): Eru[Nothing, KeyedSemaphore[K]]  // Per-key concurrency limiter

// Timer primitives (hashed timer wheel)
Eru.at[E, A](epochMillis: Long)(effect: => Eru[E, A]): Eru[Nothing, Unit]  // Fire-and-forget at absolute time
Eru.after[E, A](delay: java.time.Duration)(effect: => Eru[E, A]): Eru[Nothing, Unit] // Fire-and-forget after delay
```


## Resource management

### Core resource operations

```scala
// Resource bracket pattern (extension on Eru[E, A])
def bracket[E1 >: E, F, B](release: A => Eru[F, Unit])(use: A => Eru[E1, B]): Eru[E1, B]

// Automatic finalization
def ensure[F](finalizer: => Eru[F, Unit]): Eru[E, A]
```

## Exit & result types

### `Exit[E, A]`

Represents the structured outcome of effect execution:

```scala
enum Exit[+E, +A] {
  case Success(value: A)
  case Failure(error: E) 
  case Die(throwable: Throwable)
  case Interrupt(fiberId: FiberId, cause: InterruptCause)
}
```

### `Result[E, A]`

Simpler result type for basic success/failure scenarios:

```scala
enum Result[+E, +A] {
  case Success(value: A)
  case Failure(error: E)
}
```

### `InterruptCause`

Structured information about fiber interruption:

```scala
enum InterruptCause {
  case Cancelled(reason: Option[String])
  case Timeout(duration: java.time.Duration, operation: Option[String]) 
  case ParentTerminated(parentId: FiberId, parentExit: Exit[Any, Any])
  case ResourceExhausted(resource: String, details: Option[String])
  case Custom(name: String, context: Option[String], metadata: Map[String, String])
}
```

## Observability

### `EruObserver`

Interface for monitoring program execution:

```scala
trait EruObserver {
  def onEvent(event: EruEvent): Unit
}

object EruObserver {
  def noop: EruObserver
  def console: EruObserver
}
```

### Observer events

```scala
enum EruEvent {
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
  case TraceSpan(span: net.ghoula.eru.trace.EruTrace.Span) // Defined for tracing; not emitted by the interpreter
}

enum Outcome {
  case Success
  case TypedFailure(error: Any)
  case Defect(throwable: Throwable)  
}
```

## Extension methods

Import `net.ghoula.eru.prelude.*` to access convenient extension methods:

```scala
// Concurrency extensions (require a given EruRuntime, provided by the prelude)
def fork: Eru[Nothing, Fiber[E, A]]
def forkDaemon: Eru[Nothing, Fiber[E, A]]
def race[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, Either[A, B]]
def zipPar[E1 >: E, B](that: Eru[E1, B]): Eru[E1 | Throwable, (A, B)]
def timeout(duration: java.time.Duration)(using net.ghoula.eru.time.Monotonic): Eru[E | java.util.concurrent.TimeoutException | Throwable, A]
def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1)(using
  net.ghoula.eru.time.Monotonic
): Eru[E | Throwable, A1]

// Retry extensions
def retryN(n: Int): Eru[E, A]
def retryWithBackoff(base: Duration, maxRetries: Int): Eru[E, A]

// Degree-limited parallel operations (top-level, via the prelude)
def foreachParN[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]]
def foreachParNDiscard[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, Unit]

// Validation patterns (top-level, via the prelude)
def validatePar[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[List[E], List[A]]]
def validateFirst[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[E | Throwable, List[A]]]

// Runner conveniences  
def runExit(): Exit[E, A]
def runWith(observer: EruObserver): A
```

## Domain types

### Validated domain types

```scala
// Attempt counting for retries
opaque type AttemptCount = Int
object AttemptCount {
  def apply(value: Int): AttemptCount // Validates >= 0
}

// Jitter factors for backoff  
opaque type JitterFactor = Double  
object JitterFactor {
  def apply(value: Double): JitterFactor // Validates [0.0, 1.0]
}

// Failure thresholds for circuit breakers
opaque type FailureThreshold = Int
object FailureThreshold {
  def apply(value: Int): FailureThreshold // Validates > 0  
}
```

## Platform behavior

### JVM platform
- **True concurrency**: Uses Java Virtual Threads (JDK 25+)
- **Non-blocking**: Sleep, timeout, and suspend operations don't block OS threads
- **Scalable**: Runs each fiber on a lightweight virtual thread
- **Observable**: Fiber lifecycle events (`FiberStarted`, `FiberCompleted`)

## Documentation

Scaladoc is generated as part of the build.

## Import structure

```scala
import net.ghoula.eru.*              // Core types (Eru, Exit, Result, etc.)
import net.ghoula.eru.prelude.*      // Everything including extensions  
import net.ghoula.eru.EruRuntime.*   // Companion factories (create, shared, withBackend) and Policy
```

The `prelude` import provides all commonly needed functionality.

## Stack safety guidelines

**⚠️ Important**: Eru provides stack safety for its own operations (`flatMap`, `map`, etc.), but Scala function recursion can still cause stack overflow. Prefer iterative patterns:

### ✅ Safe patterns

```scala
// Use iterative builders for loops
Eru.iterate(0)(i => Eru.succeed(i + 1))(_ >= 10000)

// Use foldLeft for accumulation
values.foldLeft(Eru.succeed(0)) { (acc, v) =>
  acc.flatMap(total => Eru.succeed(total + v))
}

// Use traverse/sequence for collections
Eru.traverse(items)(item => processItem(item))
```

### ❌ Avoid these patterns

```scala
// DON'T: Recursive Eru construction - Scala stack overflow
def recursive(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => recursive(n - 1))

// DON'T: Deep Scala recursion with Eru
def recursiveProcess[A, E, B](items: List[A])(processItem: A => Eru[E, B]): Eru[E, List[B]] = items match {
  case Nil => Eru.succeed(Nil)
  case head :: tail =>
    processItem(head).flatMap(b =>
      recursiveProcess(tail)(processItem).map(bs => b :: bs))  // Scala recursion!
}
```

**Key insight**: Eru makes `flatMap` chains stack-safe, but you must build those chains without Scala recursion. Use iterative construction with `foldLeft`, `traverse`, `iterate`, or loops to avoid stack overflow.