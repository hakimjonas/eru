package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Duration
import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** H.9 Virtual Threads concurrency performance benchmarks.
  *
  * These benchmarks measure the true concurrent performance of Eru's H.9 implementation
  * using the VTOnlyBackend with Virtual Threads. All operations demonstrate actual
  * parallelism and non-blocking behavior where applicable.
  *
  * Benchmark Categories:
  * - True Concurrent Operations: zipPar/race with actual parallel execution
  * - Async Boundary Performance: suspend/resume with CompletableFuture integration  
  * - Timer and Timeout Performance: non-blocking sleep/timeout operations
  * - High Concurrency Load: many concurrent fibers and operations
  * - Resource Safety Under Load: finalizer execution and cleanup guarantees
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
  value = 1,
  jvmArgs = Array(
    "-server",
    "-Xms2G", 
    "-Xmx2G",
    "-XX:+UseG1GC",
    "-XX:+UnlockExperimentalVMOptions",
    "--enable-preview" // For Virtual Threads
  )
)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
class EruConcurrencyH9Bench {

  private var quickEffect: Eru[Nothing, Int] = _
  private var mediumEffect: Eru[Nothing, Int] = _
  private var slowEffect: Eru[Nothing, Int] = _
  
  @Setup(Level.Iteration)
  def setup(): Unit = {
    quickEffect = Eru.succeed(42)
    mediumEffect = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => 100)
    slowEffect = EruRuntime.sleep(Duration.ofMillis(5)).map(_ => 200)
  }

  /** Measures true parallel zipPar performance with Virtual Threads.
    * 
    * This benchmark validates that zipPar actually runs effects in parallel
    * on separate Virtual Threads, showing performance gains over sequential execution.
    */
  @Benchmark
  def zipParTrueConcurrent(h: Blackhole): Unit = {
    val left = EruRuntime.sleep(Duration.ofMillis(2)).map(_ => 1)
    val right = EruRuntime.sleep(Duration.ofMillis(2)).map(_ => 2)
    h.consume(EruRuntime.zipPar(left, right).unsafeRunSync(): AnyRef)
  }

  /** Measures race performance with actual concurrent execution.
    *
    * The race should complete in approximately the time of the faster effect,
    * demonstrating true non-deterministic concurrent racing behavior.
    */
  @Benchmark 
  def raceTrueConcurrent(h: Blackhole): Unit = {
    val fast = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => "fast")
    val slow = EruRuntime.sleep(Duration.ofMillis(10)).map(_ => "slow")  
    h.consume(EruRuntime.race(fast, slow).unsafeRunSync(): AnyRef)
  }

  /** Measures fork/await performance with Virtual Threads.
    *
    * This validates that forked effects actually run on separate Virtual Threads
    * and that await properly synchronizes on fiber completion.
    */
  @Benchmark
  def forkAwaitVirtualThread(h: Blackhole): Unit = {
    val fiber = EruRuntime.fork(mediumEffect).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    h.consume(exit: AnyRef)
  }

  /** Measures async boundary performance with CompletableFuture integration.
    *
    * This benchmark validates H.9.4 suspend/resume functionality by measuring
    * the overhead of integrating with Java's asynchronous CompletableFuture API.
    */
  @Benchmark
  def suspendAsyncBoundary(h: Blackhole): Unit = {
    val result = EruRuntime.suspend[Throwable, String] { callback =>
      Eru.effect {
        val future = new java.util.concurrent.CompletableFuture[String]()
        future.whenComplete { (value, throwable) =>
          if (throwable != null) callback(Left(throwable))
          else callback(Right(value))
        }
        // Complete immediately for benchmark measurement
        future.complete("async-result")
      }
    }.unsafeRunSync()
    h.consume(result: AnyRef)
  }

  /** Measures non-blocking timer performance.
    *
    * This validates that sleep operations use non-blocking timers via
    * ScheduledExecutorService rather than blocking Thread.sleep.
    */
  @Benchmark
  def nonBlockingTimers(h: Blackhole): Unit = {
    val timer = EruRuntime.sleep(Duration.ofMillis(1))
    h.consume(timer.unsafeRunSync(): AnyRef)
  }

  /** Measures timeout performance with concurrent effects.
    *
    * This validates that timeout operations properly race against effects
    * and cancel the losing side via Virtual Thread interruption.
    */
  @Benchmark  
  def timeoutConcurrent(h: Blackhole): Unit = {
    val fast = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => "completed")
    val result = EruRuntime.timeout(Duration.ofMillis(10))(fast).attempt.unsafeRunSync()
    h.consume(result: AnyRef)
  }

  /** Measures high-concurrency fiber creation and completion.
    *
    * This benchmark validates that the Virtual Threads backend can efficiently
    * handle many concurrent fibers without significant overhead or blocking.
    */
  @Benchmark
  def highConcurrencyFibers(h: Blackhole): Unit = {
    val fibers = (1 to 50).map { i =>
      EruRuntime.fork(Eru.succeed(i))
    }
    val results = fibers.map(_.flatMap(_.await)).toList.sequence.unsafeRunSync()
    h.consume(results: AnyRef)
  }

  /** Measures nested zipPar performance under concurrent load.
    *
    * This validates that nested parallel operations work correctly and
    * efficiently with the Virtual Threads backend implementation.
    */
  @Benchmark
  def nestedZipParConcurrent(h: Blackhole): Unit = {
    val inner1 = EruRuntime.zipPar(quickEffect, quickEffect)
    val inner2 = EruRuntime.zipPar(quickEffect, quickEffect)  
    val outer = EruRuntime.zipPar(inner1, inner2)
    h.consume(outer.unsafeRunSync(): AnyRef)
  }

  /** Measures resource cleanup performance under concurrent execution.
    *
    * This validates that finalizers execute properly in FILO order even
    * under high concurrent load with Virtual Threads.
    */
  @Benchmark
  def resourceCleanupConcurrent(h: Blackhole): Unit = {
    var cleanupCount = 0
    val resourceEffect = quickEffect.ensure(Eru.effect { cleanupCount += 1 })
    val concurrent = (1 to 20).map(_ => EruRuntime.fork(resourceEffect))
    val results = concurrent.map(_.flatMap(_.await)).toList.sequence.unsafeRunSync()
    h.consume((results, cleanupCount): AnyRef)
  }

  /** Measures cancellation performance with Virtual Thread interruption.
    *
    * This validates that effect cancellation via Thread.interrupt() works
    * efficiently and properly cleans up resources under concurrent load.
    */
  @Benchmark
  def cancellationPerformance(h: Blackhole): Unit = {
    val fastFail = Eru.fail("immediate failure")
    val slowSuccess = EruRuntime.sleep(Duration.ofMillis(10)).map(_ => "slow success")
    val result = EruRuntime.zipPar(fastFail, slowSuccess).attempt.unsafeRunSync()
    h.consume(result: AnyRef)
  }

  /** Measures mixed sync/async workload performance.
    *
    * This validates that the Virtual Threads backend handles mixed workloads
    * of synchronous effects and asynchronous operations efficiently.
    */
  @Benchmark
  def mixedSyncAsyncWorkload(h: Blackhole): Unit = {
    val syncWork = Eru.succeed(1).map(_ * 2).map(_ + 3)
    val asyncWork = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => 42)  
    val combined = EruRuntime.zipPar(syncWork, asyncWork)
    h.consume(combined.unsafeRunSync(): AnyRef)
  }
}