# Chapter 9: Introduction to Fibers

This chapter introduces fibers, Eru's approach to structured concurrency. If you've used threads before, fibers will feel familiar but much safer and more composable. This chapter focuses on foundational patterns that work on the JVM runtime.

## What Are Fibers?

A fiber is a lightweight concurrent execution context that runs Eru programs independently. Unlike operating system threads, fibers are managed by the Eru runtime and provide structured concurrency guarantees.

**Platform Implementation**: On the JVM, fibers are implemented using Java Virtual Threads (available in Java 21+), providing true parallelism with lightweight concurrency. On Scala Native, fibers execute synchronously while maintaining API compatibility. This chapter's examples demonstrate concurrent behavior on the JVM; Native platforms execute the same code deterministically in a single thread.

```scala mdoc
import net.ghoula.eru.prelude.*
import net.ghoula.eru.prelude.given

// A simple program that represents background work
def longRunningTask(id: Int): Eru[String, String] = {
  Eru.effect {
    // Simulate some computation
    val result = (1 to 1000).sum + id
    s"Task $id completed with result $result"
  }.mapError(_.getMessage)
}

// Fork a program into a fiber - it starts running immediately
val fiberProgram = for {
  fiber  <- longRunningTask(1).fork
  _      <- Eru.effect(println("Task forked, continuing..."))
  result <- fiber.await
} yield result

// Run the program
val fiberResult = fiberProgram.attempt.unsafeRunSync()
fiberResult match {
  case net.ghoula.eru.Result.Success(outcome) =>
    outcome match {
      case net.ghoula.eru.Exit.Success(value) => println(s"Fiber succeeded: $value")
      case net.ghoula.eru.Exit.Failure(error) => println(s"Fiber failed: $error")
      case net.ghoula.eru.Exit.Die(throwable) => println(s"Fiber died: $throwable")
      case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => println("Fiber was interrupted")
    }
  case net.ghoula.eru.Result.Failure(error) => println(s"Runtime error: $error")
}
```

Notice how `fork` returns immediately while the task runs in the background, and `await` safely collects the result. The `fork` operation returns an `Eru[Nothing, Fiber[E, A]]`, meaning that starting a fiber is itself a non-blocking and infallible effect—you get back a reference to the fiber, not the result of its computation.

## Fiber Lifecycle

Understanding the fiber lifecycle helps you use them effectively:

```scala mdoc
def demonstrateLifecycle(): Eru[String, String] = {
  for {
    // 1. Creation - fiber is created and starts running
    fiber <- Eru.effect(println("Starting background work...")).fork
    
    // 2. The main fiber continues while the background fiber runs
    _ <- Eru.effect(println("Main fiber continues...")).mapError(_.getMessage)
    
    // 3. Awaiting - wait for the background fiber to complete
    result <- fiber.await
    
    // 4. The result is an Exit that tells us how the fiber completed
    finalMessage <- result match {
      case net.ghoula.eru.Exit.Success(value) => 
        Eru.succeed("Background work completed successfully")
      case net.ghoula.eru.Exit.Failure(error) => 
        Eru.succeed(s"Background work failed: $error")
      case net.ghoula.eru.Exit.Die(throwable) => 
        Eru.succeed(s"Background work died: $throwable")
      case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => 
        Eru.succeed("Background work was interrupted")
    }
  } yield finalMessage
}

val lifecycleResult = demonstrateLifecycle().unsafeRunSync()
println(lifecycleResult)
```

## Basic Fiber Operations

### Forking Independent Work

```scala mdoc
def processItem(item: String): Eru[String, String] = {
  Eru.effect {
    // Simulate processing by computing something
    val hash = item.hashCode.abs % 1000
    s"Processed: $item (hash: $hash)"
  }.mapError(_.getMessage)
}

// Process multiple items concurrently
def processConcurrently(): Eru[String, List[String]] = {
  val items = List("item1", "item2", "item3")
  
  for {
    // Fork all tasks
    fibers <- Eru.collectAll(items.map(item => processItem(item).fork))
    
    // Await all results
    exits <- Eru.collectAll(fibers.map(_.await))
    
    // Extract successful results
    results <- Eru.succeed(exits.collect {
      case net.ghoula.eru.Exit.Success(value) => value
    })
  } yield results
}

val concurrentResults = processConcurrently().unsafeRunSync()
println(s"Processed ${concurrentResults.size} items: ${concurrentResults.mkString(", ")}")
```

### Racing Operations

Sometimes you want to run multiple operations and take the first one that completes:

