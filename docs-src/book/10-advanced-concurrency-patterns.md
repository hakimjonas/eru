# Chapter 10: Advanced Concurrency Patterns

*"Concurrency is not just about running things in parallel—it's about coordinating independent computations to work together safely and efficiently."*

Building on Chapter 9's introduction to fibers, this chapter explores advanced concurrency patterns that make Eru production-ready. We'll cover parallel processing strategies, coordination primitives, resource-bounded concurrency, and sophisticated racing patterns that enable robust concurrent systems.

## Parallel Collection Processing

One of the most common concurrency needs is processing collections in parallel while maintaining safety and composability:

```scala mdoc
import net.ghoula.eru.prelude.*
import net.ghoula.eru.EruRuntime

// Create runtime for concurrent execution
given runtime: EruRuntime = EruRuntime.create()

// Simulate different types of work with varying complexity
def lightProcessing(item: String): Eru[String, String] = {
  Eru.effect {
    s"Light: $item (${item.hashCode.abs % 100})"
  }.mapError(_.getMessage)
}

def heavyProcessing(item: String): Eru[String, String] = {
  Eru.effect {
    // Simulate CPU-intensive work
    val result = (1 to 1000).map(_ * item.hashCode).sum
    s"Heavy: $item ($result)"
  }.mapError(_.getMessage)
}

// parTraverse processes items in parallel with bounded concurrency
def parallelLightWork(): Eru[String, List[String]] = {
  val items = List("item1", "item2", "item3", "item4", "item5")
  parTraverse(items)(lightProcessing)
}

// Test parallel processing
val lightResults = parallelLightWork().attempt.unsafeRunSync()
lightResults match {
  case net.ghoula.eru.Result.Success(results) =>
    println(s"Light processing completed: ${results.size} items")
    results.foreach(println)
  case net.ghoula.eru.Result.Failure(error) =>
    println(s"Light processing failed: $error")
}
```

### Custom Parallel Processing Patterns

For more control over concurrency, you can build custom parallel processing:

```scala mdoc
// Process items with custom concurrency control
def customParallelProcessing[A, B](
  items: List[A],
  maxConcurrency: Int
)(processor: A => Eru[String, B]): Eru[String, List[B]] = {

  def processChunk(chunk: List[A]): Eru[String, List[B]] = {
    val fiberPrograms = chunk.map(item => processor(item).fork)

    for {
      fibers <- Eru.collectAll(fiberPrograms)
      exits <- Eru.collectAll(fibers.map(_.await))
      results <- Eru.succeed(exits.collect {
        case net.ghoula.eru.Exit.Success(value) => value
      })
    } yield results
  }

  // Split into chunks based on max concurrency
  val chunks = items.grouped(maxConcurrency).toList

  for {
    chunkResults <- Eru.traverse(chunks)(processChunk)
    flatResults <- Eru.succeed(chunkResults.flatten)
  } yield flatResults
}

// Test custom parallel processing
def testCustomParallel(): Eru[String, List[String]] = {
  val items = (1 to 8).map(i => s"task-$i").toList

  customParallelProcessing(items, maxConcurrency = 3) { item =>
    Eru.effect {
      s"Processed $item at ${System.currentTimeMillis() % 10000}"
    }.mapError(_.getMessage)
  }
}

val customResults = testCustomParallel().unsafeRunSync()
println(s"Custom parallel processing: ${customResults.size} results")
customResults.foreach(println)
```

## Coordination Primitives

Advanced concurrent systems need coordination between fibers. Eru provides several primitives for fiber coordination:

### Promise-Based Coordination

