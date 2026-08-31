# Chapter 11: Observability and debugging

This chapter covers Eru's observability system: `EruObserver` events, distributed tracing, and the debugging techniques that apply to concurrent programs.

## The observer system

Eru provides structured observability through the `EruObserver` trait, which receives program lifecycle events as they occur:

```scala mdoc
import net.ghoula.eru.prelude.*

// Define a simple observer for demonstration
class LoggingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    event match {
      case EruEvent.ProgramStart(scopeId) =>
        println(s"Program started with scope $scopeId")

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        val outcomeStr = outcome match {
          case EruObserver.Outcome.Success => "Success"
          case EruObserver.Outcome.TypedFailure(error) => s"TypedFailure($error)"
          case EruObserver.Outcome.Defect(throwable) => s"Defect(${throwable.getMessage})"
        }
        println(s"Program ended with scope $scopeId: $outcomeStr")

      case EruEvent.FiberStarted(fiberId) =>
        println(s"Fiber $fiberId started")

      case EruEvent.FiberCompleted(fiberId, exit) =>
        println(s"Fiber $fiberId completed: $exit")

      case EruEvent.Step(scopeId, label) =>
        println(s"Debug step [$scopeId]: $label")

      case _ => // Handle other event types
        println(s"Other event: $event")
    }
  }
}

// Create runtime (standard creation)
given runtime: EruRuntime = EruRuntime.create()

// Simple program to demonstrate observation
def observableProgram(): Eru[String, String] = {
  for {
    _      <- Eru.succeed("Starting").debug("Starting computation")
    result <- Eru.effect("Hello, Observable World!").mapError(_.getMessage)
    _      <- Eru.succeed(result).debug(s"Computation completed with result: $result")
  } yield result
}

// Run with observer
val observer = LoggingObserver()
val observedResult = observableProgram().runWith(observer)
println(s"Final result: $observedResult")
```

## Event categories

The observer system captures different types of events throughout program execution:

### Program lifecycle events

```scala mdoc
// Program lifecycle tracking
def lifecycleDemo(): Eru[String, String] = {
  for {
    _ <- Eru.succeed(()).debug("Phase 1: Initialization")

    // Simulate some initialization work
    config <- Eru.effect("Configuration loaded").mapError(_.getMessage)
    _ <- Eru.succeed(config).debug(s"Configuration phase complete: $config")

    _ <- Eru.succeed(()).debug("Phase 2: Processing")

    // Main processing
    result <- Eru.effect {
      "Processing completed successfully"
    }.mapError(_.getMessage)

    _ <- Eru.succeed(()).debug("Phase 3: Cleanup")

    // Cleanup
    _ <- Eru.effect(println("Resources cleaned up")).mapError(_.getMessage)
    _ <- Eru.succeed(()).debug("All phases complete")

  } yield result
}

val lifecycleResult = lifecycleDemo().runWith(observer)
println(s"Lifecycle demo result: $lifecycleResult")
```

### Fiber lifecycle events

