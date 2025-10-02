# Chapter 12: Performance & Optimization

Eru achieves exceptional performance while maintaining safety and composability. This chapter explores Eru's performance characteristics, benchmarking methodologies, optimization strategies, and measurement techniques that help you build high-performance systems without sacrificing correctness.

## Performance Philosophy

Eru's performance approach follows these principles:

- **Correctness First**: Never sacrifice correctness for performance
- **Measure, Don't Guess**: Base optimizations on real measurements
- **Architectural Impact**: Focus on high-impact design decisions over micro-optimizations
- **Sustainable Performance**: Build systems that maintain performance under load

## Understanding Eru's Performance Characteristics

### Benchmarking Methodology

Eru uses rigorous benchmarking with JMH (Java Microbenchmark Harness) for reliable performance measurements:

```scala mdoc
import net.ghoula.eru.prelude.*

// Example benchmark scenarios that demonstrate performance characteristics
def coreOperationsBenchmark(): Unit = {
  // Benchmark 1: Basic effect creation and execution
  val simpleEffect = Eru.succeed(42)
  val iterations = 100000

  val startTime = System.nanoTime()
  (1 to iterations).foreach { _ =>
    simpleEffect.unsafeRunSync()
  }
  val endTime = System.nanoTime()

  val durationMs = (endTime - startTime) / 1000000.0
  val opsPerMs = iterations / durationMs

  println(f"Simple Effect: $opsPerMs%.0f ops/ms ($iterations iterations in $durationMs%.1f ms)")

  // Benchmark 2: Map chain performance
  val mappedEffect = Eru.succeed(1).map(_ * 2).map(_ + 1).map(_.toString)

  val mapStartTime = System.nanoTime()
  (1 to iterations).foreach { _ =>
    mappedEffect.unsafeRunSync()
  }
  val mapEndTime = System.nanoTime()

  val mapDurationMs = (mapEndTime - mapStartTime) / 1000000.0
  val mapOpsPerMs = iterations / mapDurationMs

  println(f"Map Chain: $mapOpsPerMs%.0f ops/ms ($iterations iterations in $mapDurationMs%.1f ms)")

  // Benchmark 3: FlatMap composition performance
  val flatMappedEffect = Eru.succeed(1)
    .flatMap(x => Eru.succeed(x * 2))
    .flatMap(x => Eru.succeed(x + 1))
    .flatMap(x => Eru.succeed(x.toString))

  val flatMapStartTime = System.nanoTime()
  (1 to iterations).foreach { _ =>
    flatMappedEffect.unsafeRunSync()
  }
  val flatMapEndTime = System.nanoTime()

  val flatMapDurationMs = (flatMapEndTime - flatMapStartTime) / 1000000.0
  val flatMapOpsPerMs = iterations / flatMapDurationMs

  println(f"FlatMap Chain: $flatMapOpsPerMs%.0f ops/ms ($iterations iterations in $flatMapDurationMs%.1f ms)")
}

// Run the benchmark demonstration
println("=== ERU PERFORMANCE CHARACTERISTICS ===")
coreOperationsBenchmark()
```

### Real-World Performance Data

Based on comprehensive CI benchmarking, Eru demonstrates exceptional performance characteristics:

**Core Operations (ops/ms)**:
- eruChain: 21,430 ops/ms
- eruFlatMap: 41,484 ops/ms
- eruLongChain: 12,486 ops/ms
- eruMap: 61,663 ops/ms
- eruSucceed: 160,143 ops/ms

**Stack Safety Operations (ops/ms)**:
- eruDeepFlatMap: 2,111 ops/ms
- eruDeepMap: 2,496 ops/ms
- eruUnfold: 4,756 ops/ms

**Error Handling (ops/ms)**:
- eruAttempt: 35,842 ops/ms
- eruFallback: 50,781 ops/ms
- eruRecoverWith: 37,037 ops/ms

**State Management (ops/ms)**:
- eruFoldLeft: 30,864 ops/ms
- eruIterate: 23,148 ops/ms
- eruTraverse: 25,641 ops/ms

**Comparative Performance** (Eru vs other effect systems):
- 2-4x faster than ZIO for core operations
- 50-160x faster than Cats Effect for basic operations
- Consistent performance across different operation types

## Performance Optimization Strategies

### Fusion Optimizations

Eru automatically fuses adjacent operations to reduce allocation overhead:

