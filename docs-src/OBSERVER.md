# Observability in Eru

Eru provides comprehensive, structured observability for monitoring program execution, debugging, and performance analysis. The observer system captures program lifecycle events, fiber operations, and custom debug steps with minimal overhead.

## Core Concepts

### EruObserver Interface

```scala
trait EruObserver {
  def onEvent(event: EruEvent): Unit
}
```

Observers receive structured events throughout program execution and provide hooks for logging, metrics, tracing, and debugging.

### Event Categories

**Program Lifecycle Events**
- Track overall program execution from start to completion
- Provide correlation IDs for distributed tracing
- Capture final outcomes (success, failure, or defect)

**Fiber Lifecycle Events**  
- Monitor concurrent fiber creation and completion
- Track fiber interruption and cleanup
- Enable debugging of concurrent execution patterns

**Debug Events**
- Custom step markers for detailed program tracing
- Performance profiling integration points
- User-defined observability hooks

## Event Types

### Program Events

```scala
case class ProgramStart(scopeId: ScopeId)
case class ProgramEnd(scopeId: ScopeId, outcome: Outcome)
case class Step(scopeId: ScopeId, label: String)
```

**Outcomes:**
```scala
enum Outcome {
  case Success                              // Program completed successfully
  case TypedFailure(error: Any)            // Program failed with typed error
  case Defect(throwable: Throwable)        // Program crashed with exception
}
```

### Fiber Events

```scala
case class FiberStarted(fiberId: FiberId)
case class FiberCompleted(fiberId: FiberId, exit: Exit[Any, Any])  
case class FiberInterrupted(fiberId: FiberId, cause: InterruptCause)
```

### Scope and Fiber IDs

```scala
opaque type ScopeId = Long      // Unique program execution identifier
opaque type FiberId = Long      // Unique fiber identifier
```

IDs are generated using atomic counters, ensuring uniqueness within the process lifetime and enabling correlation across events.

## Basic Usage

### Built-in Observers

```scala
import net.ghoula.eru.prelude.*

// No-op observer (minimal overhead)
val noop: EruObserver = EruObserver.noop

// Console observer (development/debugging)  
val console: EruObserver = EruObserver.console

val result = myProgram.unsafeRunSyncWith(console)
```

### Custom Observer

```scala
class LoggingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = event match {
    case EruEvent.ProgramStart(scopeId) =>
      logger.info(s"Program started: $scopeId")
      
    case EruEvent.ProgramEnd(scopeId, outcome) =>
      outcome match {
        case Outcome.Success =>
          logger.info(s"Program $scopeId completed successfully")
        case Outcome.TypedFailure(error) =>
          logger.warn(s"Program $scopeId failed: $error")
        case Outcome.Defect(throwable) =>
          logger.error(s"Program $scopeId crashed", throwable)
      }
      
    case EruEvent.FiberStarted(fiberId) =>
      logger.debug(s"Fiber $fiberId started")
      
    case EruEvent.FiberCompleted(fiberId, exit) =>
      logger.debug(s"Fiber $fiberId completed: $exit")
      
    case EruEvent.Step(scopeId, label) =>
      logger.trace(s"Step '$label' in scope $scopeId")
      
    case other =>
      logger.debug(s"Event: $other")
  }
}
```

## Advanced Observability Patterns

### Metrics Collection

```scala
class MetricsObserver extends EruObserver {
  private val programCounter = new AtomicLong(0)
  private val fiberCounter = new AtomicLong(0)
  private val errorCounter = new AtomicLong(0)
  
  def onEvent(event: EruEvent): Unit = event match {
    case EruEvent.ProgramStart(_) =>
      programCounter.incrementAndGet()
      
    case EruEvent.ProgramEnd(_, outcome) =>
      outcome match {
        case Outcome.Success => 
          metrics.incrementCounter("eru.program.success")
        case Outcome.TypedFailure(_) => 
          metrics.incrementCounter("eru.program.failure")
          errorCounter.incrementAndGet()
        case Outcome.Defect(_) => 
          metrics.incrementCounter("eru.program.defect")
          errorCounter.incrementAndGet()
      }
      
    case EruEvent.FiberStarted(_) =>
      fiberCounter.incrementAndGet()
      metrics.incrementGauge("eru.fibers.active", 1)
      
    case EruEvent.FiberCompleted(_, _) =>
      metrics.incrementGauge("eru.fibers.active", -1)
      
    case _ => ()
  }
  
  def getStats: (Long, Long, Long) = 
    (programCounter.get(), fiberCounter.get(), errorCounter.get())
}
```

### Performance Profiling

