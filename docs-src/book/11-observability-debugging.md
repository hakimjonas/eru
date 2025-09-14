# Chapter 11: Observability & Debugging

*"A system you cannot observe is a system you cannot understand, debug, or optimize."*

Observability is one of Eru's four foundational pillars. This chapter explores Eru's comprehensive observability system through `EruObserver`, covering structured event monitoring, distributed tracing, performance analysis, and debugging techniques that make concurrent programs transparent and maintainable.

## The Observer System

Eru provides structured observability through the `EruObserver` interface, which captures program lifecycle events with minimal overhead:

```scala mdoc
import net.ghoula.eru.prelude.*
import net.ghoula.eru.{EruObserver, EruEvent, EruRuntime}

// Define a simple observer for demonstration
class LoggingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    event match {
      case EruEvent.ProgramStart(programId, timestamp) =>
        println(s"🚀 Program $programId started at $timestamp")

      case EruEvent.ProgramEnd(programId, timestamp, exit) =>
        println(s"🏁 Program $programId ended at $timestamp: $exit")

      case EruEvent.FiberStart(fiberId, programId, timestamp) =>
        println(s"🧵 Fiber $fiberId started in program $programId at $timestamp")

      case EruEvent.FiberEnd(fiberId, programId, timestamp, exit) =>
        println(s"🔚 Fiber $fiberId ended in program $programId at $timestamp: $exit")

      case EruEvent.Debug(fiberId, programId, timestamp, step, metadata) =>
        println(s"🐛 Debug [$fiberId]: $step - $metadata")
    }
  }
}

// Create runtime with observer
val observer = LoggingObserver()
given runtime: EruRuntime = EruRuntime.create(observer = Some(observer))

// Simple program to demonstrate observation
def observableProgram(): Eru[String, String] = {
  for {
    _      <- Eru.debug("Starting computation")
    result <- Eru.effect("Hello, Observable World!").mapError(_.getMessage)
    _      <- Eru.debug("Computation completed", Map("result" -> result))
  } yield result
}

// Run and observe
val observedResult = observableProgram().unsafeRunSync()
println(s"Final result: $observedResult")
```

## Event Categories

The observer system captures different types of events throughout program execution:

### Program Lifecycle Events

```scala mdoc
// Program lifecycle tracking
def lifecycleDemo(): Eru[String, String] = {
  for {
    _ <- Eru.debug("Phase 1: Initialization")

    // Simulate some initialization work
    config <- Eru.effect("Configuration loaded").mapError(_.getMessage)
    _ <- Eru.debug("Configuration phase complete", Map("config" -> config))

    _ <- Eru.debug("Phase 2: Processing")

    // Main processing
    result <- Eru.effect {
      "Processing completed successfully"
    }.mapError(_.getMessage)

    _ <- Eru.debug("Phase 3: Cleanup")

    // Cleanup
    _ <- Eru.effect(println("Resources cleaned up")).mapError(_.getMessage)
    _ <- Eru.debug("All phases complete")

  } yield result
}

val lifecycleResult = lifecycleDemo().unsafeRunSync()
println(s"Lifecycle demo result: $lifecycleResult")
```

### Fiber Lifecycle Events