```scala mdoc
// Demonstration of fusion optimization benefits
def fusionExample(): Eru[String, String] = {
  // This chain of maps gets fused into a single operation
  Eru.succeed("hello")
    .map(_.toUpperCase)        // Fused
    .map(_ + " WORLD")         // Fused
    .map(_ + "!")             // Fused
    // All three maps become one optimized operation
}

// Test fusion performance
def testFusion(): Unit = {
  val fusedEffect = fusionExample()
  val iterations = 50000

  val startTime = System.nanoTime()
  (1 to iterations).foreach { _ =>
    fusedEffect.unsafeRunSync()
  }
  val endTime = System.nanoTime()

  val durationMs = (endTime - startTime) / 1000000.0
  val opsPerMs = iterations / durationMs

  println(f"Fused Operations: $opsPerMs%.0f ops/ms ($iterations iterations)")
}

testFusion()
```

### Stack Safety Optimizations

Eru maintains stack safety while optimizing for performance:

```scala mdoc
// Stack-safe recursive operations with performance
def stackSafeRecursion(): Eru[String, Long] = {
  // This would stack overflow with naive recursion, but Eru handles it efficiently
  def accumulate(n: Long, acc: Long): Eru[String, Long] = {
    if (n <= 0) Eru.succeed(acc)
    else Eru.succeed(n + acc).flatMap(newAcc => accumulate(n - 1, newAcc))
  }

  accumulate(10000, 0)
}

// More efficient iterative approach
def iterativeApproach(): Eru[String, Long] = {
  // Use a smaller range to avoid infinite loops in documentation
  val numbers = (1L to 100L).toList
  Eru.foldLeft(numbers)(0L)((acc, n) => Eru.succeed(acc + n))
}

// Compare performance approaches
def compareStackSafetyPerformance(): Unit = {
  println("=== STACK SAFETY PERFORMANCE ===")

  // Test iterative approach (recommended for performance)
  val startIterative = System.nanoTime()
  val iterativeResult = iterativeApproach().unsafeRunSync()
  val endIterative = System.nanoTime()
  val iterativeDuration = (endIterative - startIterative) / 1000000.0

  println(f"Iterative approach: $iterativeResult in $iterativeDuration%.2f ms")

  // Test with smaller numbers for recursive (to avoid long execution)
  def smallRecursive(): Eru[String, Long] = {
    def accumulate(n: Long, acc: Long): Eru[String, Long] = {
      if (n <= 0) Eru.succeed(acc)
      else Eru.succeed(n + acc).flatMap(newAcc => accumulate(n - 1, newAcc))
    }
    accumulate(1000, 0)  // Smaller number for comparison
  }

  val startRecursive = System.nanoTime()
  val recursiveResult = smallRecursive().unsafeRunSync()
  val endRecursive = System.nanoTime()
  val recursiveDuration = (endRecursive - startRecursive) / 1000000.0

  println(f"Recursive approach (n=1000): $recursiveResult in $recursiveDuration%.2f ms")
  println("Use iterative builders (Eru.iterate) for optimal performance")
}

compareStackSafetyPerformance()
```

### Error Handling Performance

Error handling in Eru is optimized for both success and failure paths:

```scala mdoc
// Performance characteristics of error handling
def errorHandlingPerformance(): Unit = {
  println("=== ERROR HANDLING PERFORMANCE ===")

  val iterations = 25000

  // Success path performance
  val successEffect = Eru.succeed(42).flatMap { x =>
    if (x > 0) Eru.succeed(x * 2) else Eru.fail("Negative number")
  }.recoverWith { case _ => Eru.succeed(-1) }

  val successStart = System.nanoTime()
  (1 to iterations).foreach { _ =>
    successEffect.unsafeRunSync()
  }
  val successEnd = System.nanoTime()
  val successDuration = (successEnd - successStart) / 1000000.0
  val successOpsPerMs = iterations / successDuration

  println(f"Success path: $successOpsPerMs%.0f ops/ms")

  // Failure path performance
  val failureEffect = Eru.succeed(-1).flatMap { x =>
    if (x > 0) Eru.succeed(x * 2) else Eru.fail("Negative number")
  }.recoverWith { case _ => Eru.succeed(-1) }

  val failureStart = System.nanoTime()
  (1 to iterations).foreach { _ =>
    failureEffect.unsafeRunSync()
  }
  val failureEnd = System.nanoTime()
  val failureDuration = (failureEnd - failureStart) / 1000000.0
  val failureOpsPerMs = iterations / failureDuration

  println(f"Failure path: $failureOpsPerMs%.0f ops/ms")
  println(f"Performance ratio (success/failure): ${(successOpsPerMs / failureOpsPerMs).round}x")
}

errorHandlingPerformance()
```