```scala mdoc
import net.ghoula.eru.coordination.Promise

// Promises allow one fiber to complete a value that other fibers await
def promiseCoordination(): Eru[String, String] = {
  for {
    // Create a promise that will be completed later
    promise <- Promise.make[String, String]()

    // Fork a fiber that will complete the promise
    producer <- Eru.effect {
      // Simulate some work before completing the promise
      Thread.sleep(10) // Minimal delay for demonstration
      "Producer result"
    }.mapError(_.getMessage)
      .flatMap(result => promise.complete(net.ghoula.eru.Exit.Success(result)))
      .fork

    // Fork fibers that wait for the promise
    consumer1 <- promise.await
      .map(exit => s"Consumer1 received: $exit")
      .fork

    consumer2 <- promise.await
      .map(exit => s"Consumer2 received: $exit")
      .fork

    // Collect all results
    _ <- producer.await
    result1 <- consumer1.await
    result2 <- consumer2.await

    summary <- Eru.succeed {
      val r1 = result1 match {
        case net.ghoula.eru.Exit.Success(value) => value
        case other => s"Consumer1 failed: $other"
      }
      val r2 = result2 match {
        case net.ghoula.eru.Exit.Success(value) => value
        case other => s"Consumer2 failed: $other"
      }
      s"Coordination complete - $r1, $r2"
    }
  } yield summary
}

val promiseResult = promiseCoordination().attempt.unsafeRunSync()
promiseResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Promise coordination: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Promise coordination failed: $error")
}
```

### Deferred Values for Lazy Coordination

```scala mdoc
import net.ghoula.eru.coordination.Deferred

// Deferred values provide single-assignment variables for coordination
def deferredCoordination(): Eru[String, String] = {
  for {
    // Create a deferred value
    deferred <- Deferred.make[String, Int]()

    // Fork a computation that will complete the deferred
    computation <- Eru.effect {
      // Expensive computation
      (1 to 1000).sum
    }.mapError(_.getMessage)
      .flatMap(result => deferred.complete(result))
      .fork

    // Multiple fibers can await the same deferred value
    awaiter1 <- deferred.await
      .map(value => s"Awaiter1 got: $value")
      .fork

    awaiter2 <- deferred.await
      .map(value => s"Awaiter2 got: $value")
      .fork

    // Collect results
    _ <- computation.await
    result1 <- awaiter1.await
    result2 <- awaiter2.await

    summary <- Eru.succeed {
      val r1 = result1 match {
        case net.ghoula.eru.Exit.Success(value) => value
        case other => s"Failed: $other"
      }
      val r2 = result2 match {
        case net.ghoula.eru.Exit.Success(value) => value
        case other => s"Failed: $other"
      }
      s"Deferred coordination: $r1, $r2"
    }
  } yield summary
}

val deferredResult = deferredCoordination().unsafeRunSync()
println(deferredResult)
```

## Resource-Bounded Concurrency

Production systems need to limit concurrent resource usage to prevent overwhelming external systems:

### Semaphore-Based Rate Limiting

```scala mdoc
import net.ghoula.eru.coordination.Semaphore

// Semaphores control access to limited resources
def semaphoreExample(): Eru[String, List[String]] = {
  for {
    // Create semaphore with 2 permits (max 2 concurrent operations)
    semaphore <- Semaphore.make(2)

    // Define a resource-intensive operation
    def limitedOperation(id: Int): Eru[String, String] = {
      semaphore.withPermit {
        Eru.effect {
          println(s"Operation $id started (concurrent access limited)")
          // Simulate work that uses limited resources
          Thread.sleep(5) // Brief delay for demonstration
          s"Operation $id completed"
        }.mapError(_.getMessage)
      }
    }

    // Start many operations - only 2 will run concurrently
    operations = (1 to 6).map(limitedOperation).toList
    fibers <- Eru.traverse(operations)(_.fork)

    // Wait for all to complete
    exits <- Eru.traverse(fibers)(_.await)
    results <- Eru.succeed(exits.collect {
      case net.ghoula.eru.Exit.Success(value) => value
    })

  } yield results
}

val semaphoreResults = semaphoreExample().unsafeRunSync()
println(s"Semaphore-controlled operations: ${semaphoreResults.size} completed")
semaphoreResults.foreach(println)
```

### Connection Pool Pattern

