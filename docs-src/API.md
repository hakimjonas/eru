# Eru API Reference

This document provides a comprehensive reference to Eru's public API, organized by functionality area.

## Core Effect Type

### `Eru[E, A]`

The foundational effect type representing a computation that may fail with error `E` or succeed with value `A`.

```scala
// Core constructors
Eru.succeed[A](value: A): Eru[Nothing, A]
Eru.fail[E](error: E): Eru[E, Nothing]
Eru.effect[A](computation: => A): Eru[Throwable, A]
Eru.blocking[A](computation: => A): Eru[Throwable, A]

// Iterative builders (stack-safe)
Eru.iterate[E, A](start: A)(step: A => Eru[E, A])(condition: A => Boolean): Eru[E, A]
Eru.iterateN[E, A](start: A, n: Int)(step: A => Eru[E, A]): Eru[String | E, A]
Eru.unfold[E, A, B](seed: A)(f: A => Eru[E, Option[(B, A)]]): Eru[E, List[B]]
Eru.sequence[E, A](effects: List[Eru[E, A]]): Eru[E, List[A]]
Eru.traverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, List[B]]

// Collection operations
Eru.collectAll[E, A](effects: List[Eru[E, A]]): Eru[E, List[A]]
Eru.collectAllDiscard[E, A](effects: List[Eru[E, A]]): Eru[E, Unit]
Eru.foreach[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, List[B]]
Eru.foreachDiscard[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, Unit]
Eru.partition[E, A](effects: List[Eru[E, A]]): Eru[Nothing, (List[E], List[A])]

// Aggregation operations
Eru.foldLeft[E, A, B](start: B, effects: List[Eru[E, A]])(f: (B, A) => B): Eru[E, B]
Eru.foldRight[E, A, B](effects: List[Eru[E, A]], start: B)(f: (A, B) => B): Eru[E, B]

// Conditional operations
Eru.when[E](condition: Boolean)(effect: => Eru[E, Unit]): Eru[E, Unit]
Eru.unless[E](condition: Boolean)(effect: => Eru[E, Unit]): Eru[E, Unit]
Eru.cond[E, A](condition: Boolean, ifTrue: => A, ifFalse: => A): Eru[E, A]

// Control flow
Eru.forever[E, A](effect: Eru[E, A]): Eru[E, Nothing]
Eru.repeatN[E, A](n: Int)(effect: Eru[E, A]): Eru[E, List[A]]
Eru.repeatUntil[E, A](effect: Eru[E, A])(condition: A => Boolean): Eru[E, A]

// Transformation
map[B](f: A => B): Eru[E, B]
flatMap[E1 >: E, B](f: A => Eru[E1, B]): Eru[E1, B]
zip[E1 >: E, B](that: Eru[E1, B]): Eru[E1, (A, B)]
zipWith[E1 >: E, B, C](that: Eru[E1, B])(f: (A, B) => C): Eru[E1, C]

// Error handling  
recover[A1 >: A](pf: PartialFunction[E, A1]): Eru[Nothing, A1]
recoverWith[E1, A1 >: A](pf: PartialFunction[E, Eru[E1, A1]]): Eru[E1, A1]
attempt: Eru[Nothing, Result[E, A]]
orElse[E1, A1 >: A](that: => Eru[E1, A1]): Eru[E1, A1]

// Execution
unsafeRunSync(): A  // May throw
unsafeRunSyncWith(observer: EruObserver): A
```

## Iterative Construction

Stack-safe builders for common iteration patterns:

```scala
// Iterate until a condition is met
def iterate[E, A](start: A)(step: A => Eru[E, A])(condition: A => Boolean): Eru[E, A]

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

## Concurrency & Fibers

### Core Fiber Operations

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

### `EruRuntime` Operations

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
  def sleep(duration: java.time.Duration): Eru[Nothing, Unit]
  def timeout[E, A](duration: java.time.Duration)(fa: Eru[E, A]): Eru[E | java.util.concurrent.TimeoutException | Throwable, A]

  // Coordination primitives
  def ref[A](initial: A): Eru[Nothing, Ref[A]]
  def deferred[E, A]: Eru[Nothing, Deferred[E, A]]
  def promise[E, A]: Eru[Nothing, Promise[E, A]]
  def semaphore(permits: Int): Eru[Nothing, Semaphore]
  def queue[A](capacity: Int): Eru[Nothing, Queue[A]]
  def unboundedQueue[A]: Eru[Nothing, Queue[A]]
  def hub[A](capacity: Int): Eru[Nothing, Hub[A]]
  def unboundedHub[A]: Eru[Nothing, Hub[A]]
  def countDownLatch(count: Int): Eru[Nothing, CountDownLatch]
  def cyclicBarrier(parties: Int): Eru[Nothing, CyclicBarrier]

  // Retry operations
  def retry[E, A](effect: Eru[E, A])(policy: RetryPolicy): Eru[E, A]
  def retryN[E, A](n: Int)(effect: Eru[E, A]): Eru[E, A]
  def retryWithBackoff[E, A](base: Duration, maxRetries: Int)(effect: Eru[E, A]): Eru[E, A]

  // Async boundaries
  def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A]
  
  // Validation patterns
  def validatePar[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[List[E], List[A]]]
  def validateFirst[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[E | Throwable, List[A]]]
}
```