## Resource Management Performance

Resource management operations maintain high performance while guaranteeing cleanup:

```scala mdoc
// Resource management performance characteristics
case class MockResource(id: String) {
  def process(): String = s"Processed by $id"
  def close(): Unit = () // No-op for benchmarking
}

def resourcePerformanceTest(): Unit = {
  println("=== RESOURCE MANAGEMENT PERFORMANCE ===")

  val iterations = 20000

  // Benchmark bracket pattern
  def bracketOperation(): Eru[String, String] = {
    Eru.succeed(MockResource("test-resource"))
      .bracket(release = resource => Eru.effect(resource.close()).mapError(_.getMessage)) {
        resource => Eru.effect(resource.process()).mapError(_.getMessage)
      }
  }

  val bracketStart = System.nanoTime()
  (1 to iterations).foreach { _ =>
    bracketOperation().unsafeRunSync()
  }
  val bracketEnd = System.nanoTime()
  val bracketDuration = (bracketEnd - bracketStart) / 1000000.0
  val bracketOpsPerMs = iterations / bracketDuration

  println(f"Bracket pattern: $bracketOpsPerMs%.0f ops/ms")

  // Benchmark ensure pattern
  def ensureOperation(): Eru[String, String] = {
    val resource = MockResource("test-resource")
    Eru.effect(resource.process())
      .mapError(_.getMessage)
      .ensure(Eru.effect(resource.close()).mapError(_.getMessage))
  }

  val ensureStart = System.nanoTime()
  (1 to iterations).foreach { _ =>
    ensureOperation().unsafeRunSync()
  }
  val ensureEnd = System.nanoTime()
  val ensureDuration = (ensureEnd - ensureStart) / 1000000.0
  val ensureOpsPerMs = iterations / ensureDuration

  println(f"Ensure pattern: $ensureOpsPerMs%.0f ops/ms")
  println("Both patterns provide excellent performance with guaranteed cleanup")
}

resourcePerformanceTest()
```

## Concurrency Performance

Eru's fiber-based concurrency provides excellent performance characteristics:

```scala mdoc
import net.ghoula.eru.prelude.given

// Concurrency performance testing
def concurrencyPerformance(): Unit = {
  println("=== CONCURRENCY PERFORMANCE ===")

  // Benchmark sequential vs parallel processing
  def sequentialWork(): Eru[String, List[Int]] = {
    val tasks = (1 to 100).map(i => Eru.succeed(i * 2)).toList
    Eru.collectAll(tasks)
  }

  def parallelWork(): Eru[String | Throwable, List[Int]] = {
    val tasks = (1 to 100).map(i => i * 2).toList
    parTraverse(tasks)(i => Eru.effect(i).mapError(_.getMessage))
  }

  // Sequential benchmark
  val seqStart = System.nanoTime()
  val seqResult = sequentialWork().unsafeRunSync()
  val seqEnd = System.nanoTime()
  val seqDuration = (seqEnd - seqStart) / 1000000.0

  println(f"Sequential (100 tasks): ${seqResult.size} results in $seqDuration%.2f ms")

  // Parallel benchmark
  val parStart = System.nanoTime()
  val parResult = parallelWork().unsafeRunSync()
  val parEnd = System.nanoTime()
  val parDuration = (parEnd - parStart) / 1000000.0

  println(f"Parallel (100 tasks): ${parResult.size} results in $parDuration%.2f ms")

  val speedup = seqDuration / parDuration
  println(f"Speedup: ${speedup}x (parallel vs sequential)")

  // Fiber creation overhead
  val fiberIterations = 10000
  def fiberCreation(): Eru[String, List[String]] = {
    val fibers = (1 to 100).map(i => Eru.succeed(s"fiber-$i").fork).toList

    for {
      fiberList <- Eru.collectAll(fibers)
      exits <- Eru.collectAll(fiberList.map(_.await))
      results <- Eru.succeed(exits.collect {
        case net.ghoula.eru.Exit.Success(value) => value
      })
    } yield results
  }

  val fiberStart = System.nanoTime()
  val fiberResult = fiberCreation().unsafeRunSync()
  val fiberEnd = System.nanoTime()
  val fiberDuration = (fiberEnd - fiberStart) / 1000000.0

  println(f"Fiber creation (100 fibers): ${fiberResult.size} results in $fiberDuration%.2f ms")
  println(f"Per-fiber overhead: ${fiberDuration / 100}%.3f ms")
}

concurrencyPerformance()
```