```scala mdoc
// Simulate a connection pool using semaphores and resource management
case class Connection(id: String) {
  def execute(query: String): String = s"$id executed: $query"
  def close(): Unit = println(s"Connection $id returned to pool")
}

class ConnectionPool(maxConnections: Int) {
  private val semaphore = Semaphore.make(maxConnections).unsafeRunSync()
  private var connectionCounter = 0

  def withConnection[A](operation: Connection => Eru[String, A]): Eru[String, A] = {
    semaphore.withPermit {
      for {
        // Acquire connection
        connection <- Eru.effect {
          connectionCounter += 1
          Connection(s"conn-$connectionCounter")
        }.mapError(_.getMessage)

        // Use connection with guaranteed cleanup
        result <- operation(connection).ensure(
          Eru.effect(connection.close()).mapError(_.getMessage)
        )
      } yield result
    }
  }
}

def connectionPoolDemo(): Eru[String, List[String]] = {
  val pool = ConnectionPool(maxConnections = 2)

  // Define database operations
  def databaseOperation(id: Int): Eru[String, String] = {
    pool.withConnection { connection =>
      Eru.effect {
        connection.execute(s"SELECT * FROM table WHERE id = $id")
      }.mapError(_.getMessage)
    }
  }

  // Execute multiple database operations
  val operations = (1 to 5).map(databaseOperation).toList

  for {
    fibers <- Eru.traverse(operations)(_.fork)
    exits <- Eru.traverse(fibers)(_.await)
    results <- Eru.succeed(exits.collect {
      case net.ghoula.eru.Exit.Success(value) => value
    })
  } yield results
}

val poolResults = connectionPoolDemo().unsafeRunSync()
println(s"Connection pool operations: ${poolResults.size} completed")
poolResults.foreach(println)
```

## Advanced Racing Patterns

Beyond simple racing, production systems need sophisticated timeout and racing strategies:

### Timeout with Fallback

```scala mdoc
import net.ghoula.eru.InterruptCause

// Create timeout patterns for robust service calls
def serviceWithTimeout[A](
  operation: Eru[String, A],
  timeoutMs: Long,
  fallback: A
): Eru[String, A] = {

  // Create a timeout operation
  val timeout = Eru.effect {
    Thread.sleep(timeoutMs)
    throw new RuntimeException("Operation timed out")
  }.mapError(_.getMessage)

  // Race the operation against timeout
  operation.race(timeout).map {
    case Left(result) => result      // Operation completed first
    case Right(_) => fallback        // Timeout occurred, use fallback
  }.catchAll(_ => Eru.succeed(fallback))  // Any error results in fallback
}

// Test timeout behavior
def fastService(): Eru[String, String] = {
  Eru.effect("Fast service response").mapError(_.getMessage)
}

def slowService(): Eru[String, String] = {
  Eru.effect {
    Thread.sleep(100) // This will timeout
    "Slow service response"
  }.mapError(_.getMessage)
}

def timeoutDemo(): Eru[String, String] = {
  for {
    fastResult <- serviceWithTimeout(fastService(), 50, "Fast fallback")
    slowResult <- serviceWithTimeout(slowService(), 50, "Slow fallback")
  } yield s"Fast: $fastResult, Slow: $slowResult"
}

val timeoutResults = timeoutDemo().unsafeRunSync()
println(s"Timeout demo: $timeoutResults")
```

### Circuit Breaker Pattern

```scala mdoc
// Implement a basic circuit breaker pattern
class CircuitBreaker(failureThreshold: Int, recoveryTimeout: Long) {
  private var failureCount = 0
  private var lastFailureTime = 0L
  private var isOpen = false

  def execute[A](operation: Eru[String, A]): Eru[String, A] = {
    if (isOpen && (System.currentTimeMillis() - lastFailureTime) < recoveryTimeout) {
      Eru.fail("Circuit breaker is OPEN - failing fast")
    } else {
      operation.tapError { _ =>
        Eru.effect {
          failureCount += 1
          lastFailureTime = System.currentTimeMillis()
          if (failureCount >= failureThreshold) {
            isOpen = true
            println(s"Circuit breaker OPENED after $failureCount failures")
          }
        }.mapError(_.getMessage)
      }.tap { _ =>
        Eru.effect {
          // Success resets the circuit breaker
          failureCount = 0
          isOpen = false
        }.mapError(_.getMessage)
      }
    }
  }
}

def circuitBreakerDemo(): Eru[String, List[String]] = {
  val circuitBreaker = CircuitBreaker(failureThreshold = 2, recoveryTimeout = 1000)

  // Simulate an unreliable service
  var callCount = 0
  def unreliableService(): Eru[String, String] = {
    callCount += 1
    if (callCount <= 3) {
      Eru.fail(s"Service failure #$callCount")
    } else {
      Eru.succeed(s"Service success #$callCount")
    }
  }

  // Make several calls through the circuit breaker
  val calls = (1 to 5).map { i =>
    circuitBreaker.execute(unreliableService()).attempt.map {
      case net.ghoula.eru.Result.Success(result) => s"Call $i: SUCCESS - $result"
      case net.ghoula.eru.Result.Failure(error) => s"Call $i: FAILURE - $error"
    }
  }.toList

  Eru.collectAll(calls)
}

val circuitResults = circuitBreakerDemo().unsafeRunSync()
println("Circuit breaker demo results:")
circuitResults.foreach(println)
```

