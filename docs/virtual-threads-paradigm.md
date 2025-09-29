# The Virtual Thread Paradigm: A New Way of Thinking

## The Fundamental Shift

Virtual Threads change everything about JVM concurrency. Eru embraces this change fully, which is why some traditional "parallel" patterns might seem slower - **we're optimized for the new way, not the old way**.

## Old Thinking vs New Thinking

### ❌ Old: "I need to parallelize CPU work"
```scala
// Traditional: Explicit parallelism for CPU-bound work
val results = items.par.map { item =>
  expensiveComputation(item)  // Fighting for CPU cores
}
```

### ✅ New: "I write sequential code that naturally scales"
```scala
// Eru + VT: Sequential code that scales through natural yielding
val results = items.traverse { item =>
  for {
    // VT parks on cache lookup - carrier thread freed
    cached <- cache.get(item)
    result <- cached match {
      case Some(v) => Eru.succeed(v)
      case None =>
        for {
          // If computation has ANY I/O, VT parks
          computed <- computeWithLookups(item)
          // VT parks on cache write
          _ <- cache.set(item, computed)
        } yield computed
    }
  } yield result
}
```

**Key insight:** With Virtual Threads, you don't need to think about parallelism. You think about your business logic, and parallelism happens naturally at I/O boundaries.

## The Virtual Thread Advantage

### What Makes VTs Different

1. **Threads are cheap** - You can have millions of them
2. **Blocking is fine** - VTs park instead of blocking OS threads
3. **Sequential code scales** - No need for async/await or callbacks
4. **I/O creates natural concurrency** - Every I/O operation is a yield point

### How Eru Maximizes VT Benefits

Eru is designed to make VT programming effortless:

```scala
// This sequential-looking code is actually highly concurrent!
def processOrder(orderId: OrderId): Eru[String, Order] = for {
  order <- fetchOrder(orderId)        // VT parks on DB query
  user <- fetchUser(order.userId)     // VT parks on service call
  inventory <- checkInventory(order)  // VT parks on inventory service
  payment <- processPayment(order)    // VT parks on payment gateway
  _ <- sendEmail(user, order)        // VT parks on email service
  _ <- updateAnalytics(order)        // VT parks on analytics service
} yield order

// Process 10,000 orders "sequentially" but actually concurrently!
val orders = orderIds.traverse(processOrder)
```

Each order processes in its own Virtual Thread. When one VT parks on I/O, another takes its place on the carrier thread. You get massive concurrency without writing concurrent code!

## Practical Patterns for VT Success

### Pattern 1: Replace Parallel Maps with Traverse

❌ **Old way:**
```scala
// CPU-bound parallel processing
def processBatch(items: List[Item]): List[Result] = {
  items.par.map(expensivePureComputation).toList
}
```

✅ **VT way:**
```scala
// Add strategic I/O points for natural yielding
def processBatch(items: List[Item]): Eru[Nothing, List[Result]] = {
  items.traverse { item =>
    for {
      // Add caching - VT parks on cache operations
      cached <- cache.get(item.id)
      result <- cached match {
        case Some(r) => Eru.succeed(r)
        case None =>
          for {
            computed <- compute(item)
            _ <- cache.set(item.id, computed)
            // Optional: Add strategic yield points
            _ <- Eru.sleep(0.millis)  // Forces VT scheduling
          } yield computed
      }
    } yield result
  }
}
```

### Pattern 2: Replace Thread Pools with Simple Sequential Code

❌ **Old way:**
```scala
// Complex thread pool management
val executor = Executors.newFixedThreadPool(10)
val futures = items.map { item =>
  CompletableFuture.supplyAsync(() => process(item), executor)
}
CompletableFuture.allOf(futures: _*).join()
```

✅ **VT way:**
```scala
// Just traverse - each item gets its own VT
items.traverse(process)  // That's it! Thousands of VTs will be created
```

### Pattern 3: Replace Parallel Streams with I/O-Enriched Processing

❌ **Old way:**
```scala
// Java parallel streams for CPU work
items.parallelStream()
  .map(this::cpuIntensiveWork)
  .collect(Collectors.toList())
```

