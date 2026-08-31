# Chapter 9: Introduction to fibers

This chapter introduces fibers: how to fork work, await results, and interrupt computations. If you've used threads before, the fork/await model will feel familiar, with explicit interruption and typed outcomes on top. This chapter focuses on patterns that work on the JVM runtime.

## What are fibers?

A fiber is an Eru program running concurrently on its own virtual thread. Forked fibers are contained by structured scopes: a fiber forked inside a scope dies with that scope (interrupted with the real parent identity and exit, then awaited). At the root, fibers are tracked by the runtime and released only by an explicit `cleanup()` or `shutdownRootFibers` — there is no automatic handling at program exit.

On the JVM, fibers run on Java virtual threads (a JDK 21 feature; Eru targets JDK 25).

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

Notice how `fork` returns immediately while the task runs in the background, and `await` collects the result. The `fork` operation returns an `Eru[Nothing, Fiber[E, A]]`: starting a fiber is non-blocking and infallible, and you get back a reference to the fiber, not the result of its computation.

## Fiber lifecycle

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

## Basic fiber operations

### Forking independent work

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

### Racing operations

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

## Fiber cleanup

Forked fibers are contained by structured scopes, and root fibers are tracked by the runtime.

### Fiber tracking and cleanup

```
Parent Fiber (main program)
├── Child Fiber 1 (forked task)
├── Child Fiber 2 (forked task)
└── Child Fiber 3 (forked task)

When the parent's scope unwinds:
→ Children still running are interrupted with ParentTerminated
→ Joined children are awaited; daemons unwind asynchronously
→ No child outlives its parent's scope
```

At the root (no parent scope), tracked fibers are released only when you call `runtime.cleanup()` or `shutdownRootFibers` — both interrupt the remaining root fibers and await them. There is no shutdown hook; an application that wants an orderly end calls cleanup itself.

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
    
    // We do not await the background fiber here. At the root it keeps running
    // until it completes or the runtime's cleanup() interrupts it.
    
  } yield quickResult
}

val parentResult = parentFiberDemo().unsafeRunSync()
println(s"Parent result: $parentResult")
```

## Error handling with fibers

Fibers isolate errors: a failing fiber does not crash other fibers.

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

## Cooperative interruption

Fibers support cooperative interruption, allowing graceful cancellation:

```scala mdoc

def interruptibleTask(): Eru[Nothing, String] = {
  // interruptibleBlocking maps InterruptedException to Exit.Interrupt
  Eru.interruptibleBlocking {
    Thread.sleep(5_000)
    "Task completed"
  }
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
      case net.ghoula.eru.Exit.Interrupt(fiberId, cause) =>
        Eru.succeed(s"Task was interrupted: $cause")
      case other =>
        Eru.succeed(s"Other outcome: $other")
    }
  } yield message
}

val interruptionResult = demonstrateInterruption().unsafeRunSync()
println(interruptionResult)
```

## Parallel collection processing

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

## Resource safety with fibers

Fibers compose with resource management:

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

## Fiber patterns

### Fire-and-forget pattern

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
    _ <- backgroundLogging("User action performed").forkDaemon
    
    // Continue with main work
    result <- Eru.succeed("Main work completed")
  } yield result
  // forkDaemon: not joined, not tracked at the root. Inside a structured scope the
  // daemon is still interrupted when the scope unwinds; at the root it lives until
  // the JVM exits.
}

val fireForgetResult = fireAndForget().unsafeRunSync()
println(fireForgetResult)
```

### Timeout pattern

```scala mdoc
// Timeout with Eru's real timer: the effect races against a logical deadline on the
// backend's timer wheel, and a loss surfaces as java.util.concurrent.TimeoutException.
def timeoutOperation[E, A](
  operation: Eru[E, A]
): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
  operation.timeout(java.time.Duration.ofSeconds(2))

def slowOperation(): Eru[String, String] = {
  Eru.effect {
    "Slow operation completed"
  }.mapError(_.getMessage)
}

// A quick operation completes before the deadline; the timeout never fires.
val timeoutResult = timeoutOperation(slowOperation()).attempt.unsafeRunSync()
println(s"Timeout result: $timeoutResult")
```

## Key takeaways

This chapter introduced:

Lightweight concurrency: fibers run on Java virtual threads, which cost less to create than platform threads.

Tracked fibers: scopes contain their children (interrupted and awaited at unwind); root fibers are released by an explicit `cleanup()`/`shutdownRootFibers`, never automatically at exit.

Safe error isolation: a failing fiber does not crash other fibers.

Composable operations: fork, await, race, and interrupt compose with other Eru patterns.

Resource safety: fibers compose with resource management and cleanup patterns.

Cooperative interruption: a fiber's finalizers run before it terminates.

## What's next

Chapter 10 covers advanced concurrency patterns: parallel processing, coordination primitives, and resource-bounded concurrency.