```scala mdoc
// Monitor concurrent fiber execution
def concurrentObservation(): Eru[String, List[String]] = {
  for {
    _ <- Eru.debug("Starting concurrent operations")

    // Fork multiple fibers with observation
    fiber1 <- (for {
      _ <- Eru.debug("Worker 1 starting")
      result <- Eru.effect("Worker 1 completed").mapError(_.getMessage)
      _ <- Eru.debug("Worker 1 finished", Map("result" -> result))
    } yield result).fork

    fiber2 <- (for {
      _ <- Eru.debug("Worker 2 starting")
      result <- Eru.effect("Worker 2 completed").mapError(_.getMessage)
      _ <- Eru.debug("Worker 2 finished", Map("result" -> result))
    } yield result).fork

    _ <- Eru.debug("All workers forked, awaiting completion")

    // Collect results
    exit1 <- fiber1.await
    exit2 <- fiber2.await

    results <- Eru.succeed {
      List(exit1, exit2).collect {
        case net.ghoula.eru.Exit.Success(value) => value
      }
    }

    _ <- Eru.debug("All concurrent work completed", Map("resultCount" -> results.size.toString))

  } yield results
}

val concurrentResults = concurrentObservation().unsafeRunSync()
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
    _ <- Eru.debug("Order processing started", Map(
      "orderId" -> order.id,
      "customerId" -> order.customerId,
      "amount" -> order.amount.toString
    ))

    // Step 1: Customer validation
    _ <- Eru.debug("Validating customer")
    customer <- validateCustomer(order.customerId)
    _ <- Eru.debug("Customer validated", Map(
      "customerName" -> customer.name,
      "creditLimit" -> customer.creditLimit.toString
    ))

    // Step 2: Credit check
    _ <- Eru.debug("Performing credit check")
    _ <- checkCredit(customer, order.amount)
    _ <- Eru.debug("Credit check passed")

    // Step 3: Process payment
    _ <- Eru.debug("Processing payment")
    paymentResult <- processPayment(order)
    _ <- Eru.debug("Payment processed", Map("paymentResult" -> paymentResult))

    // Step 4: Update inventory
    _ <- Eru.debug("Updating inventory")
    _ <- updateInventory(order)
    _ <- Eru.debug("Inventory updated")

    finalResult <- Eru.succeed(s"Order ${order.id} processed successfully")
    _ <- Eru.debug("Order processing completed", Map("result" -> finalResult))

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
val orderResult = processOrder(testOrder).attempt.unsafeRunSync()

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
    event match {
      case EruEvent.ProgramStart(programId, timestamp) =>
        startTimes(s"program-$programId") = timestamp

      case EruEvent.ProgramEnd(programId, timestamp, _) =>
        startTimes.get(s"program-$programId").foreach { startTime =>
          val duration = timestamp - startTime
          metrics(s"program-$programId-duration") = duration
          println(s"📊 Program $programId executed in ${duration}ms")
        }

      case EruEvent.FiberStart(fiberId, programId, timestamp) =>
        startTimes(s"fiber-$fiberId") = timestamp

      case EruEvent.FiberEnd(fiberId, programId, timestamp, _) =>
        startTimes.get(s"fiber-$fiberId").foreach { startTime =>
          val duration = timestamp - startTime
          metrics(s"fiber-$fiberId-duration") = duration
          println(s"📊 Fiber $fiberId executed in ${duration}ms")
        }

      case EruEvent.Debug(_, _, _, step, metadata) =>
        if (step.contains("performance")) {
          println(s"🔍 Performance marker: $step - $metadata")
        }

      case _ => // Ignore other events
    }
  }

  def getMetrics: Map[String, Long] = metrics.toMap
}

// Create runtime with performance observer
val perfObserver = PerformanceObserver()
given perfRuntime: EruRuntime = EruRuntime.create(observer = Some(perfObserver))

// Program with performance markers
def performanceTrackedProgram(): Eru[String, String] = {
  for {
    _ <- Eru.debug("performance: Starting heavy computation")

    // Simulate CPU-intensive work
    result <- Eru.effect {
      val computation = (1 to 10000).map(_ * 2).sum
      s"Heavy computation result: $computation"
    }.mapError(_.getMessage)

    _ <- Eru.debug("performance: Heavy computation completed", Map("result" -> result))

    _ <- Eru.debug("performance: Starting I/O simulation")

    // Simulate I/O work
    ioResult <- Eru.effect {
      Thread.sleep(5) // Brief I/O simulation
      "I/O operation completed"
    }.mapError(_.getMessage)

    _ <- Eru.debug("performance: I/O operation completed", Map("ioResult" -> ioResult))

  } yield s"$result | $ioResult"
}

val perfResult = performanceTrackedProgram().unsafeRunSync()
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
    event match {
      case EruEvent.ProgramStart(programId, timestamp) =>
        val traceBuffer = scala.collection.mutable.ListBuffer[String]()
        traceBuffer += s"TRACE [$timestamp] Program $programId started"
        traces(programId) = traceBuffer

      case EruEvent.ProgramEnd(programId, timestamp, exit) =>
        traces.get(programId).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp] Program $programId ended: $exit"
        }

      case EruEvent.FiberStart(fiberId, programId, timestamp) =>
        traces.get(programId).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   → Fiber $fiberId started"
        }

      case EruEvent.FiberEnd(fiberId, programId, timestamp, exit) =>
        traces.get(programId).foreach { traceBuffer =>
          traceBuffer += s"TRACE [$timestamp]   ← Fiber $fiberId ended: $exit"
        }

      case EruEvent.Debug(fiberId, programId, timestamp, step, metadata) =>
        traces.get(programId).foreach { traceBuffer =>
          val metaStr = if (metadata.nonEmpty) s" {${metadata.map { case (k, v) => s"$k=$v" }.mkString(", ")}}" else ""
          traceBuffer += s"TRACE [$timestamp]   • [$fiberId] $step$metaStr"
        }
    }
  }

  def getTrace(programId: String): List[String] = {
    traces.get(programId).map(_.toList).getOrElse(List.empty)
  }

  def printTrace(programId: String): Unit = {
    getTrace(programId).foreach(println)
  }
}

// Create runtime with tracing observer
val tracingObserver = TracingObserver()
given tracingRuntime: EruRuntime = EruRuntime.create(observer = Some(tracingObserver))

// Distributed service simulation
def distributedServiceCall(): Eru[String, String] = {
  for {
    _ <- Eru.debug("Incoming request received", Map("endpoint" -> "/api/users"))

    // Service call 1: User service
    userFiber <- (for {
      _ <- Eru.debug("Calling user service")
      user <- Eru.effect("User data retrieved").mapError(_.getMessage)
      _ <- Eru.debug("User service call completed", Map("user" -> user))
    } yield user).fork

    // Service call 2: Preferences service
    prefsFiber <- (for {
      _ <- Eru.debug("Calling preferences service")
      prefs <- Eru.effect("User preferences retrieved").mapError(_.getMessage)
      _ <- Eru.debug("Preferences service call completed", Map("prefs" -> prefs))
    } yield prefs).fork

    _ <- Eru.debug("All service calls initiated, awaiting responses")

    // Collect results
    userExit <- userFiber.await
    prefsExit <- prefsFiber.await

    response <- (userExit, prefsExit) match {
      case (net.ghoula.eru.Exit.Success(user), net.ghoula.eru.Exit.Success(prefs)) =>
        val combined = s"$user + $prefs"
        Eru.debug("Response prepared", Map("response" -> combined)) *>
        Eru.succeed(combined)
      case _ =>
        Eru.debug("Service calls failed") *>
        Eru.fail("Service call failure")
    }

    _ <- Eru.debug("Request processing completed")

  } yield response
}

// Execute with tracing
val tracedResult = distributedServiceCall().attempt.unsafeRunSync()

println(s"Distributed service result: $tracedResult")
println("\nDistributed Trace:")

// The program ID is generated internally - for demo, we'll assume it exists
// In practice, you'd capture this from the first event or use runtime APIs
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
      case EruEvent.ProgramStart(programId, _) =>
        executionSteps(programId) = scala.collection.mutable.ListBuffer[String]()

      case EruEvent.Debug(_, programId, _, step, metadata) =>
        executionSteps.get(programId).foreach { steps =>
          val metaStr = if (metadata.nonEmpty) s" [${metadata.map { case (k, v) => s"$k=$v" }.mkString(", ")}]" else ""
          steps += s"$step$metaStr"
        }

      case EruEvent.ProgramEnd(programId, _, exit) =>
        exit match {
          case net.ghoula.eru.Exit.Failure(_) | net.ghoula.eru.Exit.Die(_) =>
            executionSteps.get(programId).foreach { steps =>
              errorContexts(programId) = steps.toList
            }
          case _ => // Success cases don't need error context
        }

      case _ => // Ignore other events
    }
  }

  def getErrorContext(programId: String): List[String] = {
    errorContexts.get(programId).getOrElse(List.empty)
  }
}

// Create runtime with error context observer
val errorObserver = ErrorContextObserver()
given errorRuntime: EruRuntime = EruRuntime.create(observer = Some(errorObserver))

// Program that will fail with detailed context
def failingProgram(): Eru[String, String] = {
  for {
    _ <- Eru.debug("Starting data validation")

    data <- Eru.effect("input-data").mapError(_.getMessage)
    _ <- Eru.debug("Data loaded", Map("dataSize" -> data.length.toString))

    _ <- Eru.debug("Starting transformation phase")

    // This step will fail
    processed <- if (data.contains("invalid")) {
      Eru.debug("Invalid data detected") *>
      Eru.fail("Data validation failed: contains invalid content")
    } else {
      Eru.debug("Data processing step 1") *>
      Eru.effect(data.toUpperCase).mapError(_.getMessage)
    }

    _ <- Eru.debug("Transformation completed", Map("result" -> processed))

    finalResult <- Eru.succeed(s"Processed: $processed")
    _ <- Eru.debug("All processing completed")

  } yield finalResult
}

// Test with both success and failure cases
println("=== Testing Success Case ===")
val successResult = failingProgram().attempt.unsafeRunSync()
println(s"Success result: $successResult")

println("\n=== Testing Failure Case ===")
// Simulate failure by using invalid data
val failureProgram = for {
  _ <- Eru.debug("Starting data validation")
  data <- Eru.succeed("invalid-input-data")
  _ <- Eru.debug("Data loaded", Map("dataSize" -> data.length.toString))
  _ <- Eru.debug("Starting transformation phase")
  _ <- Eru.debug("Invalid data detected")
  result <- Eru.fail("Data validation failed: contains invalid content")
} yield result

val failureResult = failureProgram.attempt.unsafeRunSync()
println(s"Failure result: $failureResult")
```