## Producer-Consumer Patterns

Coordinate producers and consumers with backpressure handling:

```scala mdoc
import net.ghoula.eru.coordination.Queue

// Producer-consumer pattern with bounded queues
def producerConsumerDemo(): Eru[String, String] = {
  for {
    // Create bounded queue for backpressure
    queue <- Queue.bounded[String](capacity = 3)

    // Producer fiber
    producer <- Eru.traverse((1 to 5).toList) { i =>
      val item = s"item-$i"
      queue.offer(item).map { offered =>
        if (offered) {
          println(s"Produced: $item")
          s"Produced $item"
        } else {
          println(s"Queue full, dropped: $item")
          s"Dropped $item"
        }
      }
    }.fork

    // Consumer fibers
    consumer1 <- Eru.iterate(0)(_ =>
      queue.take.map { item =>
        println(s"Consumer1 consumed: $item")
        item
      }
    )(_ => false).fork  // This will run once then complete

    consumer2 <- Eru.iterate(0)(_ =>
      queue.take.map { item =>
        println(s"Consumer2 consumed: $item")
        item
      }
    )(_ => false).fork  // This will run once then complete

    // Let producer complete
    producerResult <- producer.await

    // Brief delay to let some consumption happen
    _ <- Eru.effect(Thread.sleep(10)).mapError(_.getMessage)

    // Interrupt consumers (they would run forever otherwise)
    _ <- consumer1.interrupt(InterruptCause.Cancelled(Some("Demo complete")))
    _ <- consumer2.interrupt(InterruptCause.Cancelled(Some("Demo complete")))

    result <- producerResult match {
      case net.ghoula.eru.Exit.Success(items) =>
        Eru.succeed(s"Producer completed ${items.size} items")
      case other =>
        Eru.succeed(s"Producer failed: $other")
    }

  } yield result
}

val producerConsumerResult = producerConsumerDemo().attempt.unsafeRunSync()
producerConsumerResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Producer-consumer: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Producer-consumer failed: $error")
}
```

## Pipeline Processing Patterns

Build processing pipelines with stages that can run concurrently:

```scala mdoc
// Multi-stage processing pipeline
case class Item(id: Int, data: String, processed: Boolean = false)

def pipelineDemo(): Eru[String, List[Item]] = {

  // Stage 1: Input validation
  def validateStage(item: Item): Eru[String, Item] = {
    if (item.data.nonEmpty) {
      Eru.succeed(item.copy(data = s"validated-${item.data}"))
    } else {
      Eru.fail(s"Invalid item: ${item.id}")
    }
  }

  // Stage 2: Data transformation
  def transformStage(item: Item): Eru[String, Item] = {
    Eru.effect {
      item.copy(data = item.data.toUpperCase, processed = true)
    }.mapError(_.getMessage)
  }

  // Stage 3: Output preparation
  def outputStage(item: Item): Eru[String, Item] = {
    Eru.succeed(item.copy(data = s"${item.data}-FINAL"))
  }

  // Pipeline composition
  def processItem(item: Item): Eru[String, Item] = {
    for {
      validated <- validateStage(item)
      transformed <- transformStage(validated)
      output <- outputStage(transformed)
    } yield output
  }

  // Process multiple items through the pipeline
  val inputItems = List(
    Item(1, "data1"),
    Item(2, "data2"),
    Item(3, ""),        // This will fail validation
    Item(4, "data4"),
    Item(5, "data5")
  )

  // Process in parallel
  for {
    fibers <- Eru.traverse(inputItems)(item => processItem(item).attempt.fork)
    exits <- Eru.traverse(fibers)(_.await)
    results <- Eru.succeed {
      exits.collect {
        case net.ghoula.eru.Exit.Success(net.ghoula.eru.Result.Success(item)) => item
      }
    }
  } yield results
}

val pipelineResults = pipelineDemo().unsafeRunSync()
println(s"Pipeline processing completed ${pipelineResults.size} items:")
pipelineResults.foreach { item =>
  println(s"  Item ${item.id}: ${item.data} (processed: ${item.processed})")
}
```