✅ **VT way:**
```scala
// Enrich with I/O for natural concurrency
items.traverse { item =>
  for {
    // Check cache first (I/O)
    cached <- redis.get(s"item:${item.id}")
    result <- cached match {
      case Some(json) => parseJson(json)
      case None =>
        for {
          // Maybe fetch additional data (I/O)
          enriched <- fetchEnrichmentData(item)
          computed <- compute(item, enriched)
          // Store result (I/O)
          _ <- redis.set(s"item:${item.id}", computed.toJson)
        } yield computed
    }
  } yield result
}
```

### Pattern 4: Replace Actors with Simple Functions

❌ **Old way:**
```scala
// Complex actor systems for concurrency
class ProcessorActor extends Actor {
  def receive = {
    case ProcessItem(item) =>
      val result = process(item)
      sender() ! result
  }
}
```

✅ **VT way:**
```scala
// Just functions - each call runs in its own VT
def processItem(item: Item): Eru[Error, Result] = {
  // Your logic here - VTs handle concurrency
}

// Process thousands concurrently without actors
items.traverse(processItem)
```

## When You Actually Need CPU Parallelism

Sometimes you really do have CPU-bound work without I/O. For these cases:

### Option 1: Add Strategic I/O Points
```scala
def cpuIntensive(data: Data): Eru[Nothing, Result] = for {
  part1 <- computePart1(data)
  _ <- Eru.sleep(0.millis)  // Strategic yield point
  part2 <- computePart2(data)
  _ <- checkpoint(part1, part2)  // Save progress (I/O)
  part3 <- computePart3(data)
} yield combine(part1, part2, part3)
```

### Option 2: Use Java's ForkJoinPool Directly
```scala
// For pure CPU work, use the right tool
def pureCpuWork(items: List[BigData]): List[Result] = {
  items.par.map(pureComputation).toList
}

// Then integrate with Eru
for {
  data <- fetchData()  // I/O with VT
  results <- Eru.effect(pureCpuWork(data))  // CPU with ForkJoin
  _ <- saveResults(results)  // I/O with VT
} yield results
```

## The Mental Model

### Think in Terms of Resources, Not Threads

**Old:** "I have 8 cores, so I need 8 threads for parallelism"
**New:** "I have thousands of concurrent operations, VTs handle scheduling"

### Think in Terms of Business Logic, Not Concurrency

**Old:** "How do I parallelize this loop?"
**New:** "What does this operation need to do?" (Let VTs handle concurrency)

### Think in Terms of I/O Points, Not Thread Pools

**Old:** "I need a thread pool for database queries"
**New:** "Every database query is a natural concurrency point"

## Real-World Example: API Gateway

Here's how an API gateway benefits from the VT paradigm:

```scala
def handleRequest(request: Request): Eru[Error, Response] = for {
  // Each step parks the VT, allowing massive concurrency

  rateLimitOk <- checkRateLimit(request.ip)      // Redis call - VT parks
  auth <- authenticate(request.token)            // Auth service - VT parks

  // Fan out to multiple services "sequentially"
  userProfile <- userService.getProfile(auth.userId)     // VT parks
  permissions <- authService.getPermissions(auth.userId)  // VT parks
  preferences <- preferenceService.get(auth.userId)      // VT parks

  // Process business logic (fast, sequential)
  validated <- validateRequest(request, permissions)  // Pure, fast
  transformed <- transformRequest(request, preferences) // Pure, fast

  // Call backend service
  result <- backendService.process(transformed)  // VT parks

  // Update analytics asynchronously
  _ <- analytics.track(request, result).fork  // Fire and forget

  // Format response
  response <- formatResponse(result, preferences)  // Pure, fast

} yield response

// Handle thousands of concurrent requests with simple sequential code!
val server = requests.traverse(handleRequest)
```

This sequential code handles thousands of concurrent requests efficiently because Virtual Threads park at each I/O point, allowing other threads to run.

## Summary: The New Rules

1. **Write sequential code** - VTs make it concurrent
2. **Embrace I/O** - Every I/O operation is a concurrency point
3. **Forget thread pools** - VTs are the pool
4. **Stop parallelizing** - Start thinking about business logic
5. **Trust the runtime** - VTs and Eru handle the complexity

## The Eru Promise

Eru is built for this new world. We're not trying to win at old-style parallel CPU benchmarks. We're building the fastest, simplest effect system for the Virtual Thread era.

**Your code stays simple. Performance comes for free.**