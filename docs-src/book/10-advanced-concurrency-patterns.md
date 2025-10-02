# Chapter 10: Advanced Concurrency Patterns

Building on Chapter 9's introduction to fibers, this chapter explores advanced concurrency patterns that make Eru production-ready. We'll cover parallel processing strategies, coordination primitives, resource-bounded concurrency, and sophisticated racing patterns that enable robust concurrent systems.

## Parallel Collection Processing

One of the most common concurrency needs is processing collections in parallel while maintaining safety and composability:

```scala mdoc
import net.ghoula.eru.prelude.*
import net.ghoula.eru.prelude.given

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
def parallelLightWork(): Eru[String | Throwable, List[String]] = {
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
)(processor: A => Eru[String, B]): Eru[String | Throwable, List[B]] = {
  // Use the built-in foreachParN for bounded concurrency
  foreachParN(maxConcurrency, items)(processor)
}

// Test custom parallel processing
def testCustomParallel(): Eru[String | Throwable, List[String]] = {
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
// Promises allow one fiber to complete a value that other fibers await
def promiseCoordination(): Eru[String | Throwable, String] = {
  for {
    // Create a promise that will be completed later
    promise <- Eru.promise[String, String]

    // Fork a fiber that will complete the promise
    producer <- Eru.effect {
      // Simulate some work before completing the promise
      Thread.sleep(10) // Minimal delay for demonstration
      "Producer result"
    }.mapError(_.getMessage)
      .flatMap(result => promise.succeed(result).map(_ => ()).eru)
      .fork

    // Fork fibers that wait for the promise
    consumer1 <- promise.await
      .map(result => s"Consumer1 received: $result")
      .eru.fork

    consumer2 <- promise.await
      .map(result => s"Consumer2 received: $result")
      .eru.fork

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
// Deferred values provide single-assignment variables for coordination
def deferredCoordination(): Eru[Throwable, String] = {
  for {
    // Create a deferred value
    deferred <- Eru.deferred[Int]

    // Fork a computation that will complete the deferred
    computation <- Eru.effect {
      // Expensive computation
      (1 to 1000).sum
    }.mapError(_.getMessage)
      .flatMap(result => deferred.complete(result).map(_ => ()).eru)
      .fork

    // Multiple fibers can await the same deferred value
    awaiter1 <- deferred.await
      .map(value => s"Awaiter1 got: $value")
      .eru.fork

    awaiter2 <- deferred.await
      .map(value => s"Awaiter2 got: $value")
      .eru.fork

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
// Semaphores control access to limited resources
def semaphoreExample(): Eru[String | Throwable, List[String]] = {
  // Define a resource-intensive operation
  def limitedOperation(semaphore: Semaphore)(id: Int): Eru[String | Throwable, String] = {
    semaphore.withPermit {
      Eru.effect {
        println(s"Operation $id started (concurrent access limited)")
        // Simulate work that uses limited resources
        Thread.sleep(5) // Brief delay for demonstration
        s"Operation $id completed"
      }.mapError(_.getMessage)
    }.eru
  }

  for {
    // Create semaphore with 2 permits (max 2 concurrent operations)
    semaphore <- Eru.semaphore(2)

    // Start many operations - only 2 will run concurrently
    operations = (1 to 6).map(limitedOperation(semaphore)).toList
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
  private val semaphore = Eru.semaphore(maxConnections).unsafeRunSync()
  private var connectionCounter = 0

  def withConnection[A](operation: Connection => Eru[String, A]): Eru[String | Throwable, A] = {
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
    }.eru
  }
}

def connectionPoolDemo(): Eru[String | Throwable, List[String]] = {
  val pool = ConnectionPool(maxConnections = 2)

  // Define database operations
  def databaseOperation(id: Int): Eru[String | Throwable, String] = {
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
// Create timeout patterns for robust service calls
def serviceWithTimeout[A](
  operation: Eru[String, A],
  timeoutMs: Long,
  fallback: A
): Eru[String | java.util.concurrent.TimeoutException | Throwable, A] = {
  import java.time.Duration

  // Use the built-in timeout with fallback
  operation.timeoutTo(Duration.ofMillis(timeoutMs), fallback)
}

// Test timeout behavior
def fastService(): Eru[String, String] = {
  Eru.effect("Fast service response").mapError(_.getMessage)
}

def slowService(): Eru[String, String] = {
  Eru.effect {
    try {
      Thread.sleep(100) // This will timeout
      "Slow service response"
    } catch {
      case _: InterruptedException =>
        Thread.currentThread().interrupt() // Restore interrupt status
        "Slow service interrupted"
    }
  }.mapError(_.getMessage)
}

def timeoutDemo(): Eru[String | java.util.concurrent.TimeoutException | Throwable, String] = {
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
import net.ghoula.eru.prelude.given

// State for circuit breaker - immutable case class
case class CircuitBreakerState(
  failureCount: Int = 0,
  lastFailureTime: Long = 0L,
  isOpen: Boolean = false
)

// Functional circuit breaker using Ref for thread-safe state management
class FunctionalCircuitBreaker(failureThreshold: Int, recoveryTimeout: Long) {
  private val stateRef = Eru.ref(CircuitBreakerState()).unsafeRunSync()

  def execute[A](operation: Eru[String, A]): Eru[String | Throwable, A] = {
    for {
      state <- stateRef.get
      currentTime = System.currentTimeMillis()

      result <- if (state.isOpen && (currentTime - state.lastFailureTime) < recoveryTimeout) {
        Eru.fail("Circuit breaker is OPEN - failing fast")
      } else {
        operation.tapError { _ =>
          // Update state atomically on failure
          stateRef.update { currentState =>
            val newFailureCount = currentState.failureCount + 1
            val newState = currentState.copy(
              failureCount = newFailureCount,
              lastFailureTime = currentTime,
              isOpen = newFailureCount >= failureThreshold
            )
            if (!currentState.isOpen && newState.isOpen) {
              println(s"Circuit breaker OPENED after $newFailureCount failures")
            }
            newState
          }.map(_ => ())
        }.tap { _ =>
          // Reset state on success
          stateRef.set(CircuitBreakerState()).map(_ => ())
        }
      }
    } yield result
  }
}

def circuitBreakerDemo(): Eru[String | Throwable, List[String]] = {
  val circuitBreaker = new FunctionalCircuitBreaker(failureThreshold = 2, recoveryTimeout = 1000)

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

  Eru.sequence(calls)
}

val circuitResults = circuitBreakerDemo().unsafeRunSync()
println("Circuit breaker demo results:")
circuitResults.foreach(println)
```