Fiber events (`FiberStarted`, `FiberCompleted`) are emitted only when a fiber is forked with an observer: use `forkWithObserver(observer)`. Plain `fork` does not attach an observer, and `debug` steps inside a backend-forked fiber are not emitted (the fiber body runs outside the observer's hook pipeline).

```scala mdoc
// Monitor concurrent fiber execution
def concurrentObservation(): Eru[String | Throwable, List[String]] = {
  for {
    _ <- Eru.succeed(()).debug("Starting concurrent operations")

    // Fork multiple fibers with observation
    fiber1 <- (for {
      result <- Eru.effect("Worker 1 completed").mapError(_.getMessage)
    } yield result).forkWithObserver(observer)

    fiber2 <- (for {
      result <- Eru.effect("Worker 2 completed").mapError(_.getMessage)
    } yield result).forkWithObserver(observer)

    _ <- Eru.succeed(()).debug("All workers forked, awaiting completion")

    // Collect results
    exit1 <- fiber1.await
    exit2 <- fiber2.await

    results <- Eru.succeed {
      List(exit1, exit2).collect {
        case net.ghoula.eru.Exit.Success(value) => value
      }
    }

    _ <- Eru.succeed(()).debug(s"All concurrent work completed with ${results.size} results")

  } yield results
}

val concurrentResults = concurrentObservation().runWith(observer)
println(s"Concurrent observation results: ${concurrentResults.mkString(", ")}")
```

## Custom debug events

Use debug events to instrument your business logic for detailed tracing:

```scala mdoc
// Business logic with debug instrumentation
case class Order(id: String, customerId: String, amount: Double)
case class Customer(id: String, name: String, creditLimit: Double)

def processOrder(order: Order): Eru[String, String] = {
  for {
    _ <- Eru.succeed(()).debug(s"Order processing started: orderId=${order.id}, customerId=${order.customerId}, amount=${order.amount}")

    // Step 1: Customer validation
    _ <- Eru.succeed(()).debug("Validating customer")
    customer <- validateCustomer(order.customerId)
    _ <- Eru.succeed(customer).debug(s"Customer validated: name=${customer.name}, creditLimit=${customer.creditLimit}")

    // Step 2: Credit check
    _ <- Eru.succeed(()).debug("Performing credit check")
    _ <- checkCredit(customer, order.amount)
    _ <- Eru.succeed(()).debug("Credit check passed")

    // Step 3: Process payment
    _ <- Eru.succeed(()).debug("Processing payment")
    paymentResult <- processPayment(order)
    _ <- Eru.succeed(paymentResult).debug(s"Payment processed: $paymentResult")

    // Step 4: Update inventory
    _ <- Eru.succeed(()).debug("Updating inventory")
    _ <- updateInventory(order)
    _ <- Eru.succeed(()).debug("Inventory updated")

    finalResult <- Eru.succeed(s"Order ${order.id} processed successfully")
    _ <- Eru.succeed(finalResult).debug(s"Order processing completed: $finalResult")

  } yield finalResult
}

// Supporting functions for the order processing example
def validateCustomer(customerId: String): Eru[String, Customer] = {
  if (customerId.nonEmpty) {
    Eru.succeed(Customer(customerId, s"Customer-$customerId", 1000.0))
  } else {
    Eru.fail("Invalid customer ID")
  }
}

def checkCredit(customer: Customer, amount: Double): Eru[String, Unit] = {
  if (amount <= customer.creditLimit) {
    Eru.succeed(())
  } else {
    Eru.fail(s"Credit limit exceeded: $amount > ${customer.creditLimit}")
  }
}

def processPayment(order: Order): Eru[String, String] = {
  Eru.succeed(s"Payment-${order.id}-${System.currentTimeMillis() % 1000}")
}

def updateInventory(order: Order): Eru[String, Unit] = {
  Eru.succeed(())
}

// Test order processing with full observation
val testOrder = Order("ORD-123", "CUST-456", 750.0)
val orderResult = processOrder(testOrder).attempt.runWith(observer)

orderResult match {
  case net.ghoula.eru.Result.Success(result) =>
    println(s"Order processing succeeded: $result")
  case net.ghoula.eru.Result.Failure(error) =>
    println(s"Order processing failed: $error")
}
```

## Performance monitoring

Use observers to collect performance metrics and identify bottlenecks:

```scala mdoc
// Performance monitoring observer
class PerformanceObserver extends EruObserver {
  private val startTimes = scala.collection.mutable.Map[String, Long]()
  private val metrics = scala.collection.mutable.Map[String, Long]()

  def onEvent(event: EruEvent): Unit = {
    val currentTime = System.currentTimeMillis()

    event match {
      case EruEvent.ProgramStart(scopeId) =>
        startTimes(s"program-$scopeId") = currentTime
        println(s"📊 Program started with scope $scopeId")

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        startTimes.get(s"program-$scopeId").foreach { startTime =>
          val duration = currentTime - startTime
          metrics(s"program-$scopeId-duration") = duration
          println(s"📊 Program $scopeId executed in ${duration}ms with outcome: $outcome")
        }

      case EruEvent.FiberStarted(fiberId) =>
        startTimes(s"fiber-$fiberId") = currentTime
        println(s"📊 Fiber $fiberId started")

      case EruEvent.FiberCompleted(fiberId, exit) =>
        startTimes.get(s"fiber-$fiberId").foreach { startTime =>
          val duration = currentTime - startTime
          metrics(s"fiber-$fiberId-duration") = duration
          println(s"📊 Fiber $fiberId completed in ${duration}ms with exit: $exit")
        }

      case EruEvent.Step(scopeId, label) =>
        if (label.contains("performance")) {
          println(s"🔍 Performance marker [$scopeId]: $label")
        }

      case _ => // Ignore other events
    }
  }

  def getMetrics: Map[String, Long] = metrics.toMap
}

// Create performance observer
val perfObserver = PerformanceObserver()

// Program with performance markers
def performanceTrackedProgram(): Eru[String, String] = {
  for {
    _ <- Eru.succeed(()).debug("performance: Starting heavy computation")

    // Simulate CPU-intensive work
    result <- Eru.effect {
      val computation = (1 to 10000).map(_ * 2).sum
      s"Heavy computation result: $computation"
    }.mapError(_.getMessage)

    _ <- Eru.succeed(result).debug(s"performance: Heavy computation completed with result: $result")

    _ <- Eru.succeed(()).debug("performance: Starting I/O simulation")

    // Simulate I/O work
    ioResult <- Eru.effect {
      Thread.sleep(5) // Brief I/O simulation
      "I/O operation completed"
    }.mapError(_.getMessage)

    _ <- Eru.succeed(ioResult).debug(s"performance: I/O operation completed: $ioResult")

  } yield s"$result | $ioResult"
}

val perfResult = performanceTrackedProgram().runWith(perfObserver)
println(s"Performance tracked result: $perfResult")

// Print collected metrics
val collectedMetrics = perfObserver.getMetrics
println("\nCollected Performance Metrics:")
collectedMetrics.foreach { case (key, value) =>
  println(s"  $key: ${value}ms")
}
```

## Tracing

Two complementary mechanisms exist, and the distinction matters:

- **Trace spans** (`traced`, `withTraceBaggage`, `EruTrace`): a thread-local span context. A `traced` effect runs inside a span recorded on the context; when the effect completes, the ambient context is restored, so spans never leak past the effect boundary. The context does not cross process or network boundaries: for actual distributed tracing, propagate the `TraceId`/`SpanId` through your transport and re-establish the context on the receiving side.
- **Event correlation**: observer events carry program and fiber IDs for correlating work within one process.

### Trace spans

```scala mdoc
def tracedWork(): Eru[Throwable | String, String] =
  Eru
    .effect("computed")
    .mapError(_.getMessage)
    .traced("compute")                     // span with default tags
    .withTraceBaggage("requestId", "abc")  // baggage scoped to this effect

val tracedResult = tracedWork().attempt.unsafeRunSync()
println(s"Traced result: $tracedResult")
```

### Event-based correlation

Observer events let you correlate work within one process. Know which events actually fire:

- `ProgramStart`/`ProgramEnd`/`Step` fire in the observed main flow (`runWith`).
- `FiberStarted`/`FiberCompleted` fire only for fibers forked with `forkWithObserver(observer)`.
- `StructuredCleanupStarted`/`StructuredCleanupCompleted`/`ChildInterruptionRequested` fire only when you call `runtime.shutdownRootFibers(Some(observer))` — the automatic per-scope unwind is observer-silent.
- `FiberInterrupted`, `FiberForked`, and `TraceSpan` are defined for completeness but never emitted by the interpreter.

```scala mdoc
// Correlate fibers within one program run
def correlatedWork(): Eru[String | Throwable, List[String]] = {
  for {
    fiber1 <- Eru.effect("a").mapError(_.getMessage).forkWithObserver(observer)
    fiber2 <- Eru.effect("b").mapError(_.getMessage).forkWithObserver(observer)
    exit1  <- fiber1.await
    exit2  <- fiber2.await
  } yield List(exit1, exit2).collect {
    case net.ghoula.eru.Exit.Success(value) => value
  }
}

val correlatedResult = correlatedWork().attempt.unsafeRunSync()
println(s"Correlated work result: $correlatedResult")
```

### Structured cleanup events

The structured-cleanup event trio is emitted by the observable shutdown path:

```scala mdoc
// Fork a root fiber, then shut the runtime down observably. The observer receives
// StructuredCleanupStarted, ChildInterruptionRequested (with the root boundary identity
// FiberId.Root), and StructuredCleanupCompleted.
val _ = Eru.effect("tracked root work").mapError(_.getMessage).fork.unsafeRunSync()
val (interrupted, alreadyCompleted) =
  runtime.shutdownRootFibers(Some(observer)).unsafeRunSync()
println(s"Shutdown: $interrupted interrupted, $alreadyCompleted already completed")
```

## Error debugging

Use observers to capture detailed error context:

```scala mdoc
// Error context observer
class ErrorContextObserver extends EruObserver {
  private val errorContexts = scala.collection.mutable.Map[String, List[String]]()
  private val executionSteps = scala.collection.mutable.Map[String, scala.collection.mutable.ListBuffer[String]]()

  def onEvent(event: EruEvent): Unit = {
    event match {
      case EruEvent.ProgramStart(scopeId) =>
        executionSteps(scopeId.toString) = scala.collection.mutable.ListBuffer[String]()

      case EruEvent.Step(scopeId, label) =>
        executionSteps.get(scopeId.toString).foreach { steps =>
          steps += label
        }

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        outcome match {
          case EruObserver.Outcome.TypedFailure(_) | EruObserver.Outcome.Defect(_) =>
            executionSteps.get(scopeId.toString).foreach { steps =>
              errorContexts(scopeId.toString) = steps.toList
            }
          case _ => // Success cases don't need error context
        }

      case _ => // Ignore other events
    }
  }

  def getErrorContext(programId: String): List[String] = {
    errorContexts.get(programId).getOrElse(List.empty)
  }

  def printAllErrorContexts(): Unit = {
    errorContexts.foreach { case (programId, context) =>
      println(s"\nError context for $programId:")
      context.foreach(step => println(s"  - $step"))
    }
  }
}

// Create error context observer
val errorObserver = ErrorContextObserver()

// Program that will fail with detailed context
def failingProgram(): Eru[String, String] = {
  for {
    _ <- Eru.succeed(()).debug("Starting data validation")

    data <- Eru.effect("input-data").mapError(_.getMessage)
    _ <- Eru.succeed(data).debug(s"Data loaded with size: ${data.length}")

    _ <- Eru.succeed(()).debug("Starting transformation phase")

    // This step will fail
    processed <- if (data.contains("invalid")) {
      for {
        _ <- Eru.succeed(()).debug("Invalid data detected")
        result <- Eru.fail("Data validation failed: contains invalid content")
      } yield result
    } else {
      for {
        _ <- Eru.succeed(()).debug("Data processing step 1")
        result <- Eru.effect(data.toUpperCase).mapError(_.getMessage)
      } yield result
    }

    _ <- Eru.succeed(processed).debug(s"Transformation completed: $processed")

    finalResult <- Eru.succeed(s"Processed: $processed")
    _ <- Eru.succeed(()).debug("All processing completed")

  } yield finalResult
}

// Test with both success and failure cases
println("=== Testing Success Case ===")
val successResult = failingProgram().attempt.runWith(errorObserver)
println(s"Success result: $successResult")

println("\n=== Testing Failure Case ===")
// Simulate failure by using invalid data
val failureProgram = for {
  _ <- Eru.succeed(()).debug("Starting data validation")
  data <- Eru.succeed("invalid-input-data")
  _ <- Eru.succeed(data).debug(s"Data loaded with size: ${data.length}")
  _ <- Eru.succeed(()).debug("Starting transformation phase")
  _ <- Eru.succeed(()).debug("Invalid data detected")
  result <- Eru.fail("Data validation failed: contains invalid content")
} yield result

val failureResult = failureProgram.attempt.runWith(errorObserver)
println(s"Failure result: $failureResult")

// Error contexts are keyed by the program's scope id. Print them all.
errorObserver.printAllErrorContexts()
```

## Production observability patterns

### Structured logging integration

```scala mdoc
// Integration with structured logging systems
class StructuredLoggingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    val timestamp = System.currentTimeMillis()

    val logEntry = event match {
      case EruEvent.ProgramStart(scopeId) =>
        Map(
          "event" -> "program.start",
          "scopeId" -> scopeId.toString,
          "timestamp" -> timestamp.toString,
          "level" -> "INFO"
        )

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        val (level, exitType) = outcome match {
          case EruObserver.Outcome.Success => ("INFO", "success")
          case EruObserver.Outcome.TypedFailure(_) => ("WARN", "failure")
          case EruObserver.Outcome.Defect(_) => ("ERROR", "defect")
        }
        Map(
          "event" -> "program.end",
          "scopeId" -> scopeId.toString,
          "timestamp" -> timestamp.toString,
          "level" -> level,
          "exitType" -> exitType
        )

      case EruEvent.Step(scopeId, label) =>
        Map(
          "event" -> "debug",
          "scopeId" -> scopeId.toString,
          "timestamp" -> timestamp.toString,
          "step" -> label,
          "level" -> "DEBUG"
        )

      case _ =>
        Map("event" -> "unknown", "level" -> "DEBUG")
    }

    // In production, this would integrate with your logging framework
    println(s"LOG: ${logEntry.map { case (k, v) => s"$k=$v" }.mkString(" ")}")
  }
}

// Example usage with structured logging
val structuredObserver = StructuredLoggingObserver()

def businessProcess(): Eru[String, String] = {
  for {
    _ <- Eru.succeed(()).debug("business.process.start v1.0")
    result <- Eru.effect("Business logic completed").mapError(_.getMessage)
    _ <- Eru.succeed(()).debug("business.process.end success")
  } yield result
}

val structuredResult = businessProcess().runWith(structuredObserver)
println(s"Structured logging result: $structuredResult")
```

### Metrics collection

```scala mdoc
// Metrics collection observer
class MetricsObserver extends EruObserver {
  private val counters = scala.collection.mutable.Map[String, Long]()
  private val timers = scala.collection.mutable.Map[String, scala.collection.mutable.ListBuffer[Long]]()
  private val startTimes = scala.collection.mutable.Map[String, Long]()

  def onEvent(event: EruEvent): Unit = {
    val currentTime = System.currentTimeMillis()

    event match {
      case EruEvent.ProgramStart(scopeId) =>
        incrementCounter("programs.started")
        startTimes(s"program-$scopeId") = currentTime

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        incrementCounter("programs.completed")

        outcome match {
          case EruObserver.Outcome.Success => incrementCounter("programs.success")
          case EruObserver.Outcome.TypedFailure(_) => incrementCounter("programs.failure")
          case EruObserver.Outcome.Defect(_) => incrementCounter("programs.error")
        }

        startTimes.get(s"program-$scopeId").foreach { startTime =>
          recordTimer("program.duration", currentTime - startTime)
        }

      case EruEvent.FiberStarted(fiberId) =>
        incrementCounter("fibers.started")
        startTimes(s"fiber-$fiberId") = currentTime

      case EruEvent.FiberCompleted(fiberId, _) =>
        incrementCounter("fibers.completed")
        startTimes.get(s"fiber-$fiberId").foreach { startTime =>
          recordTimer("fiber.duration", currentTime - startTime)
        }

      case EruEvent.Step(_, label) =>
        incrementCounter(s"debug.steps.${label.replaceAll("\\W", "_")}")

      case _ => // Ignore other events
    }
  }

  private def incrementCounter(name: String): Unit = {
    counters(name) = counters.getOrElse(name, 0L) + 1
  }

  private def recordTimer(name: String, duration: Long): Unit = {
    timers.getOrElseUpdate(name, scala.collection.mutable.ListBuffer[Long]()) += duration
  }

  def getCounters: Map[String, Long] = counters.toMap

  def getTimerStats: Map[String, (Double, Long, Long)] = {
    timers.map { case (name, durations) =>
      val avg = durations.sum.toDouble / durations.size
      val min = durations.min
      val max = durations.max
      name -> (avg, min, max)
    }.toMap
  }

  def printMetrics(): Unit = {
    println("=== METRICS REPORT ===")
    println("Counters:")
    getCounters.foreach { case (name, count) =>
      println(s"  $name: $count")
    }
    println("Timers (avg/min/max ms):")
    getTimerStats.foreach { case (name, (avg, min, max)) =>
      println(s"  $name: ${avg.round}/${min}/${max}")
    }
  }
}

// Test metrics collection
val metricsObserver = MetricsObserver()

def metricsTestProgram(): Eru[String | Throwable, String] = {
  for {
    _ <- Eru.succeed(()).debug("initialization")

    // Fork some concurrent work
    fiber1 <- (for {
      _ <- Eru.succeed(()).debug("worker.task")
      result <- Eru.succeed("Work 1")
    } yield result).fork
    fiber2 <- (for {
      _ <- Eru.succeed(()).debug("worker.task")
      result <- Eru.succeed("Work 2")
    } yield result).fork

    _ <- Eru.succeed(()).debug("coordination")

    result1 <- fiber1.await
    result2 <- fiber2.await

    _ <- Eru.succeed(()).debug("completion")

    finalResult <- (result1, result2) match {
      case (net.ghoula.eru.Exit.Success(r1), net.ghoula.eru.Exit.Success(r2)) =>
        Eru.succeed(s"Combined: $r1, $r2")
      case _ =>
        Eru.succeed("Partial results")
    }

  } yield finalResult
}

val metricsResult = metricsTestProgram().runWith(metricsObserver)
println(s"Metrics test result: $metricsResult")

// Print collected metrics
metricsObserver.printMetrics()
```

## Key takeaways

Eru's observability system covers program execution:

Structured events: the observer system captures program lifecycle, debug steps, and — via forkWithObserver — fiber lifecycle events.

Synchronous delivery: observer callbacks run on the executing thread. Keep `onEvent` fast and non-blocking, and avoid debug steps on hot paths.

Composable: observers can log, collect metrics, or record traces.

Error context: error contexts record the debug steps that led to a failure.

Performance monitoring: observers can collect timing and metrics for SLA monitoring.

Tracing: spans and baggage live on the thread-local trace context and are scoped to their effect; distributed propagation is a transport-level concern you build on top.

Customizable: the observer trait integrates with existing observability infrastructure.

## What's next

Chapter 12 covers Eru's performance characteristics, benchmarking techniques, and optimization strategies.