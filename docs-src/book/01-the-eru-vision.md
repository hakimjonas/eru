# Chapter 1: Why effect systems matter

If you're reading this, you probably already write solid Scala code. You understand functional programming, use immutable data structures, and think carefully about program design. So why would you need an effect system like Eru? What does it add that plain Scala does not already provide?

Effect systems offer one principled answer to a recurring problem in functional programming: managing failure, I/O, concurrency, and resource handling while keeping code structured and composable. They are a mature approach, used in production where effects must be coordinated precisely.

## The hidden complexity problem

Non-trivial programs have effects. Consider this function:

```scala
def processOrder(order: Order): OrderResult = {
  val validated = validateOrder(order)
  val payment = processPayment(validated.total)
  val shipping = scheduleShipping(validated.items)
  OrderResult(payment.id, shipping.trackingNumber)
}
```

This looks clean, but:

- `validateOrder` might fail if required fields are missing
- `processPayment` could fail due to insufficient funds or network issues
- `scheduleShipping` might fail if items are out of stock
- Any of these could throw exceptions, block on I/O, or have timing dependencies
- The function signature gives no hint about any of it

When this function fails in production, debugging means reconstructing hidden control flow, exception propagation, and the implicit ordering dependencies between the three operations.

## What Scala gives you out of the box

Scala's standard tools already address some of this:

```scala
def processOrderSafe(order: Order): Either[String, OrderResult] = {
  for {
    validated <- validateOrder(order)
    payment   <- processPayment(validated.total)
    shipping  <- scheduleShipping(validated.items)
  } yield OrderResult(payment.id, shipping.trackingNumber)
}
```

Failure is now explicit via `Either`, and the for-comprehension shows the sequential dependencies. What is still missing:

1. Failure, I/O, asynchronous work, and resource management are conflated into one channel
2. There is no composition story: how do you combine this with retry logic, timeouts, concurrency?
3. Each `flatMap` allocates; nothing fuses
4. Error values are strings, awkward to match on per error kind

## What effect systems add

Eru makes computational effects explicit and composable:

```scala
def processOrder(order: Order): Eru[OrderError, OrderResult] =
  for {
    validated <- validateOrder(order)                    // failure
    payment   <- processPayment(validated.total)         // failure + I/O
    shipping  <- scheduleShipping(validated.items)       // failure + I/O
  } yield OrderResult(payment.id, shipping.trackingNumber)
```

The type `Eru[OrderError, OrderResult]` states:

- This computation might fail with an `OrderError`
- It produces an `OrderResult` on success
- Side effects are suspended until the program runs
- The value is a pure, immutable description

The rest is what you can do with that description:

```scala
import net.ghoula.eru.prelude.*
import net.ghoula.eru.EruRuntime
import java.time.Duration

// Add timeout and retries
val resilientOrder: Eru[OrderError | Throwable, OrderResult] =
  processOrder(order)
    .timeout(Duration.ofSeconds(30))
    .retry(EruRuntime.Policy.NoDelay(3))

// Handle specific errors
val withFallback: Eru[OrderError | Throwable, OrderResult] =
  resilientOrder.recoverWith {
    case OrderError.PaymentFailed(_) => useStoredPaymentMethod(order)
    case _: OrderError.ShippingUnavailable => scheduleForLater(order)
  }

// Run many orders in parallel
val batchResults: Eru[OrderError | Throwable, List[OrderResult]] =
  parTraverse(orders)(processOrder)
```

Each composition is still pure: it builds a more elaborate program description.

## Why Scala suits effect systems

Effect systems fit languages with strong type systems and functional programming support.

### Type system power

Scala's type system encodes effect constraints at compile time:

```scala
// This function only reads configuration
def loadConfig(): Eru[ConfigError, AppConfig]

// This one reads AND writes to a database
def updateUser(user: User): Eru[DatabaseError, User]

// A caller of loadConfig cannot accidentally call updateUser
// without its DatabaseError appearing in the caller's type
```

### GADT support with enums

Scala 3 enums give the effect type an efficient representation. A simplified view of Eru's core:

