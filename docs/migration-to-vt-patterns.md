# Migration Guide: From Traditional Parallelism to Virtual Threads

## Quick Reference: Pattern Transformations

| Traditional Pattern | VT + Eru Pattern | Performance Impact |
|---------------------|------------------|-------------------|
| `list.par.map(f)` | `list.traverse(f)` | Better if f has any I/O |
| `Future.sequence` | `Eru.traverse` | 100-700x faster |
| Thread pools | Just use traverse | Simpler, often faster |
| Async/await | Sequential for-comprehension | Much simpler, same perf |
| Actor systems | Plain functions + traverse | 100x+ faster, simpler |

## Common Migrations

### 1. Batch Processing

#### Before (Traditional Parallel)
```scala
def processBatch(records: List[Record]): List[Result] = {
  val parallelism = Runtime.getRuntime.availableProcessors
  records.grouped(records.size / parallelism)
    .map(chunk => chunk.par.map(processRecord))
    .flatten.toList
}

def processRecord(record: Record): Result = {
  val computed = heavyComputation(record)
  database.save(computed)  // Blocking!
  computed
}
```

#### After (VT Pattern)
```scala
def processBatch(records: List[Record]): Eru[DbError, List[Result]] = {
  records.traverse(processRecord)  // Each gets its own VT!
}

def processRecord(record: Record): Eru[DbError, Result] = for {
  computed <- Eru.effect(heavyComputation(record))
  _ <- database.save(computed)  // VT parks here - perfect!
} yield computed
```

**Benefits:**
- Simpler code (no manual chunking)
- Better resource utilization (VTs park on I/O)
- Automatic optimal concurrency

### 2. Microservice Aggregation

#### Before (Complex Futures)
```scala
def aggregateData(userId: UserId): Future[AggregatedData] = {
  val profileF = profileService.getProfile(userId)
  val ordersF = orderService.getOrders(userId)
  val prefsF = preferenceService.getPreferences(userId)

  for {
    profile <- profileF
    orders <- ordersF
    prefs <- prefsF
  } yield AggregatedData(profile, orders, prefs)
}
```

#### After (Sequential but Concurrent)
```scala
def aggregateData(userId: UserId): Eru[ServiceError, AggregatedData] = for {
  // Looks sequential but runs concurrently thanks to VTs!
  profile <- profileService.getProfile(userId)
  orders <- orderService.getOrders(userId)
  prefs <- preferenceService.getPreferences(userId)
} yield AggregatedData(profile, orders, prefs)

// Or for explicit parallel execution:
def aggregateDataPar(userId: UserId): Eru[ServiceError, AggregatedData] = {
  runtime.zipPar(
    profileService.getProfile(userId),
    runtime.zipPar(
      orderService.getOrders(userId),
      preferenceService.getPreferences(userId)
    )
  ).map { case (profile, (orders, prefs)) =>
    AggregatedData(profile, orders, prefs)
  }
}
```

### 3. Stream Processing

#### Before (Akka Streams / FS2)
```scala
Source(events)
  .mapAsync(10)(event => processEvent(event))
  .buffer(100, OverflowStrategy.backpressure)
  .to(Sink.foreach(save))
  .run()
```

#### After (Simple Sequential with Natural Backpressure)
```scala
def processStream(events: List[Event]): Eru[Error, Unit] = {
  events.traverse { event =>
    for {
      processed <- processEvent(event)  // VT parks on I/O
      _ <- save(processed)              // VT parks again
    } yield ()
  }.map(_ => ())
}

// For infinite streams with batching:
def streamProcessor(queue: Queue[Event]): Eru[Error, Nothing] = {
  def processBatch = for {
    batch <- queue.takeBatch(100)  // Natural backpressure
    _ <- batch.traverse(processEvent)
  } yield ()

  processBatch.forever  // Infinite loop with VT yielding
}
```

### 4. Web Scraping

#### Before (Thread Pool)
```scala
val executor = Executors.newFixedThreadPool(20)
val futures = urls.map { url =>
  CompletableFuture.supplyAsync(() => {
    val html = fetchUrl(url)  // Blocking HTTP call
    parseHtml(html)
  }, executor)
}
CompletableFuture.allOf(futures: _*).get()
```