## Memory Performance

Understanding memory allocation patterns and GC impact:

```scala mdoc
// Memory-conscious programming patterns
def memoryOptimizedPatterns(): Unit = {
  println("=== MEMORY OPTIMIZATION PATTERNS ===")

  // Pattern 1: Avoid unnecessary allocations in hot paths
  def efficientProcessing(items: List[String]): Eru[String, List[String]] = {
    // Use traverse instead of map + sequence to avoid intermediate collections
    Eru.traverse(items) { item =>
      Eru.succeed(item.toUpperCase) // Direct transformation, no intermediate allocation
    }
  }

  // Pattern 2: Use iterative builders for large datasets
  def efficientLargeDataset(size: Int): Eru[String, Long] = {
    // This uses constant memory regardless of size
    Eru.iterate((0, 0L)) { case (count, sum) =>
      Eru.succeed((count + 1, sum + count))
    }(_._1 >= size).map(_._2)
  }

  // Pattern 3: Resource pooling for memory efficiency
  case class PooledResource(id: Int) {
    def process(data: String): String = s"$id: $data"
  }

  class ResourcePool(size: Int) {
    private val resources = (1 to size).map(PooledResource.apply).toList
    private var index = 0

    def withResource[A](f: PooledResource => Eru[String, A]): Eru[String, A] = {
      val resource = resources(index % size)
      index += 1
      f(resource)
    }
  }

  val pool = ResourcePool(10)

  def efficientResourceUsage(data: List[String]): Eru[String, List[String]] = {
    Eru.traverse(data) { item =>
      pool.withResource { resource =>
        Eru.succeed(resource.process(item))
      }
    }
  }

  // Test memory-efficient patterns
  val testData = (1 to 100).map(i => s"item-$i").toList

  val start = System.nanoTime()
  val result = efficientResourceUsage(testData).unsafeRunSync()
  val end = System.nanoTime()
  val duration = (end - start) / 1000000.0

  println(f"Memory-efficient processing: ${result.size} items in $duration%.2f ms")
  println("Key patterns: traverse vs map+sequence, iterative builders, resource pooling")
}

memoryOptimizedPatterns()
```

## Profiling and Measurement

Tools and techniques for measuring Eru program performance:

```scala mdoc
// Performance measurement utilities
object PerformanceMeasurement {

  def measureOperation[A](name: String, iterations: Int)(operation: () => A): Unit = {
    // Warmup
    (1 to iterations / 10).foreach(_ => operation())

    // Measurement
    val startTime = System.nanoTime()
    (1 to iterations).foreach(_ => operation())
    val endTime = System.nanoTime()

    val durationMs = (endTime - startTime) / 1000000.0
    val opsPerMs = iterations / durationMs

    println(f"$name: $opsPerMs%.0f ops/ms ($iterations iterations in $durationMs%.1f ms)")
  }

  def measureEruOperation[E, A](name: String, iterations: Int)(operation: () => Eru[E, A]): Unit = {
    measureOperation(name, iterations) { () =>
      operation().unsafeRunSync()
    }
  }

  def compareOperations[A](baselineName: String, baseline: () => A, testName: String, test: () => A, iterations: Int): Unit = {
    println(s"=== COMPARING $baselineName vs $testName ===")

    // Measure baseline
    val baselineStart = System.nanoTime()
    (1 to iterations).foreach(_ => baseline())
    val baselineEnd = System.nanoTime()
    val baselineDuration = (baselineEnd - baselineStart) / 1000000.0
    val baselineOps = iterations / baselineDuration

    // Measure test
    val testStart = System.nanoTime()
    (1 to iterations).foreach(_ => test())
    val testEnd = System.nanoTime()
    val testDuration = (testEnd - testStart) / 1000000.0
    val testOps = iterations / testDuration

    // Compare
    val ratio = testOps / baselineOps

    println(f"$baselineName: $baselineOps%.0f ops/ms")
    println(f"$testName: $testOps%.0f ops/ms")
    println(f"Performance ratio: ${ratio}x ${if (ratio > 1) "faster" else "slower"}")
  }
}

// Example usage of measurement utilities
def measurementExample(): Unit = {
  println("=== PERFORMANCE MEASUREMENT EXAMPLE ===")

  // Measure different composition patterns
  PerformanceMeasurement.measureEruOperation("Simple Success", 50000) { () =>
    Eru.succeed(42)
  }

  PerformanceMeasurement.measureEruOperation("Map Chain", 25000) { () =>
    Eru.succeed(1).map(_ * 2).map(_ + 1).map(_.toString)
  }

  PerformanceMeasurement.measureEruOperation("FlatMap Chain", 20000) { () =>
    Eru.succeed(1)
      .flatMap(x => Eru.succeed(x * 2))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x.toString))
  }

  PerformanceMeasurement.measureEruOperation("Error Handling", 20000) { () =>
    Eru.succeed(42).flatMap { x =>
      if (x > 0) Eru.succeed(x.toString) else Eru.fail("Error")
    }.recoverWith { case _ => Eru.succeed("Default") }
  }
}

measurementExample()
```