```scala
enum Eru[+E, +A]:
  case Succeed[A](value: A) extends Eru[Nothing, A]
  case Fail[E](error: E) extends Eru[E, Nothing]
  case Effect[A](thunk: () => Either[Throwable, A]) extends Eru[Throwable, A]
  case Chain[E, A, B](source: Eru[E, A], cont: A => Eru[E, B]) extends Eru[E, B]
  case Zip[E1, E2, A, B](left: Eru[E1, A], right: Eru[E2, B]) extends Eru[E1 | E2, (A, B)]
```

The real representation differs in details (the interpreter keeps continuation chains right-associated for stack safety), but the shape is this: a program is a tree of typed nodes.

### For-comprehension syntax

For-comprehensions make sequential composition read naturally:

```scala
// Reads like imperative code, but is purely functional
val pipeline = for {
  config <- loadConfiguration()
  db     <- connectDatabase(config.dbUrl)
  user   <- db.findUser(userId)
  _      <- db.updateLastSeen(user.id, Instant.now())
} yield user
```

### Pattern matching on results

Structured error types match like any other enum:

```scala
import net.ghoula.eru.prelude.*

processOrder(order).attempt.unsafeRunSync() match {
  case Result.Success(result) =>
    println(s"Order processed: ${result.id}")
  case Result.Failure(OrderError.PaymentFailed(reason)) =>
    println(s"Payment failed: $reason")
  case Result.Failure(OrderError.InvalidOrder(field)) =>
    println(s"Invalid field: $field")
}
```

## Mental overhead: is it worth it?

The costs:

- Learning curve: effect systems require understanding `flatMap`, `bracket`, and resource safety
- Conceptual overhead: you think in program descriptions rather than direct execution
- Syntax weight: `Eru[Error, Value]` is more verbose than plain `Value`

The benefits:

- Explicit contracts: function signatures state which effects occur
- Fearless refactoring: the type system catches effect violations at compile time
- Composable patterns: retry, timeout, concurrency, and resource management are combinators
- Debuggable programs: pure descriptions are easier to test and reason about
- Optimized composition: adjacent `map` calls fuse at construction time

The overhead pays for itself in non-trivial applications, especially those involving concurrency, error handling, and resource management.

## Effect systems are about control

Effect systems control when, where, and how effects occur.

Without an effect system:

- Effects happen immediately when functions are called
- Error handling is ad-hoc and often forgotten
- Concurrency requires manual thread management
- Resource cleanup is easy to get wrong
- Testing effectful code requires mocking

With an effect system:

- Effects are descriptions that execute only when you choose
- Error handling is built into the composition model
- Concurrency is managed by the runtime
- Resource management follows patterns that prevent leaks
- Testing is building and inspecting program descriptions

## Beyond error handling: the full effect spectrum

Many developers first encounter effect systems through error handling (via `Either` or `Try`), but effects cover more than failure.

### Resource management effects

```scala
// File cleanup runs even on failure
val safeFileProcessing =
  Eru.bracket(openFile("data.txt"))(closeFile) { file =>
    processFileContents(file)
  }
```

### Concurrency effects

```scala
// Run operations in parallel, collect results safely
val parallelWork = for {
  results <- parTraverse(List(task1, task2, task3))(identity)
  summary <- aggregateResults(results)
} yield summary
```

### Environment effects

Eru has no magic environment reader: configuration and connections are ordinary values passed through the program:

```scala
val businessLogic: Eru[ConfigError | DatabaseError, Result] = for {
  config <- loadConfig()
  db     <- connectDatabase(config.url)
  result <- processWith(db, config.settings)
} yield result
```

The types make each dependency visible at every call site.

### Observability effects

```scala
// Debug steps appear as structured events, not log lines
val tracedOperation: Eru[String, User] = for {
  _    <- Eru.succeed(()) 
  user <- createUser(userData).debug("user registration")
} yield user
```

### Time and scheduling effects

