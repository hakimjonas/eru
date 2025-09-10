package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import zio.{Unsafe, ZIO}

import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import net.ghoula.eru.EruRuntime

/** Competitive benchmarks comparing Eru's race performance against ZIO.
  *
  * This benchmark suite measures the throughput performance of race operations to evaluate how Eru
  * handles concurrent execution and cancellation compared to ZIO's race implementations. The
  * benchmarks focus specifically on race semantics with varying numbers of competing effects.
  *
  * ==Benchmark Methodology==
  *
  * Each benchmark measures race operations with fast-winning effects to test cancellation
  * efficiency and concurrent execution overhead. The benchmarks use consistent timing patterns
  * where one effect wins consistently (1ms) while others are cancelled at various delays.
  *
  * ==Effect Count Parameters==
  *
  * Tests are conducted with 4, 8, and 16 competing effects to measure how race performance scales
  * with the number of concurrent operations. This tests both the fork/join efficiency and
  * cancellation propagation performance.
  *
  * ==Architectural Insights==
  *
  * This benchmark reveals differences in concurrent execution and cancellation strategies:
  *
  * '''Eru:''' Native raceAll implementation with efficient cancellation propagation and direct
  * result indexing for winner identification.
  *
  * '''ZIO:''' Uses native ZIO.raceAll for fair comparison, testing ZIO's concurrent execution and
  * fiber cancellation efficiency.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class CompetitiveRaceBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  /** The number of competing effects parameter for raceAll benchmarks.
    *
    * Values represent the number of concurrent effects racing against each other, testing race
    * performance scaling as concurrency increases.
    */
  @Param(Array("4", "8", "16"))
  var effectCount: Int = uninitialized

  /** Benchmarks Eru's race performance with fast vs slow effects.
    *
    * This benchmark measures Eru's race operation with one fast effect (1ms) and one slow effect
    * (20ms), testing cancellation efficiency and concurrent execution.
    *
    * @return
    *   the result from the winning effect
    */
  @Benchmark
  def eruRace(): Either[String, String] = {
    val fast = runtime.sleep(Duration.ofMillis(1)).map(_ => "fast")
    val slow = runtime.sleep(Duration.ofMillis(20)).map(_ => "slow")
    runtime.race(fast, slow).unsafeRunSync()
  }

  /** Benchmarks ZIO's race performance with fast vs slow effects.
    *
    * This benchmark measures ZIO's race operation with equivalent fast and slow effects, providing
    * comparable semantics to the Eru race benchmark.
    *
    * @return
    *   the result from the winning effect
    */
  @Benchmark
  def zioRace(): Either[String, String] = {
    val fast = ZIO.sleep(java.time.Duration.ofMillis(1)).as("fast")
    val slow = ZIO.sleep(java.time.Duration.ofMillis(20)).as("slow")

    val raced = fast.raceEither(slow)
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(raced).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Eru's raceAll performance with multiple competing effects.
    *
    * This benchmark measures Eru's raceAll operation with multiple effects of varying durations,
    * testing the efficiency of concurrent execution and cancellation behavior. The fastest effect
    * (1ms) should win consistently, with all losing effects being cancelled properly. The number of
    * competing effects is controlled by the effectCount parameter.
    *
    * @return
    *   the winning result as a String
    */
  @Benchmark
  def eruRaceAll(): String = {
    val effects = (0 until effectCount).map { i =>
      val delay = if (i == 1) 1 else 10 + i * 5 // Effect at index 1 wins with 1ms delay
      runtime.sleep(Duration.ofMillis(delay)).map(_ => s"effect-$i")
    }.toList

    runtime.raceAll(effects).unsafeRunSync()._1
  }

  /** Benchmarks ZIO's raceAll performance with multiple competing effects.
    *
    * This benchmark measures ZIO's native raceAll operation with multiple effects of varying
    * durations, providing direct comparison to Eru's raceAll implementation. Uses ZIO's built-in
    * raceAll method for fair performance comparison.
    *
    * @return
    *   the winning result as a String
    */
  @Benchmark
  def zioRaceAll(): String = {
    val effects = (0 until effectCount).map { i =>
      val delay = if (i == 1) 1 else 10 + i * 5 // Effect at index 1 wins with 1ms delay
      ZIO.sleep(java.time.Duration.ofMillis(delay)).as(s"effect-$i")
    }.toList

    val raced = ZIO.raceAll(effects.head, effects.tail)
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(raced).getOrThrowFiberFailure()
    }
  }
}