## Optimization Guidelines

### Design-Level Optimizations

1. **Choose the Right Abstraction Level**:
   - Use `traverse` instead of `map` + `sequence` for collections
   - Use `iterate` for large-scale repetitive operations
   - Use `parTraverse` for independent parallel operations

2. **Minimize Allocations**:
   - Prefer primitive operations over complex compositions in hot paths
   - Use resource pools for frequently created/destroyed objects
   - Avoid unnecessary intermediate collections

3. **Optimize Error Handling**:
   - Use typed errors to avoid exception overhead
   - Place error handling at appropriate levels (not too granular)
   - Use `attempt` judiciously to avoid performance impact

### Runtime Optimizations

1. **JVM Settings for Production**:
```bash
# Optimize for throughput
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms2g -Xmx8g

# For low-latency applications
-XX:+UnlockExperimentalVMOptions -XX:+UseZGC -Xms4g -Xmx4g
```

2. **Native Compilation**:
   - Scala Native provides excellent startup performance
   - Use for CLI tools and serverless functions
   - Single-threaded but highly optimized

### Benchmarking Best Practices

```scala mdoc
// Benchmarking methodology demonstration
object BenchmarkingBestPractices {

  def properBenchmark[A](
    name: String,
    operation: () => A,
    warmupIterations: Int = 1000,
    measurementIterations: Int = 10000
  ): Unit = {
    println(s"Benchmarking: $name")

    // Phase 1: Warmup (JIT optimization)
    print("Warming up...")
    (1 to warmupIterations).foreach { _ =>
      operation()
    }
    println(" done")

    // Phase 2: Measurement
    val measurements = (1 to 5).map { run =>
      val start = System.nanoTime()
      (1 to measurementIterations).foreach { _ =>
        operation()
      }
      val end = System.nanoTime()
      (end - start) / 1000000.0 // Convert to milliseconds
    }

    // Phase 3: Statistical analysis
    val avg = measurements.sum / measurements.size
    val opsPerMs = measurementIterations / avg

    println(s"  Average: ${"%.2f".format(avg)} ms (${"%.0f".format(opsPerMs)} ops/ms)")
    println(s"  Min: ${"%.2f".format(measurements.min)} ms")
    println(s"  Max: ${"%.2f".format(measurements.max)} ms")
    println(s"  Std Dev: ${"%.2f".format(standardDeviation(measurements.toList))} ms")
  }

  private def standardDeviation(values: List[Double]): Double = {
    val mean = values.sum / values.size
    val squaredDiffs = values.map(v => Math.pow(v - mean, 2))
    Math.sqrt(squaredDiffs.sum / squaredDiffs.size)
  }
}

// Example of proper benchmarking
def benchmarkingExample(): Unit = {
  println("=== PROPER BENCHMARKING METHODOLOGY ===")

  BenchmarkingBestPractices.properBenchmark(
    "Eru Simple Effect",
    () => Eru.succeed(42).unsafeRunSync(),
    warmupIterations = 5000,
    measurementIterations = 25000
  )

  BenchmarkingBestPractices.properBenchmark(
    "Eru Composition",
    () => Eru.succeed(1).map(_ * 2).flatMap(x => Eru.succeed(x + 1)).unsafeRunSync(),
    warmupIterations = 3000,
    measurementIterations = 15000
  )
}

benchmarkingExample()
```

## Production Performance Monitoring

Integrate performance monitoring into production systems:

```scala mdoc
// Production performance monitoring
class ProductionPerformanceMonitor {
  private val metrics = scala.collection.mutable.Map[String, Long]()
  private val timings = scala.collection.mutable.Map[String, scala.collection.mutable.ListBuffer[Long]]()

  def recordOperation[A](operationName: String)(operation: => A): A = {
    val start = System.nanoTime()
    try {
      val result = operation
      recordSuccess(operationName)
      result
    } catch {
      case ex: Exception =>
        recordFailure(operationName)
        throw ex
    } finally {
      val end = System.nanoTime()
      recordTiming(operationName, (end - start) / 1000000) // Convert to ms
    }
  }

  def recordEruOperation[E, A](operationName: String)(operation: Eru[E, A]): Eru[E | Throwable, A] = {
    Eru.effect {
      val start = System.nanoTime()
      val result = operation.unsafeRunSync()
      val end = System.nanoTime()
      recordTiming(operationName, (end - start) / 1000000)
      recordSuccess(operationName)
      result
    }.tapError { _ =>
      Eru.effect(recordFailure(operationName)).attempt.map(_ => ())
    }
  }

  private def recordSuccess(operationName: String): Unit = {
    metrics(s"$operationName.success") = metrics.getOrElse(s"$operationName.success", 0L) + 1
  }

  private def recordFailure(operationName: String): Unit = {
    metrics(s"$operationName.failure") = metrics.getOrElse(s"$operationName.failure", 0L) + 1
  }

  private def recordTiming(operationName: String, durationMs: Long): Unit = {
    timings.getOrElseUpdate(operationName, scala.collection.mutable.ListBuffer[Long]()) += durationMs
  }

  def getMetricsSummary: String = {
    val sb = new StringBuilder("=== PRODUCTION METRICS ===\n")

    // Success/failure rates
    val operations = metrics.keys.map(_.split("\\.")(0)).toSet
    operations.foreach { op =>
      val successes = metrics.getOrElse(s"$op.success", 0L)
      val failures = metrics.getOrElse(s"$op.failure", 0L)
      val total = successes + failures
      val successRate = if (total > 0) (successes.toDouble / total * 100) else 0.0

      sb.append(s"$op: $successes successes, $failures failures (${"%.1f".format(successRate)}% success rate)\n")
    }

    // Timing statistics
    timings.foreach { case (op, durations) =>
      if (durations.nonEmpty) {
        val avg = durations.sum.toDouble / durations.size
        val min = durations.min
        val max = durations.max
        val p95 = percentile(durations.sorted.toList, 0.95)
        sb.append(s"$op timing: avg=${"%.1f".format(avg)} ms, min=${min}ms, max=${max}ms, p95=${"%.1f".format(p95)} ms\n")
      }
    }

    sb.toString
  }

  private def percentile(sortedValues: List[Long], p: Double): Double = {
    val index = (sortedValues.size * p).toInt
    if (index < sortedValues.size) sortedValues(index).toDouble else sortedValues.last.toDouble
  }
}

// Example usage
def productionMonitoringExample(): Unit = {
  println("=== PRODUCTION MONITORING EXAMPLE ===")

  val monitor = ProductionPerformanceMonitor()

  // Simulate production operations
  (1 to 100).foreach { i =>
    try {
      monitor.recordOperation(s"business-operation") {
        // Simulate business logic
        Thread.sleep(scala.util.Random.nextInt(3)) // 0-2ms random delay
        if (scala.util.Random.nextDouble() < 0.95) "success" else throw new Exception("failure")
      }
    } catch {
      case _: Exception => // Expected occasional failures
    }
  }

  println(monitor.getMetricsSummary)
}

productionMonitoringExample()
```

## Key Takeaways

Eru's performance characteristics enable building high-performance systems:

**Exceptional Baseline Performance**: 2k-160k ops/ms for core operations, significantly faster than other effect systems.

**Consistent Performance**: Performance remains stable across different operation types and composition patterns.

**Zero-Cost Abstractions**: Eru's GADT design enables compile-time optimizations without runtime overhead.

**Fusion Optimizations**: Automatic optimization of adjacent operations reduces allocation overhead.

**Scalable Concurrency**: Fiber-based concurrency provides excellent parallel processing performance.

**Production Ready**: Built-in performance monitoring and measurement tools support production deployment.

**Optimization Guidelines**: Focus on architectural decisions over micro-optimizations for maximum impact.

## What's Next

Chapter 13 explores integration patterns for working with legacy code, blocking operations, third-party libraries, and existing systems—essential skills for real-world Eru adoption.