```scala
class ProfilingObserver extends EruObserver {
  private val timings = new ConcurrentHashMap[ScopeId, Long]()
  private val fiberTimings = new ConcurrentHashMap[FiberId, Long]()
  
  def onEvent(event: EruEvent): Unit = event match {
    case EruEvent.ProgramStart(scopeId) =>
      timings.put(scopeId, System.nanoTime())
      
    case EruEvent.ProgramEnd(scopeId, _) =>
      val startTime = timings.remove(scopeId)
      if (startTime != null) {
        val duration = (System.nanoTime() - startTime) / 1_000_000L
        metrics.recordTimer("eru.program.duration", duration)
      }
      
    case EruEvent.FiberStarted(fiberId) =>
      fiberTimings.put(fiberId, System.nanoTime())
      
    case EruEvent.FiberCompleted(fiberId, _) =>
      val startTime = fiberTimings.remove(fiberId)
      if (startTime != null) {
        val duration = (System.nanoTime() - startTime) / 1_000_000L  
        metrics.recordTimer("eru.fiber.duration", duration)
      }
      
    case _ => ()
  }
}
```

### Distributed Tracing Integration

```scala
class TracingObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = event match {
    case EruEvent.ProgramStart(scopeId) =>
      val span = tracer.nextSpan()
        .name("eru-program")  
        .tag("scope.id", scopeId.toString)
        .start()
      spanRegistry.put(scopeId, span)
      
    case EruEvent.ProgramEnd(scopeId, outcome) =>
      spanRegistry.remove(scopeId) match {
        case Some(span) =>
          outcome match {
            case Outcome.Success => 
              span.tag("outcome", "success").end()
            case Outcome.TypedFailure(error) => 
              span.tag("outcome", "failure")
                  .tag("error.type", error.getClass.getSimpleName)
                  .end()
            case Outcome.Defect(throwable) =>
              span.tag("outcome", "defect")
                  .tag("error.message", throwable.getMessage)
                  .end()
          }
        case None => ()
      }
      
    case EruEvent.FiberStarted(fiberId) =>
      val span = tracer.nextSpan()
        .name("eru-fiber")
        .tag("fiber.id", fiberId.toString)
        .start()
      fiberSpanRegistry.put(fiberId, span)
      
    case EruEvent.FiberCompleted(fiberId, exit) =>
      fiberSpanRegistry.remove(fiberId).foreach(_.end())
      
    case _ => ()
  }
}
```

## Debug Step Annotations

Add custom observation points in your programs:

```scala
import net.ghoula.eru.prelude.*

val program = for {
  _ <- Eru.succeed(()).debug("Starting computation")
  data <- fetchData().debug("Data fetched")
  processed <- processData(data).debug("Data processed") 
  result <- saveResult(processed).debug("Result saved")
} yield result

// With observer, emits Step events for each debug point
val result = program.unsafeRunSyncWith(observer)
```

## Concurrent Program Observability

Monitor fiber lifecycle in concurrent programs:

```scala
val concurrentProgram = for {
  fiber1 <- longComputation.forkWithObserver(observer)
  fiber2 <- anotherComputation.forkWithObserver(observer) 
  result1 <- fiber1.await
  result2 <- fiber2.await
} yield (result1, result2)

// Observer receives:
// FiberStarted(fiber1.id)
// FiberStarted(fiber2.id)  
// FiberCompleted(fiber1.id, exit1)
// FiberCompleted(fiber2.id, exit2)
```

## Composite Observers

Combine multiple observers for different purposes:

```scala
class CompositeObserver(observers: EruObserver*) extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    observers.foreach { observer =>
      try {
        observer.onEvent(event)
      } catch {
        case NonFatal(ex) =>
          // Never let observer exceptions escape
          logger.error("Observer failed", ex)
      }
    }
  }
}

val combined = new CompositeObserver(
  new LoggingObserver,
  new MetricsObserver,
  new TracingObserver
)

val result = myProgram.unsafeRunSyncWith(combined)
```

## Error Handling in Observers

**Critical Rule:** Observer exceptions must never escape `onEvent` or they will crash the program.

```scala
class SafeObserver extends EruObserver {
  def onEvent(event: EruEvent): Unit = {
    try {
      processEvent(event)  
    } catch {
      case NonFatal(ex) =>
        // Log error but don't propagate
        System.err.println(s"Observer error: $ex")
    }
  }
  
  private def processEvent(event: EruEvent): Unit = {
    // Your observer logic here
  }
}
```

## Best Practices

1. **Exception Safety**: Always catch and handle exceptions in observer code
2. **Performance**: Keep observer logic fast to avoid impacting program execution  
3. **Resource Management**: Clean up observer resources (close files, connections, etc.)
4. **Correlation**: Use ScopeId and FiberId to correlate related events
5. **Structured Logging**: Emit structured data that's easy to query and analyze
6. **Graceful Degradation**: Design observers to handle missing or malformed events

## Cross-Platform Behavior

### JVM Platform
- Full fiber lifecycle events for true concurrent execution
- High-precision timing information available  
- Rich integration with JVM monitoring tools
- Support for distributed tracing frameworks

### Scala Native Platform
- Same event structure with deterministic ordering
- Simplified fiber events due to sequential execution
- Lower overhead due to no concurrent event processing
- Ideal for performance-sensitive applications

The observer system provides the same rich observability experience across both platforms while adapting to platform-specific execution characteristics.