## Producer-Consumer Patterns

Coordinate producers and consumers with backpressure handling:

```scala mdoc
// Producer-consumer pattern with bounded queues
def producerConsumerDemo(): Eru[String | Throwable, String] = {
  for {
    // Create bounded queue for backpressure
    queue <- Eru.queue[String](capacity = 3)

    // Producer fiber that adds items to queue
    producer <- Eru.traverse((1 to 5).toList) { i =>
      val item = s"item-$i"
      queue.put(item).map { _ =>
        println(s"Produced: $item")
        s"Produced $item"
      }.eru
    }.fork

    // Consumer fiber that takes items from queue
    consumer <- (for {
      item1 <- queue.take.map { item =>
        println(s"Consumer consumed: $item")
        item
      }.eru
      item2 <- queue.take.map { item =>
        println(s"Consumer consumed: $item")
        item
      }.eru
      item3 <- queue.take.map { item =>
        println(s"Consumer consumed: $item")
        item
      }.eru
    } yield List(item1, item2, item3)).fork

    // Wait for both to complete
    producerResult <- producer.await
    consumerResult <- consumer.await

    result <- (producerResult, consumerResult) match {
      case (net.ghoula.eru.Exit.Success(produced), net.ghoula.eru.Exit.Success(consumed)) =>
        Eru.succeed(s"Produced ${produced.size} items, consumed ${consumed.size} items")
      case (failure, _) =>
        Eru.succeed(s"Demo failed: producer=$failure")
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

def pipelineDemo(): Eru[String | Throwable, List[Item]] = {

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
  def processItem(item: Item): Eru[String | Throwable, Item] = {
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
def bulkheadPattern(): Eru[String | Throwable, String] = {

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

Chapter 11 explores Eru's observability system with `EruObserver`, covering structured logging, distributed tracing, performance monitoring, and debugging techniques that make concurrent programs transparent and maintainable.