```scala
import net.ghoula.eru.EruRuntime
import java.time.Duration

// Timeout and retry as combinators
val timedOperation = userRegistration
  .timeout(Duration.ofSeconds(5))
  .retry(EruRuntime.Policy.NoDelay(3))
```

These effects compose. A single function can involve failure handling, resource management, concurrency, observability, and timing, all expressed in the types.

## A practical comparison

User registration without an effect system:

```scala
// Traditional approach - effects hidden everywhere
def registerUser(userData: UserData): UserResult = {
  val logger = LoggerFactory.getLogger(getClass)

  try {
    logger.info("Starting user registration")

    val db = DatabasePool.getConnection() // resource effect
    try {
      val validated = validateUser(userData) // can fail

      if (userExists(db, validated.email)) { // I/O effect
        throw new UserExistsException()
      }

      val hashedPassword = hashPassword(validated.password) // CPU effect
      val user = db.insertUser(validated.copy(password = hashedPassword)) // I/O effect

      // Send email in background thread - concurrency effect
      CompletableFuture.runAsync(() => {
        try {
          sendWelcomeEmail(user.email) // I/O effect
        } catch {
          case ex: Exception => logger.error("Failed to send email", ex)
        }
      })

      logger.info(s"User ${user.id} registered successfully")
      UserResult.Success(user)

    } finally {
      db.close() // resource cleanup
    }
  } catch {
    case ex: UserExistsException =>
      logger.warn(s"Registration failed: user exists")
      UserResult.UserExists
    case ex: ValidationException =>
      logger.warn(s"Registration failed: ${ex.getMessage}")
      UserResult.ValidationFailed(ex.errors)
    case ex: Exception =>
      logger.error("Registration failed unexpectedly", ex)
      UserResult.SystemError
  }
}
```

With an effect system:

```scala
def registerUser(userData: UserData): Eru[RegistrationError, User] =
  for {
    _         <- Eru.succeed(()).debug("Starting user registration")
    validated <- validateUser(userData)
    _         <- checkUserNotExists(validated.email)
    hashed    <- hashPassword(validated.password)
    user      <- insertUser(validated.copy(password = hashed))
    // forkDaemon: fire-and-forget without root tracking or scope joining.
    // Inside a structured scope the daemon is still interrupted when the scope
    // unwinds; at the root it lives until the JVM exits.
    _         <- sendWelcomeEmail(user.email).forkDaemon
    _         <- Eru.succeed(()).debug(s"User ${user.id} registered")
  } yield user
```

The effect system version:

- Makes effects explicit in types
- Handles resource cleanup automatically
- Composes error handling naturally
- Makes concurrent operations safe by default
- Separates business logic from effect management
- Is easier to test and reason about

## Virtual thread-native architecture

Eru builds directly on Java virtual threads (introduced in JDK 21) rather than implementing its own fiber runtime.

Why this matters:

- Platform integration: fibers are JVM entities, so JVM tooling (thread dumps, profilers) sees them
- Ambient context: virtual threads do NOT inherit thread-locals, so Eru re-establishes the parent's scope, timer, and trace context on each forked fiber explicitly (the same discipline Swift task-locals use)
- Mature foundation: the JVM's virtual thread implementation is the scheduler
- Reduced complexity: no custom scheduler to maintain or debug

One note on Java's structured concurrency API: it was a preview feature from JDK 21 through JDK 24 and was finalized in JDK 25. Eru does not depend on it either way — Eru implements its own scoping on top of virtual threads, so it works consistently across JDK versions.

Eru's fibers are Java virtual threads: a forked `Eru` computation runs on its own virtual thread.

## The four pillars of Eru

Eru's design centers on four principles:

### Pillar 1: Foundational correctness

Correctness comes first.

Pure program representation: `Eru[E, A]` is an immutable, total description of a program. Side effects are suspended inside the `Eru` context to preserve referential transparency.

```scala mdoc
import net.ghoula.eru.prelude.*
import net.ghoula.eru.prelude.given

// This creates a description - no side effects happen yet
val program: Eru[Nothing, String] = Eru.succeed("Hello, Eru!")

// The program is just data until we run it
val result: String = program.unsafeRunSync()
```

