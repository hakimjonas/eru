# Chapter 11: Observability & Debugging

Observability is one of Eru's four foundational pillars. This chapter explores Eru's comprehensive observability system through `EruObserver`, covering structured event monitoring, distributed tracing, performance analysis, and debugging techniques that make concurrent programs transparent and maintainable.

## The Observer System

Eru provides structured observability through the `EruObserver` interface, which captures program lifecycle events with minimal overhead:

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

## Event Categories

The observer system captures different types of events throughout program execution:

### Program Lifecycle Events

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

### Fiber Lifecycle Events

```scala mdoc
// Monitor concurrent fiber execution
def concurrentObservation(): Eru[String | Throwable, List[String]] = {
  for {
    _ <- Eru.succeed(()).debug("Starting concurrent operations")

    // Fork multiple fibers with observation
    fiber1 <- (for {
      _ <- Eru.succeed(()).debug("Worker 1 starting")
      result <- Eru.effect("Worker 1 completed").mapError(_.getMessage)
      _ <- Eru.succeed(result).debug(s"Worker 1 finished with result: $result")
    } yield result).fork

    fiber2 <- (for {
      _ <- Eru.succeed(()).debug("Worker 2 starting")
      result <- Eru.effect("Worker 2 completed").mapError(_.getMessage)
      _ <- Eru.succeed(result).debug(s"Worker 2 finished with result: $result")
    } yield result).fork

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

## Custom Debug Events

Use debug events to instrument your business logic for detailed tracing:

```scala mdoc
// Business logic with comprehensive debug instrumentation
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