#### After (Simple Traverse)
```scala
def scrapeUrls(urls: List[Url]): Eru[HttpError, List[ParsedData]] = {
  urls.traverse { url =>
    for {
      html <- fetchUrl(url)      // VT parks on network I/O
      parsed <- parseHtml(html)  // Fast parsing
      _ <- saveToCache(parsed)   // VT parks on cache I/O
    } yield parsed
  }
}

// Process thousands of URLs concurrently with just:
val results = scrapeUrls(thousandsOfUrls)
```

### 5. Database Operations

#### Before (Connection Pool Management)
```scala
val pool = HikariCP.create(maxConnections = 20)

def batchInsert(records: List[Record]): Unit = {
  records.grouped(20).foreach { batch =>
    batch.par.foreach { record =>
      val conn = pool.getConnection()
      try {
        insert(conn, record)
      } finally {
        conn.close()
      }
    }
  }
}
```

#### After (Let VTs Handle Concurrency)
```scala
def batchInsert(records: List[Record]): Eru[DbError, Unit] = {
  records.traverse { record =>
    database.insert(record)  // Each VT parks on I/O
  }.map(_ => ())
}

// VTs naturally limit concurrency by parking
// No manual pool management needed!
```

### 6. Cache Warming

#### Before (Parallel Collection)
```scala
def warmCache(keys: List[Key]): Map[Key, Value] = {
  keys.par.map { key =>
    val value = Option(cache.get(key)).getOrElse {
      val computed = expensiveCompute(key)
      cache.put(key, computed)
      computed
    }
    key -> value
  }.toMap
}
```

#### After (Natural I/O Concurrency)
```scala
def warmCache(keys: List[Key]): Eru[Nothing, Map[Key, Value]] = {
  keys.traverse { key =>
    for {
      cached <- cache.get(key)       // VT parks on cache read
      value <- cached match {
        case Some(v) => Eru.succeed(v)
        case None =>
          for {
            computed <- compute(key)
            _ <- cache.put(key, computed)  // VT parks on cache write
          } yield computed
      }
    } yield key -> value
  }.map(_.toMap)
}
```

## Anti-Patterns to Avoid

### ❌ Don't manually create thread pools
```scala
// Bad - unnecessary complexity
val executor = Executors.newFixedThreadPool(10)
```

### ✅ Do let VTs handle concurrency
```scala
// Good - simpler and often faster
items.traverse(process)
```

### ❌ Don't use parallel collections for I/O operations
```scala
// Bad - parallel collections are for CPU work
urls.par.map(fetchUrl)
```

### ✅ Do use traverse for I/O operations
```scala
// Good - VTs park on I/O naturally
urls.traverse(fetchUrl)
```

### ❌ Don't chunk work manually
```scala
// Bad - manual chunking is error-prone
items.grouped(batchSize).foreach(processBatch)
```

### ✅ Do process items directly
```scala
// Good - VTs handle scheduling
items.traverse(processItem)
```

## Performance Tips

### 1. Add Strategic Yield Points for CPU-Heavy Work
```scala
def cpuIntensive(data: Data): Eru[Nothing, Result] = for {
  part1 <- computePart1(data)
  _ <- Eru.sleep(Duration.ZERO)  // Yield to scheduler
  part2 <- computePart2(data)
} yield combine(part1, part2)
```

### 2. Use Caching to Add I/O Points
```scala
def enrichWithCache(item: Item): Eru[Error, EnrichedItem] = for {
  cached <- cache.get(item.id)  // I/O point for VT parking
  enriched <- cached.match {
    case Some(e) => Eru.succeed(e)
    case None => computeAndCache(item)
  }
} yield enriched
```

### 3. Prefer traverse Over parTraverse for I/O Work
```scala
// Often faster due to simpler scheduling
items.traverse(fetchFromApi)

// Only use if you need guaranteed parallelism
runtime.parTraverse(items)(fetchFromApi)
```

## Summary

The Virtual Thread paradigm means:
1. **Stop thinking about thread pools** - VTs are your pool
2. **Stop manual parallelization** - Write sequential code
3. **Embrace I/O operations** - They're your concurrency points
4. **Trust the runtime** - It handles the complexity

**The result:** Simpler code that's often faster than complex parallel implementations!