```scala mdoc
def fastService(): Eru[String, String] = {
  Eru.effect {
    // Fast computation
    "Fast service response"
  }.mapError(_.getMessage)
}

def slowService(): Eru[String, String] = {
  Eru.effect {
    // Slower computation
    val result = (1 to 10000).map(_ * 2).sum
    s"Slow service response: $result"
  }.mapError(_.getMessage)
}

// Race two services and take the first response
def raceServices(): Eru[String | Throwable, String] = {
  fastService().race(slowService()).map {
    case Left(fastResult) => s"Fast won: $fastResult"
    case Right(slowResult) => s"Slow won: $slowResult"
  }
}

val raceResult = raceServices().attempt.unsafeRunSync()
println(s"Race result: $raceResult")
```

## Structured Concurrency

Eru enforces structured concurrency, meaning child fibers are properly cleaned up when parent fibers complete. When you fork a fiber within a scope, its lifecycle is automatically bound to that scope.

### Fiber Hierarchy and Cleanup

```
Parent Fiber (main program)
├── Child Fiber 1 (forked task)
├── Child Fiber 2 (forked task)
└── Child Fiber 3 (forked task)

When parent completes or fails:
→ All child fibers are automatically interrupted
→ Resources are cleaned up in reverse order
→ No fiber leaks or orphaned tasks
```

This hierarchical structure ensures that:

```scala mdoc
def parentFiberDemo(): Eru[String, String] = {
  for {
    // Start a background task
    backgroundFiber <- Eru.effect {
      // Background computation
      (1 to 5000).sum.toString
    }.mapError(_.getMessage).fork
    
    // Do some quick work
    quickResult <- Eru.effect("Quick work done").mapError(_.getMessage)
    
    // If we don't await the background fiber, it gets cleaned up automatically
    // when this fiber completes (structured concurrency)
    
  } yield quickResult
  // backgroundFiber is automatically managed by structured concurrency
}

val parentResult = parentFiberDemo().unsafeRunSync()
println(s"Parent result: $parentResult")
```

## Error Handling with Fibers

Fibers isolate errors - a failing fiber doesn't crash other fibers:

```scala mdoc
def reliableTask(): Eru[String, String] = {
  Eru.effect("Reliable task completed").mapError(_.getMessage)
}

def unreliableTask(): Eru[String, String] = {
  Eru.fail("Unreliable task failed")
}

def handleFiberErrors(): Eru[String, String] = {
  for {
    // Fork both tasks
    reliableFiber <- reliableTask().fork
    unreliableFiber <- unreliableTask().fork
    
    // Await both
    reliableExit <- reliableFiber.await
    unreliableExit <- unreliableFiber.await
    
    // Handle the results
    summary <- Eru.succeed {
      val reliable = reliableExit match {
        case net.ghoula.eru.Exit.Success(value) => s"Reliable: $value"
        case net.ghoula.eru.Exit.Failure(error) => s"Reliable failed: $error"
        case net.ghoula.eru.Exit.Die(throwable) => s"Reliable died: $throwable"
        case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => "Reliable interrupted"
      }
      
      val unreliable = unreliableExit match {
        case net.ghoula.eru.Exit.Success(value) => s"Unreliable: $value"
        case net.ghoula.eru.Exit.Failure(error) => s"Unreliable failed: $error"
        case net.ghoula.eru.Exit.Die(throwable) => s"Unreliable died: $throwable"
        case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => "Unreliable interrupted"
      }
      
      s"$reliable | $unreliable"
    }
  } yield summary
}

val errorHandlingResult = handleFiberErrors().unsafeRunSync()
println(errorHandlingResult)
```

## Cooperative Interruption

Fibers support cooperative interruption, allowing graceful cancellation:

```scala mdoc

def interruptibleTask(): Eru[String, String] = {
  // A task that could be interrupted during computation
  Eru.effect {
    "Task completed"
  }.mapError(_.getMessage)
}

def demonstrateInterruption(): Eru[String, String] = {
  for {
    // Start a task
    longTask <- interruptibleTask().fork
    
    // Immediately interrupt for demonstration
    _ <- Eru.effect {
      println("Interrupting task...")
    }.mapError(_.getMessage)
    
    _ <- longTask.interrupt(InterruptCause.Cancelled(Some("User requested cancellation")))
    
    // Check the result
    result <- longTask.await
    
    message <- result match {
      case net.ghoula.eru.Exit.Success(value) => 
        Eru.succeed(s"Task completed: $value")
      case net.ghoula.eru.Exit.Failure(error) => 
        Eru.succeed(s"Task failed: $error")
      case net.ghoula.eru.Exit.Die(throwable) => 
        Eru.succeed(s"Task died: $throwable")
      case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => 
        Eru.succeed(s"Task was interrupted: $cause")
    }
  } yield message
}

val interruptionResult = demonstrateInterruption().unsafeRunSync()
println(interruptionResult)
```