Type-directed design: the library uses Scala 3's opaque types, GADTs, and union types to reject categories of errors at compile time rather than deferring them to runtime.

Verified lawfulness: adherence to the laws of the core type classes is checked with property-based tests.

### Pillar 2: Pragmatic ergonomics

Useful tools should be easy to use.

Discoverable operations: common patterns such as retries, timeouts, and resource management are methods on the `Eru` type (or its extensions).

```scala
// Common patterns are built into the API:
val riskyOperation: Eru[String, Int] = Eru.succeed(42)
val guarded = riskyOperation
  .timeout(Duration.ofSeconds(10))
  .retry(EruRuntime.Policy.NoDelay(2))
```

Clarity over cleverness: the API picks straightforward, readable solutions over sophisticated abstractions.

### Pillar 3: Guided correctness

The API steers developers toward correct solutions by making the safe pattern the convenient one.

Resource safety by design: `bracket` and `ensure` encode acquisition, use, and release, so cleanup is part of the pattern rather than an afterthought.

```scala mdoc
import java.nio.file.*

// Resource management that guarantees cleanup
val safeFileRead: Eru[Throwable, String] =
  Eru.effect {
    Files.newBufferedReader(Paths.get("data.txt"))
  }.bracket { reader =>
    Eru.effect(reader.close()) // always called, even on errors
  } { reader =>
    Eru.effect(reader.readLine()) // safe usage
  }
```

Structured concurrency: high-level concurrency primitives take over scheduling and isolation, making correct concurrent code the default.

Explicit integration boundaries: blocking or legacy code goes through `Eru.blocking(...)`, keeping the application responsive by default.

### Pillar 4: Runtime observability

Running programs are observable.

Structured error information: failures carry typed context, not opaque messages.

Low-overhead instrumentation: optional event emission provides tracing and profiling without cost when unused.

Unified observation interface: `EruObserver` is the single integration point for logging, metrics, and tracing.

## Bridging theory and practice

These principles are a commitment to functional programming that is both sound and practical. The goal is not to choose between correctness and productivity; it is to have both.

Many effect-system libraries exist, each with trade-offs. Eru's focus is making the benefits of effect systems accessible to working developers without discarding the foundations that make them correct.

## Where effect systems excel

Effect systems pay off in domains where coordinating effects is the core of the problem.

### Data engineering and ETL pipelines

Data processing involves resource management, failure recovery, and complex dependencies:

```scala
import net.ghoula.eru.prelude.*
import net.ghoula.eru.EruRuntime
import java.time.Duration

def processDataPipeline(inputPath: String): Eru[PipelineError | Throwable, DatasetMetrics] =
  for {
    // Resource management - the connection closes automatically
    source  <- Eru.bracket(openDataSource(inputPath))(_.close) { ds =>
                 readDataset(ds)
               }
    // Structured error handling with recovery
    cleaned <- cleanData(source)
                 .retry(EruRuntime.Policy.NoDelay(3))
                 .timeout(Duration.ofMinutes(10))
    // Parallel processing with coordination
    results <- parTraverse(List(validateSchema _, enrichData _, computeStats _))(_(cleaned))
    // Commit on success, roll back on failure
    _       <- Eru.effect(commitTx(writeResults(results)))
                 .recoverWith { case error =>
                   Eru.effect(rollbackTx(error)).flatMap(_ => Eru.fail(error))
                 }
  } yield DatasetMetrics(results.size, source.recordCount)
```

Compare this to manually managing connections, retries, parallel coordination, and transactions in vanilla Scala.

### Microservice integration

A service with several external dependencies:

