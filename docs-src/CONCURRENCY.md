# Concurrency Guide

Eru provides powerful cross-platform concurrency capabilities with a unified API that adapts to the execution environment. On JVM, you get true concurrent execution with Java Virtual Threads. On Scala Native, you get deterministic sequential execution with identical API surface.

## Core Concepts

### Fibers

Fibers are lightweight units of concurrent execution. Each fiber represents a computation running (or completed) independently from the main execution thread.

```scala
import net.ghoula.eru.prelude.*

// Fork a computation onto a new fiber
val program = for {
  fiber <- Eru.succeed(42).fork
  result <- fiber.await  
} yield result

// On JVM: Runs asynchronously on Virtual Thread
// On Native: Runs synchronously but maintains same API
```

### Cross-Platform Behavior

```scala
val concurrent = for {
  fiber1 <- longComputation.fork      // Platform-adaptive
  fiber2 <- anotherComputation.fork   // Platform-adaptive
  result1 <- fiber1.await
  result2 <- fiber2.await
} yield (result1, result2)
```

**JVM Execution:**
- Each fiber runs on its own Virtual Thread
- True parallelism - computations execute simultaneously
- Non-blocking - doesn't consume OS threads when waiting

**Native Execution:**  
- Fibers execute sequentially in fork order
- Deterministic - same input always produces same execution order
- Resource-safe - maintains all cleanup guarantees

## Structured Concurrency

Eru follows structured concurrency principles where child fibers are automatically managed by their parent scope.

### Automatic Cleanup

```scala
val structured = for {
  fiber1 <- computation1.fork
  fiber2 <- computation2.fork
  // If this scope exits (success, failure, or interruption),
  // all child fibers are automatically cleaned up
  result <- fiber1.await.zipPar(fiber2.await)
} yield result
```

### Parent-Child Relationships

```scala
def parentTask: Eru[String, String] = for {
  childFiber <- childTask.fork
  _ <- EruRuntime.sleep(Duration.ofSeconds(1))
  // If parent is interrupted, child is automatically interrupted
  result <- childFiber.await
} yield result

def childTask: Eru[String, String] = 
  EruRuntime.sleep(Duration.ofSeconds(5)).as("child completed")
```

## Parallel Composition

### Racing Operations

Race multiple computations and get the result of whichever completes first:

```scala
import java.time.Duration

val raced = EruRuntime.race(
  slowOperation.timeout(Duration.ofSeconds(10)),
  fastOperation.timeout(Duration.ofSeconds(1))
)

raced.map {
  case Left(slow) => s"Slow won: $slow"
  case Right(fast) => s"Fast won: $fast"  // This will typically win
}
```

### Parallel Execution

Run multiple effects in parallel and collect all results:

```scala
val parallel = EruRuntime.zipPar(
  fetchUserData(userId),
  fetchUserPreferences(userId),
  fetchUserHistory(userId)
)

// All three operations run concurrently (JVM) or sequentially (Native)
val (userData, preferences, history) = parallel.unsafeRunSync()
```

### Bulk Parallel Operations

Process collections in parallel:

```scala
val userIds = List("user1", "user2", "user3", "user4")

// Process all users in parallel
val allUsers = EruRuntime.parSequence(
  userIds.map(fetchUser)
)

// Transform inputs and process in parallel  
val enrichedUsers = EruRuntime.parTraverse(userIds) { userId =>
  for {
    user <- fetchUser(userId)
    profile <- fetchProfile(userId) 
  } yield EnrichedUser(user, profile)
}
```

### Resource-Controlled Parallel Execution

When processing large collections, you may want to limit the degree of parallelism to prevent resource exhaustion:

```scala
val manyUserIds = (1 to 10000).map(i => s"user$i").toList

// Process users with controlled parallelism (max 10 concurrent)
val controlledProcessing = EruRuntime.foreachParN(10, manyUserIds) { userId =>
  fetchAndProcessUser(userId)
}

// Process and discard results (for side effects only)
val sideEffectProcessing = EruRuntime.foreachParNDiscard(5, manyUserIds) { userId =>
  sendNotification(userId)
}
```

**Benefits of Degree-Limited Parallelism:**
- **Resource Protection**: Prevents overwhelming database connections, API rate limits, or memory
- **Predictable Performance**: Maintains consistent resource usage under load
- **Error Isolation**: Limits the blast radius of concurrent failures

## Timeouts and Cancellation

### Operation Timeouts

```scala
val timeoutProtected = longRunningOperation
  .timeout(Duration.ofSeconds(30))
  .recover {
    case _: java.util.concurrent.TimeoutException => 
      "Operation timed out, using fallback"
  }
```

### Timeout with Fallback

```scala
val withFallback = riskyOperation
  .timeoutTo(Duration.ofSeconds(5), "fallback value")
```

### Manual Interruption

```scala
val interruptible = for {
  fiber <- longRunningTask.fork
  _ <- EruRuntime.sleep(Duration.ofSeconds(1))
  _ <- fiber.interrupt(InterruptCause.Cancelled(Some("User requested cancellation")))
  result <- fiber.await // Will be interrupted
} yield result
```

## Resource Safety in Concurrent Context

### Resources with Fibers