## Parallel Collection Processing

A common pattern is processing collections in parallel:

```scala mdoc
def processNumber(n: Int): Eru[String, Int] = {
  Eru.effect {
    // Simple computation
    n * 2
  }.mapError(_.getMessage)
}

// Process a list of numbers in parallel
def parallelProcessing(): Eru[String | Throwable, List[Int]] = {
  val numbers = (1 to 10).toList
  
  // Use parTraverse for parallel processing
  parTraverse(numbers)(processNumber)
}

val parallelResult = parallelProcessing().attempt.unsafeRunSync()
parallelResult match {
  case net.ghoula.eru.Result.Success(results) => 
    println(s"Processed ${results.size} numbers: ${results.mkString(", ")}")
  case net.ghoula.eru.Result.Failure(error) => 
    println(s"Processing failed: $error")
}
```

## Resource Safety with Fibers

Fibers integrate seamlessly with resource management:

```scala mdoc
case class DatabaseConnection(id: String) {
  def query(sql: String): String = s"$id: Query result for $sql"
  def close(): Unit = println(s"Closed connection $id")
}

def openConnection(id: String): Eru[String, DatabaseConnection] = {
  Eru.effect(DatabaseConnection(id)).mapError(_.getMessage)
}

def queryInBackground(connectionId: String): Eru[String, String] = {
  openConnection(connectionId).flatMap { connection =>
    // Query in a background fiber with guaranteed cleanup
    Eru.effect(connection.query("SELECT * FROM users"))
      .mapError(_.getMessage)
      .fork
      .flatMap(_.await)
      .map {
        case net.ghoula.eru.Exit.Success(result) => result
        case net.ghoula.eru.Exit.Failure(error) => s"Query failed: $error"
        case net.ghoula.eru.Exit.Die(throwable) => s"Query died: $throwable"
        case net.ghoula.eru.Exit.Interrupt(fiberId, cause) => "Query interrupted"
      }
      .ensure(Eru.effect(connection.close()).mapError(_.getMessage))
  }
}

val resourceResult = queryInBackground("conn-1").unsafeRunSync()
println(resourceResult)
```

## Fiber Patterns

### Fire-and-Forget Pattern

```scala mdoc
def backgroundLogging(message: String): Eru[String, Unit] = {
  Eru.effect {
    // Immediate logging for demonstration
    println(s"LOG: $message")
  }.mapError(_.getMessage)
}

def fireAndForget(): Eru[String, String] = {
  for {
    // Start background logging but don't wait for it
    _ <- backgroundLogging("User action performed").fork
    
    // Continue with main work
    result <- Eru.succeed("Main work completed")
  } yield result
  // Logging fiber continues in background
}

val fireForgetResult = fireAndForget().unsafeRunSync()
println(fireForgetResult)
```

### Timeout Pattern

```scala mdoc
// Simplified timeout pattern without actual timing
def timeoutOperation[E, A](
  operation: Eru[E, A], 
  shouldTimeout: Boolean
): Eru[E | String, A] = {
  val timeout = Eru.fail("Operation timed out")
  
  if (shouldTimeout) {
    operation.race(timeout).flatMap {
      case Left(result) => Eru.succeed(result)
      case Right(_) => Eru.fail("Timed out")
    }.mapError(_.toString)
  } else {
    operation.mapError(_.toString)
  }
}

def slowOperation(): Eru[String, String] = {
  Eru.effect {
    "Slow operation completed"
  }.mapError(_.getMessage)
}

// Demonstrate timeout behavior
val timeoutResult = timeoutOperation(slowOperation(), shouldTimeout = true).attempt.unsafeRunSync()
println(s"Timeout result: $timeoutResult")
```

## Key Takeaways

This introduction to fibers demonstrates several fundamental concepts:

**Lightweight Concurrency**: Fibers provide efficient concurrent execution without the overhead of operating system threads.

**Structured Concurrency**: Parent-child relationships ensure proper cleanup and resource management.

**Safe Error Isolation**: Failing fibers don't crash other fibers, enabling robust concurrent systems.

**Composable Operations**: Fork, await, race, and interrupt operations compose naturally with other Eru patterns.

**Resource Safety**: Fibers integrate seamlessly with resource management and cleanup patterns.

**Cooperative Interruption**: Graceful cancellation preserves system stability and resource integrity.

## What's Next

Chapter 10 explores advanced concurrency patterns including parallel processing, coordination primitives, and resource-bounded concurrency patterns for production systems.