## Production Observability Patterns

### Structured Logging Integration

```scala mdoc
// Integration with structured logging systems
class StructuredLoggingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    val logEntry = event match {
      case EruEvent.ProgramStart(programId, timestamp) =>
        Map(
          "event" -> "program.start",
          "programId" -> programId,
          "timestamp" -> timestamp.toString,
          "level" -> "INFO"
        )

      case EruEvent.ProgramEnd(programId, timestamp, exit) =>
        val (level, exitType) = exit match {
          case net.ghoula.eru.Exit.Success(_) => ("INFO", "success")
          case net.ghoula.eru.Exit.Failure(_) => ("WARN", "failure")
          case net.ghoula.eru.Exit.Die(_) => ("ERROR", "die")
          case net.ghoula.eru.Exit.Interrupt(_, _) => ("INFO", "interrupt")
        }
        Map(
          "event" -> "program.end",
          "programId" -> programId,
          "timestamp" -> timestamp.toString,
          "level" -> level,
          "exitType" -> exitType
        )

      case EruEvent.Debug(fiberId, programId, timestamp, step, metadata) =>
        Map(
          "event" -> "debug",
          "fiberId" -> fiberId,
          "programId" -> programId,
          "timestamp" -> timestamp.toString,
          "step" -> step,
          "level" -> "DEBUG"
        ) ++ metadata

      case _ =>
        Map("event" -> "unknown", "level" -> "DEBUG")
    }

    // In production, this would integrate with your logging framework
    println(s"LOG: ${logEntry.map { case (k, v) => s"$k=$v" }.mkString(" ")}")
  }
}

// Example usage with structured logging
val structuredObserver = StructuredLoggingObserver()
given structuredRuntime: EruRuntime = EruRuntime.create(observer = Some(structuredObserver))

def businessProcess(): Eru[String, String] = {
  for {
    _ <- Eru.debug("business.process.start", Map("version" -> "1.0"))
    result <- Eru.effect("Business logic completed").mapError(_.getMessage)
    _ <- Eru.debug("business.process.end", Map("resultType" -> "success"))
  } yield result
}

val structuredResult = businessProcess().unsafeRunSync()
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
    event match {
      case EruEvent.ProgramStart(programId, timestamp) =>
        incrementCounter("programs.started")
        startTimes(s"program-$programId") = timestamp

      case EruEvent.ProgramEnd(programId, timestamp, exit) =>
        incrementCounter("programs.completed")

        exit match {
          case net.ghoula.eru.Exit.Success(_) => incrementCounter("programs.success")
          case net.ghoula.eru.Exit.Failure(_) => incrementCounter("programs.failure")
          case net.ghoula.eru.Exit.Die(_) => incrementCounter("programs.error")
          case net.ghoula.eru.Exit.Interrupt(_, _) => incrementCounter("programs.interrupted")
        }

        startTimes.get(s"program-$programId").foreach { startTime =>
          recordTimer("program.duration", timestamp - startTime)
        }

      case EruEvent.FiberStart(fiberId, _, timestamp) =>
        incrementCounter("fibers.started")
        startTimes(s"fiber-$fiberId") = timestamp

      case EruEvent.FiberEnd(fiberId, _, timestamp, _) =>
        incrementCounter("fibers.completed")
        startTimes.get(s"fiber-$fiberId").foreach { startTime =>
          recordTimer("fiber.duration", timestamp - startTime)
        }

      case EruEvent.Debug(_, _, _, step, _) =>
        incrementCounter(s"debug.steps.$step")

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
given metricsRuntime: EruRuntime = EruRuntime.create(observer = Some(metricsObserver))

def metricsTestProgram(): Eru[String, String] = {
  for {
    _ <- Eru.debug("initialization")

    // Fork some concurrent work
    fiber1 <- (Eru.debug("worker.task") *> Eru.succeed("Work 1")).fork
    fiber2 <- (Eru.debug("worker.task") *> Eru.succeed("Work 2")).fork

    _ <- Eru.debug("coordination")

    result1 <- fiber1.await
    result2 <- fiber2.await

    _ <- Eru.debug("completion")

    finalResult <- (result1, result2) match {
      case (net.ghoula.eru.Exit.Success(r1), net.ghoula.eru.Exit.Success(r2)) =>
        Eru.succeed(s"Combined: $r1, $r2")
      case _ =>
        Eru.succeed("Partial results")
    }

  } yield finalResult
}

val metricsResult = metricsTestProgram().unsafeRunSync()
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

In Chapter 12, we'll explore Eru's performance characteristics, benchmarking techniques, and optimization strategies that help you build high-performance systems while maintaining correctness and observability.

---

*"Observability is not about perfect information—it's about having the right information at the right time to make informed decisions."*