```scala
def processFilesConcurrently(paths: List[Path]): Eru[Throwable, List[String]] = {
  val processFile = (path: Path) => 
    Eru.resource {
      Files.newBufferedReader(path)
    } { reader =>
      Eru.effect(reader.close())
    }.flatMap { reader =>
      Eru.effect(reader.readLine())
    }
    
  EruRuntime.parSequence(paths.map(processFile))
  // All resources properly cleaned up even if some operations fail
}
```

### Finalizers with Concurrency

```scala
val safeProcess = for {
  resource <- acquireExpensiveResource()
  fiber <- processData(resource).fork
  result <- fiber.await
} yield result
  .ensure(releaseExpensiveResource())  // Always executes
```

## Advanced Patterns

### Fan-out / Fan-in

```scala
def fanOutFanIn[A, B](input: A, workers: Int)(process: A => Eru[Throwable, B]): Eru[Throwable, List[B]] = {
  val workItems = (1 to workers).map(_ => process(input)).toList
  EruRuntime.parSequence(workItems)
}

val results = fanOutFanIn("data", 4)(heavyComputation)
```

### Pipeline Processing

```scala
def pipeline[A, B, C, D](
  input: A
)(
  stage1: A => Eru[String, B],
  stage2: B => Eru[String, C], 
  stage3: C => Eru[String, D]
): Eru[String, D] = {
  for {
    step1 <- stage1(input)
    step2 <- stage2(step1) 
    step3 <- stage3(step2)
  } yield step3
}
```

### Concurrent Processing Pipeline

```scala
def processPipeline[A, B](items: List[A])(process: A => Eru[String, B]): Eru[String, List[B]] = {
  for {
    // Process items in parallel
    processed <- EruRuntime.parTraverse(items)(process)
  } yield processed
}
```

## Observability in Concurrent Context

Monitor fiber lifecycle and execution:

```scala
val observer = new EruObserver {
  def onEvent(event: EruObserver.EruEvent): Unit = event match {
    case EruObserver.EruEvent.FiberStarted(fiberId) =>
      println(s"Fiber started: $fiberId")
      
    case EruObserver.EruEvent.FiberCompleted(fiberId, exit) =>
      println(s"Fiber completed: $fiberId -> $exit")
      
    case EruObserver.EruEvent.FiberInterrupted(fiberId, cause) =>
      println(s"Fiber interrupted: $fiberId -> $cause")
      
    case other => 
      // Handle other events
  }
}

val monitored = concurrentProgram.unsafeRunSyncWith(observer)
```

## Error Handling in Concurrent Programs

### Fail-Fast vs Fault-Tolerant

```scala
// Fail-fast: Stop on first error
val failFast = EruRuntime.parSequence(operations)

// Fault-tolerant: Collect all results and errors
val faultTolerant = EruRuntime.parSequence(
  operations.map(_.attempt)
).map { results =>
  val (successes, failures) = results.partitionMap(identity)
  (successes, failures)
}
```

### Validation Patterns

For domain validation scenarios where you need flexible error handling strategies:

```scala
val validationInputs = List(
  validateEmail(user.email),
  validateAge(user.age),
  validateUsername(user.username)
)

// Error accumulation: Collect ALL validation errors
val accumulateErrors = EruRuntime.validatePar(validationInputs)
accumulateErrors.unsafeRunSync() match {
  case Left(allErrors) => 
    // Show user all validation problems at once
    displayValidationErrors(allErrors)
  case Right(validatedFields) => 
    // All validations passed
    proceedWithValidUser(validatedFields)
}

// Fail-fast: Stop at first validation error
val failFast = EruRuntime.validateFirst(validationInputs)
failFast.unsafeRunSync() match {
  case Left(firstError) => 
    // Show user the first validation error only
    displaySingleError(firstError)
  case Right(validatedFields) => 
    // All validations passed
    proceedWithValidUser(validatedFields)
}
```

### Circuit Breaker Pattern

```scala
class CircuitBreaker[E, A](
  failureThreshold: Int,
  resetTimeout: Duration
) {
  def protect(operation: Eru[E, A]): Eru[E | CircuitBreakerOpen, A] = {
    // Implementation would track failures and open circuit when threshold exceeded
    operation
  }
}
```

## Best Practices

1. **Prefer Structured Concurrency**: Always ensure child fibers are properly awaited or interrupted
2. **Use Timeouts**: Protect against unbounded waiting with reasonable timeouts
3. **Handle Interruption**: Design computations to respond gracefully to interruption
4. **Resource Safety**: Always use proper resource management even in concurrent contexts
5. **Monitor Fiber Lifecycle**: Use observers to understand concurrent execution patterns
6. **Platform Awareness**: Write code that works well on both JVM (concurrent) and Native (sequential)

## Platform-Specific Considerations

### JVM Optimizations
- Uses Virtual Threads for massive scalability (millions of fibers)
- Non-blocking I/O operations preserve Virtual Thread efficiency  
- Cooperative interruption respects Virtual Thread lifecycle

### Native Optimizations  
- Deterministic execution order aids in debugging
- No context switching overhead
- Predictable memory usage patterns
- Excellent for single-threaded high-performance scenarios

The key insight is that Eru's concurrency model provides the same powerful abstractions regardless of platform, allowing you to write concurrent code once and run it optimally everywhere.