## Resource Management

### Core Resource Operations

```scala
// Resource bracket pattern
def resource[R, A](
  acquire: => R
)(
  release: R => Eru[Nothing, Unit]  
): Eru[Throwable, R]

// Automatic finalization
def ensure(finalizer: Eru[Nothing, Unit]): Eru[E, A]
def onExit(f: Exit[E, A] => Eru[Nothing, Unit]): Eru[E, A]
```

## Exit & Result Types

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

### Observer Events

```scala
enum EruEvent {
  case ProgramStart(scopeId: ScopeId)
  case ProgramEnd(scopeId: ScopeId, outcome: Outcome)  
  case Step(scopeId: ScopeId, label: String)
  case FiberStarted(fiberId: FiberId)
  case FiberCompleted(fiberId: FiberId, exit: Exit[Any, Any])
  case FiberInterrupted(fiberId: FiberId, cause: InterruptCause)
  case TraceSpan(span: Any) // For future tracing integration
}

enum Outcome {
  case Success
  case TypedFailure(error: Any)
  case Defect(throwable: Throwable)  
}
```

## Extension Methods

Import `net.ghoula.eru.prelude.*` to access convenient extension methods:

```scala
// Concurrency extensions
def fork: Eru[Nothing, Fiber[E, A]]
def race[E1, A1](that: Eru[E1, A1]): Eru[E | E1 | Throwable, Either[A, A1]]  
def zipPar[E1, B](that: Eru[E1, B]): Eru[E | E1 | Throwable, (A, B)]
def timeout(duration: java.time.Duration): Eru[E | java.util.concurrent.TimeoutException | Throwable, A]
def timeoutTo[A1 >: A](duration: java.time.Duration, fallback: A1): Eru[E, A1]

// Retry extensions
def retryN(n: Int): Eru[E, A]
def retryWithBackoff(base: Duration, maxRetries: Int): Eru[E, A]

// Degree-limited parallel extensions
def foreachParN[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, List[B]]
def foreachParNDiscard[A, E, B](n: Int, inputs: Iterable[A])(f: A => Eru[E, B]): Eru[E | Throwable, Unit]

// Validation patterns
def validatePar[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[List[E], List[A]]]
def validateFirst[E, A](effects: List[Eru[E, A]]): Eru[Throwable, Either[E | Throwable, List[A]]]

// Runner conveniences  
def runExit(): Exit[E, A]
def runWith(observer: EruObserver): A
def runAttempt(): Result[E, A]
```

## Domain Types

### Validated Domain Types

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

## Platform Behavior

### JVM Platform
- **True Concurrency**: Uses Java Virtual Threads (JDK 21+)
- **Non-blocking**: Sleep, timeout, and suspend operations don't block OS threads
- **Scalable**: Supports millions of lightweight fibers
- **Observable**: Full fiber lifecycle events

### Scala Native Platform  
- **Synchronous**: All operations execute deterministically in order
- **Identical API**: Same interface as JVM for code portability
- **Resource Safe**: Full finalizer and cleanup support
- **Zero Reflection**: Native-compatible implementation

## Code Generation 

Eru provides excellent Scaladoc documentation integrated with the build.

## Import Structure

```scala
import net.ghoula.eru.*              // Core types (Eru, Exit, Result, etc.)
import net.ghoula.eru.prelude.*      // Everything including extensions  
import net.ghoula.eru.EruRuntime.*   // Runtime operations only
```

The `prelude` import provides the most ergonomic experience with all commonly needed functionality.

## Stack Safety Guidelines

**Important**: Eru provides full stack safety for its own operations (`flatMap`, `map`, etc.), but Scala function recursion can still cause stack overflow. Always prefer iterative patterns:

### Safe Patterns

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

### Unsafe Patterns (Avoid)

```scala
// DON'T: Recursive Eru construction - Scala stack overflow
def recursive(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => recursive(n - 1))

// DON'T: Deep Scala recursion with Eru
def recursiveProcess(items: List[A]): Eru[E, List[B]] = items match {
  case Nil => Eru.succeed(Nil)
  case head :: tail =>
    processItem(head).flatMap(b =>
      recursiveProcess(tail).map(bs => b :: bs))  // Scala recursion!
}
```

**Key insight**: Eru makes `flatMap` chains stack-safe, but you must build those chains without Scala recursion. Use iterative construction with `foldLeft`, `traverse`, `iterate`, or loops to avoid stack overflow.