```scala
def processOrder(orderId: OrderId): Eru[ServiceError | Throwable, OrderConfirmation] =
  for {
    // Concurrent external calls with timeout protection
    (customer, pricing) <- customerService.getCustomer(orderId.customerId)
                             .zipPar(pricingService.calculateTotal(orderId.items))
                             .timeout(Duration.ofSeconds(5))

    // Circuit breaker for payment processing
    payment  <- paymentService.charge(customer.paymentMethod, pricing.total)
                  .withCircuitBreaker(paymentCircuitBreaker)

    // Compensating actions on failure
    shipping <- shippingService.schedule(orderId).recoverWith {
                  case error =>
                    paymentService.refund(payment.id)
                      .flatMap(_ => inventoryService.release(orderId.items))
                      .flatMap(_ => Eru.fail(error))
                }
  } yield OrderConfirmation(payment.id, shipping.trackingId)
```

Timeout, circuit breaker, and compensating actions, coordinated through the type system.

### Apache Spark integration

Spark jobs with resource management:

```scala
def runSparkAnalysis(config: AnalysisConfig): Eru[AnalysisError | Throwable, AnalysisResult] =
  Eru.bracket(Eru.effect(createSparkSession(config)))(_.stop()) { spark =>
    for {
      // Dataset loading with validation
      rawData   <- loadDataset(spark, config.inputPath)
      _         <- Eru.effect(rawData.count()).flatMap { n =>
                     if (n > 0) Eru.succeed(())
                     else Eru.fail(AnalysisError.EmptyDataset)
                   }

      // Multi-stage transformations
      processed <- runFeatureEngineering(rawData)

      // Resource-safe model training
      model     <- Eru.bracket(Eru.effect(allocateGPU()))(gpu => Eru.effect(gpu.deallocate())) {
                     gpu => trainOnGPU(processed, gpu)
                   }

      // Result persistence
      _         <- persistModel(model, config.outputPath)
    } yield AnalysisResult(model.metrics, processed.count())
  }
```

### Financial trading systems

```scala
def executeTrade(trade: TradeRequest): Eru[TradingError | Throwable, TradeExecution] =
  for {
    // Market data with staleness protection
    quote      <- marketDataService.getQuote(trade.symbol)
                    .timeout(Duration.ofMillis(100))
    _          <- Eru
                    .effect(quote.timestamp.isAfter(Instant.now().minusSeconds(1)))
                    .flatMap { fresh =>
                      if (fresh) Eru.succeed(())
                      else Eru.fail(TradingError.StaleQuote)
                    }

    // Risk checks with audit trail
    _          <- riskEngine.validateTrade(trade, quote)
                    .tapError(error => auditService.logRiskViolation(trade, error))

    // Atomic execution with position updates
    execution  <- Eru.bracket(Eru.effect(lockPosition(trade.account)))(unlockPosition) { _ =>
                    for {
                      exec <- exchangeService.submitOrder(trade)
                      _    <- positionService.updatePosition(trade.account, exec)
                      _    <- settlementService.scheduleSettlement(exec)
                    } yield exec
                  }
  } yield execution
```

### Stream processing with queues

Eru has no built-in streaming combinator library. Stream-like processing composes from the concurrency primitives in Chapter 10:

```scala
def processEventStream(source: EventSource): Eru[StreamError | Throwable, Int] =
  for {
    queue  <- Eru.queue[RawEvent](capacity = 100)
    _      <- produce(source, queue).fork
    total  <- consumeAndAggregate(queue)
  } yield total
```

`produce` puts events into the bounded queue (backpressure: `put` suspends when the queue is full), and `consumeAndAggregate` drains it. The producer fork runs at the root, so it is tracked by the runtime and released by `cleanup()`/`shutdownRootFibers` — or use `forkDaemon` for fire-and-forget. Inside a structured scope the producer would instead be interrupted when the scope unwinds. Chapter 10 covers `Queue` and the fork/await model behind this.

## Common patterns across domains

These examples share recurring patterns where effect systems provide clear value:

1. Resource bracketing: cleanup runs even during failures
2. Structured concurrency: parallel operations are coordinated safely
3. Timeout and retry logic: unreliable external systems get bounded
4. Transactional operations: atomic success or failure across multiple steps
5. Error recovery: fallback and compensation logic
6. Observability: structured events for debugging

## What's next

Chapter 2 covers the practical basics of using Eru: working code examples that show how these concepts translate into daily development.
