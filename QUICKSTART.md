# Eru Quickstart

This guide introduces Eru, a pure effect system for Scala 3 for building safe, concurrent applications.

## Key concepts

- `Eru[E, A]` is a pure description of a computation that may fail with typed error `E` or succeed with value `A`
- Construction is lazy and pure - no side effects occur until execution
- Evaluation happens when you call runtime methods like `unsafeRunSync()`
- Runs on the JVM with Virtual Thread-based concurrency

## Basic usage

### Hello, Eru

```scala
import net.ghoula.eru.prelude.*

val program: Eru[Nothing, String] = 
  Eru.succeed("Hello, Eru!")

val result: String = program.unsafeRunSync()
```

### Suspending effects

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

### Sequencing operations

Chain computations using `map` and `flatMap`:

```scala
val computation: Eru[Nothing, Int] =
  Eru.succeed(10)
    .flatMap(x => Eru.succeed(x * 2))  // Optimized at construction time
    .map(_ + 2)

val result = computation.unsafeRunSync() // 22
```

## Error handling

Eru handles errors through the typed error channel:

```scala
val risky: Eru[String, Int] = Eru.fail("something went wrong")

val recovered: Eru[String, String] = risky.recover {
  case "something went wrong" => "all better now!"
}

val safe: Eru[String, String] = risky.attempt.map {
  case Result.Success(value) => s"Got: $value"
  case Result.Failure(error) => s"Error: $error"
}
```

## Resource management

Eru ensures resources are properly cleaned up even in the presence of errors:

```scala
import java.nio.file.*

val safeFileRead: Eru[Throwable, String] = 
  Eru.effect {
    // Acquire resource
    Files.newBufferedReader(Paths.get("data.txt"))
  }.bracket { reader =>
    // Release resource (always called)
    Eru.effect(reader.close())
  } { reader =>
    // Use resource safely
    Eru.effect(reader.readLine())
  }
```

## Concurrency with fibers

Eru runs fibers on Java Virtual Threads:

```scala
// Fork two fibers and await both results
val concurrent: Eru[Throwable, (Int, String)] = for {
  fiber1 <- Eru.succeed(42).fork
  fiber2 <- Eru.succeed("world").fork
  exit1 <- fiber1.await
  exit2 <- fiber2.await
  result1 <- Eru.fromExit(exit1)
  result2 <- Eru.fromExit(exit2)
} yield (result1, result2)

val (number, text) = concurrent.unsafeRunSync()
```

Fibers map one-to-one onto Java Virtual Threads, which are lightweight enough to run a very large number of fibers concurrently.

## Parallel operations

Race multiple computations or run them in parallel:

```scala
import java.time.Duration

// Race two computations
val first: Eru[Throwable, String] = EruRuntime.shared.race(
  EruRuntime.shared.sleep(Duration.ofMillis(100)).map(_ => "slow"),
  EruRuntime.shared.sleep(Duration.ofMillis(50)).map(_ => "fast")
).map {
  case Left(slow) => slow
  case Right(fast) => fast  // This will win
}

// Run computations in parallel
val parallel: Eru[Throwable, (String, Int)] = 
  EruRuntime.shared.zipPar(
    Eru.succeed("hello"),
    Eru.succeed(42)
  )
```

## Observability

Monitor program execution with observers:

```scala
val myProgram: Eru[Nothing, String] = Eru.succeed("Hello, Eru!")

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

## What's next

- **[API Documentation](API.md)** - Complete reference for all Eru operations
- **[The Eru Book](book/00-table-of-contents.md)** - Progressive guide
- **[Resource Management](RESOURCES.md)** - Safe resource handling patterns
- **[Observability](OBSERVER.md)** - Monitoring and debugging techniques

## Common patterns

### Retries with backoff
```scala
import java.time.Duration

val resilient = riskyOperation.retryWithBackoff(
  Duration.ofMillis(100), 
  maxRetries = 3
)
```

### Timeout protection
```scala
val timed = longRunningOperation
  .timeoutTo(Duration.ofSeconds(5), "Operation timed out")
```

### Parallel processing
```scala
val items = List("item1", "item2", "item3")

val processed = parTraverse(items)(processItem)
```

### Resource-controlled processing
```scala
val manyItems = (1 to 1000).toList

// Process with limited parallelism to avoid resource exhaustion
val controlled = foreachParN(10, manyItems)(processItem)
```

### Validation patterns
```scala
val validations = List(
  validateEmail("user@example.com"),
  validateAge(25),
  validateUsername("john_doe")
)

// Collect ALL validation errors (error accumulation)
val allErrors = validatePar(validations)

// Stop at first validation error (fail-fast)
val firstError = validateFirst(validations)
```

Eru builds safe, concurrent applications while keeping types precise.