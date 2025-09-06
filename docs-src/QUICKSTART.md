# Eru Quickstart

This guide introduces you to Eru, a pure effect system for Scala 3 that provides powerful abstractions for building safe, concurrent, and cross-platform applications.

## Key Concepts

- `Eru[E, A]` is a pure description of a computation that may fail with typed error `E` or succeed with value `A`
- Construction is lazy and pure - no side effects occur until execution
- Evaluation happens when you call runtime methods like `unsafeRunSync()`
- Works identically across JVM (with concurrency) and Scala Native (synchronous)

## Basic Usage

### Hello, Eru

```scala
import net.ghoula.eru.prelude.*

val program: Eru[Nothing, String] = 
  Eru.succeed("Hello, Eru!")

val result: String = program.unsafeRunSync()
```

### Suspending Effects

Use `Eru.effect` to suspend side effects. The computation is deferred until execution:

```scala
var counter = 0

val program: Eru[Throwable, Int] = Eru.effect {
  counter += 1  // This only happens when the program runs
  42
}

// counter is still 0 here
val value = program.unsafeRunSync() 
// counter is now 1
```

### Sequencing Operations

Chain computations using `map` and `flatMap`. Eru optimizes pure chains for exceptional performance:

```scala
val computation: Eru[Nothing, Int] =
  Eru.succeed(10)
    .flatMap(x => Eru.succeed(x * 2))  // Optimized at construction time
    .map(_ + 2)

val result = computation.unsafeRunSync() // 22
```

## Error Handling

Eru provides comprehensive error handling with typed errors:

```scala
val risky: Eru[String, Int] = Eru.fail("something went wrong")

val recovered: Eru[Nothing, String] = risky.recover {
  case "something went wrong" => "all better now!"
}

val safe: Eru[String, String] = risky.attempt.map {
  case Result.Success(value) => s"Got: $value"
  case Result.Failure(error) => s"Error: $error"
}
```

## Resource Management

Eru ensures resources are properly cleaned up even in the presence of errors:

```scala
import java.nio.file.*

val safeFileRead: Eru[Throwable, String] = 
  Eru.resource {
    // Acquire resource
    Files.newBufferedReader(Paths.get("data.txt"))
  } { reader =>
    // Release resource (always called)
    Eru.effect(reader.close())
  }.flatMap { reader =>
    // Use resource safely
    Eru.effect(reader.readLine())
  }
```

## Cross-Platform Concurrency

Eru provides the same API across platforms with different execution models:

```scala
// This code works identically on JVM and Native
val concurrent: Eru[Nothing, (Int, String)] = for {
  fiber1 <- Eru.succeed(42).fork        // JVM: async, Native: sync
  fiber2 <- Eru.succeed("world").fork   // JVM: async, Native: sync  
  result1 <- fiber1.await
  result2 <- fiber2.await
} yield (result1, result2)

val (number, text) = concurrent.unsafeRunSync()
```

### Platform Differences

- **JVM**: Uses Java Virtual Threads for true concurrency - fibers run in parallel
- **Native**: Uses synchronous execution - fibers run sequentially but API remains identical

## Parallel Operations

Race multiple computations or run them in parallel:

```scala
import java.time.Duration

// Race two computations
val first: Eru[Nothing, String] = EruRuntime.race(
  EruRuntime.sleep(Duration.ofMillis(100)).as("slow"),
  EruRuntime.sleep(Duration.ofMillis(50)).as("fast")
).map {
  case Left(slow) => slow
  case Right(fast) => fast  // This will win
}

// Run computations in parallel
val parallel: Eru[Nothing, (String, Int)] = 
  EruRuntime.zipPar(
    Eru.succeed("hello"),
    Eru.succeed(42)
  )
```

## Observability

Monitor program execution with observers:

```scala
val observer = new EruObserver {
  def onEvent(event: EruObserver.EruEvent): Unit = event match {
    case EruObserver.EruEvent.ProgramStart(scopeId) =>
      println(s"Program started: $scopeId")
    case EruObserver.EruEvent.ProgramEnd(scopeId, outcome) =>  
      println(s"Program finished: $scopeId -> $outcome")
    case other => 
      println(s"Event: $other")
  }
}

val result = myProgram.unsafeRunSyncWith(observer)
```

## What's Next

- **[API Documentation](API.md)** - Complete reference for all Eru operations
- **[Concurrency Guide](CONCURRENCY.md)** - Advanced fiber patterns and structured concurrency
- **[Resource Management](RESOURCES.md)** - Safe resource handling patterns  
- **[Observability](OBSERVER.md)** - Monitoring and debugging techniques

## Common Patterns

### Retries with Backoff
```scala
import java.time.Duration

val retryPolicy = EruRuntime.Policy.Exponential(
  Duration.ofMillis(100), 
  maxRetries = 3
)

val resilient = riskyOperation.retry(retryPolicy)
```

### Timeout Protection
```scala
val protected = longRunningOperation
  .timeout(Duration.ofSeconds(5))
  .recover {
    case _: java.util.concurrent.TimeoutException => "Operation timed out"
  }
```

### Parallel Processing
```scala
val items = List("item1", "item2", "item3")

val processed = EruRuntime.parSequence(
  items.map(processItem)
)
```

Eru makes it easy to build robust, concurrent applications while maintaining type safety and cross-platform compatibility.