## Performance Monitoring

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
        println(s"Program started with scope $scopeId")

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        startTimes.get(s"program-$scopeId").foreach { startTime =>
          val duration = currentTime - startTime
          metrics(s"program-$scopeId-duration") = duration
          println(s"Program $scopeId executed in ${duration}ms with outcome: $outcome")
        }

      case EruEvent.FiberStarted(fiberId) =>
        startTimes(s"fiber-$fiberId") = currentTime
        println(s"Fiber $fiberId started")

      case EruEvent.FiberCompleted(fiberId, exit) =>
        startTimes.get(s"fiber-$fiberId").foreach { startTime =>
          val duration = currentTime - startTime
          metrics(s"fiber-$fiberId-duration") = duration
          println(s"Fiber $fiberId completed in ${duration}ms with exit: $exit")
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

## Distributed Tracing

Build distributed tracing systems using program and fiber IDs:

```scala mdoc
// Distributed tracing observer
class TracingObserver extends EruObserver {
  private val traces = scala.collection.mutable.Map[String, scala.collection.mutable.ListBuffer[String]]()

  def onEvent(event: EruEvent): Unit = {
    val timestamp = System.currentTimeMillis()

    event match {
      case EruEvent.ProgramStart(scopeId) =>
        val traceBuffer = scala.collection.mutable.ListBuffer[String]()
        traceBuffer += s"TRACE [$timestamp] Program $scopeId started"
        traces(scopeId.toString) = traceBuffer

      case EruEvent.ProgramEnd(scopeId, outcome) =>
        traces.get(scopeId.toString).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp] Program $scopeId ended: $outcome"
        }

      case EruEvent.FiberStarted(fiberId) =>
        // For fiber events, we associate with the current scope
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   → Fiber $fiberId started"
        }

      case EruEvent.FiberCompleted(fiberId, exit) =>
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   ← Fiber $fiberId ended: $exit"
        }

      case EruEvent.Step(scopeId, label) =>
        traces.get(scopeId.toString).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   • [$scopeId] $label"
        }

      case EruEvent.FiberInterrupted(fiberId, cause) =>
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   ✕ Fiber $fiberId interrupted: $cause"
        }

      case EruEvent.FiberForked(parentId, childId) =>
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   ⎇ Fiber $parentId forked child $childId"
        }

      case EruEvent.StructuredCleanupStarted(scopeId, childCount) =>
        traces.get(scopeId.toString).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   🧹 Cleanup started for scope $scopeId ($childCount children)"
        }

      case EruEvent.StructuredCleanupCompleted(scopeId, childCount, outcome) =>
        traces.get(scopeId.toString).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   ✓ Cleanup completed for scope $scopeId ($childCount children): $outcome"
        }

      case EruEvent.ChildInterruptionRequested(parentId, childId, signal, reason) =>
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   Parent $parentId requested interruption of child $childId ($signal): $reason"
        }

      case EruEvent.TraceSpan(span) =>
        traces.values.lastOption.foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   Trace span: $span"
        }
    }
  }

  def getTrace(programId: String): List[String] = {
    traces.get(programId).map(_.toList).getOrElse(List.empty)
  }

  def printTrace(programId: String): Unit = {
    getTrace(programId).foreach(println)
  }

  def printAllTraces(): Unit = {
    traces.foreach { case (id, buffer) =>
      println(s"\nTrace for $id:")
      buffer.foreach(println)
    }
  }
}

// Create tracing observer
val tracingObserver = TracingObserver()

// Distributed service simulation
def distributedServiceCall(): Eru[String | Throwable, String] = {
  for {
    _ <- Eru.succeed(()).debug("Incoming request received for /api/users")

    // Service call 1: User service
    userFiber <- (for {
      _ <- Eru.succeed(()).debug("Calling user service")
      user <- Eru.effect("User data retrieved").mapError(_.getMessage)
      _ <- Eru.succeed(user).debug(s"User service call completed: $user")
    } yield user).fork

    // Service call 2: Preferences service
    prefsFiber <- (for {
      _ <- Eru.succeed(()).debug("Calling preferences service")
      prefs <- Eru.effect("User preferences retrieved").mapError(_.getMessage)
      _ <- Eru.succeed(prefs).debug(s"Preferences service call completed: $prefs")
    } yield prefs).fork

    _ <- Eru.succeed(()).debug("All service calls initiated, awaiting responses")

    // Collect results
    userExit <- userFiber.await
    prefsExit <- prefsFiber.await

    response <- (userExit, prefsExit) match {
      case (net.ghoula.eru.Exit.Success(user), net.ghoula.eru.Exit.Success(prefs)) =>
        val combined = s"$user + $prefs"
        for {
          _ <- Eru.succeed(combined).debug(s"Response prepared: $combined")
          result <- Eru.succeed(combined)
        } yield result
      case _ =>
        for {
          _ <- Eru.succeed(()).debug("Service calls failed")
          result <- Eru.fail("Service call failure")
        } yield result
    }

    _ <- Eru.succeed(()).debug("Request processing completed")

  } yield response
}

// Execute with tracing
val tracedResult = distributedServiceCall().attempt.runWith(tracingObserver)

println(s"Distributed service result: $tracedResult")
println("\nDistributed Traces:")
tracingObserver.printAllTraces()
```

## Error Debugging

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

  def printErrorContext(programId: String): Unit = {
    val context = getErrorContext(programId)
    if (context.nonEmpty) {
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

// Print error context for debugging
errorObserver.printErrorContext("failure-program")
```

## Production Observability Patterns

### Structured Logging Integration

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

### Metrics Collection

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

## Key Takeaways

Eru's observability system provides comprehensive insights into program execution:

**Structured Events**: The observer system captures program lifecycle, fiber operations, and custom debug events with rich metadata.

**Zero-Overhead Debugging**: Debug events are processed asynchronously and don't impact program performance.

**Production Ready**: Observers integrate seamlessly with logging frameworks, metrics systems, and distributed tracing tools.

**Error Context**: Detailed error contexts help diagnose failures with complete execution history.

**Performance Monitoring**: Built-in timing and metrics collection enable performance optimization and SLA monitoring.

**Distributed Tracing**: Program and fiber IDs enable correlation across service boundaries in distributed systems.

**Customizable**: The observer interface allows integration with existing observability infrastructure.

## What's Next

Chapter 12 explores Eru's performance characteristics, benchmarking techniques, and optimization strategies that help you build high-performance systems while maintaining correctness and observability.