## Error Isolation and Recovery

Advanced error handling patterns for concurrent systems:

```scala mdoc
// Bulkhead pattern - isolate failures between different service groups
def bulkheadPattern(): Eru[String, String] = {

  // Critical service group
  def criticalService(): Eru[String, String] = {
    Eru.succeed("Critical service OK")
  }

  // Non-critical service group
  def nonCriticalService(): Eru[String, String] = {
    Eru.fail("Non-critical service failed")
  }

  // Optional service group
  def optionalService(): Eru[String, String] = {
    Eru.succeed("Optional service OK")
  }

  for {
    // Run service groups in parallel with isolated error handling
    criticalFiber <- criticalService().fork
    nonCriticalFiber <- nonCriticalService().attempt.fork  // Isolate failures
    optionalFiber <- optionalService().attempt.fork        // Isolate failures

    // Collect results with different error handling strategies
    critical <- criticalFiber.await.flatMap {
      case net.ghoula.eru.Exit.Success(result) => Eru.succeed(result)
      case net.ghoula.eru.Exit.Failure(error) => Eru.fail(s"Critical failure: $error")
      case other => Eru.fail(s"Critical system error: $other")
    }

    nonCritical <- nonCriticalFiber.await.map {
      case net.ghoula.eru.Exit.Success(net.ghoula.eru.Result.Success(result)) => result
      case _ => "Non-critical fallback"  // Failures are acceptable
    }

    optional <- optionalFiber.await.map {
      case net.ghoula.eru.Exit.Success(net.ghoula.eru.Result.Success(result)) => result
      case _ => "Optional disabled"      // Failures are ignored
    }

    summary <- Eru.succeed(s"Services: critical=[$critical], non-critical=[$nonCritical], optional=[$optional]")

  } yield summary
}

val bulkheadResult = bulkheadPattern().attempt.unsafeRunSync()
bulkheadResult match {
  case net.ghoula.eru.Result.Success(result) => println(s"Bulkhead pattern: $result")
  case net.ghoula.eru.Result.Failure(error) => println(s"Bulkhead pattern failed: $error")
}
```

## Key Takeaways

Advanced concurrency patterns provide the building blocks for robust, production-ready concurrent systems:

**Parallel Processing**: Use `parTraverse` and custom patterns to efficiently process collections while controlling resource usage.

**Coordination Primitives**: Promise, Deferred, and Semaphore enable sophisticated coordination between independent fibers.

**Resource Bounding**: Semaphores and connection pools prevent resource exhaustion and provide backpressure mechanisms.

**Advanced Racing**: Timeout patterns, circuit breakers, and fallback strategies make systems resilient to failures and slow dependencies.

**Pipeline Processing**: Multi-stage processing with concurrent stages enables high-throughput data processing pipelines.

**Error Isolation**: Bulkhead patterns and isolated error handling prevent failures in one component from cascading to others.

**Production Readiness**: These patterns provide the foundation for building systems that can handle real-world production loads and failure scenarios.

## What's Next

In Chapter 11, we'll explore Eru's observability system with `EruObserver`, covering structured logging, distributed tracing, performance monitoring, and debugging techniques that make concurrent programs transparent and maintainable.

---

*"The art of concurrent programming is not just making things run in parallel, but coordinating them to work together as a coherent whole."*