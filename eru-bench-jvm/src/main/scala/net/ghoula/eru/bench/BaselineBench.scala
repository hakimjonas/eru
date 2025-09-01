package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.CorePrelude.*

/** JMH baseline benchmarks to establish minimum overhead measurements.
  *
  * These benchmarks provide baseline measurements for:
  *   - JMH framework overhead (absolute minimum work)
  *   - Raw function composition without Eru effects
  *   - Simple Eru effect creation and execution
  *   - Raw JVM allocation overhead
  *
  * Run with: sbt "project eruBenchJVM; jmh:run .*BaselineBench.*"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class BaselineBench {

  /** Absolute baseline: empty benchmark to measure JMH harness overhead. */
  @Benchmark
  def absoluteBaseline(): Unit = ()

  /** Raw function composition of simple Int => Int functions. */
  @Benchmark
  def rawFunctionComposition(h: Blackhole): Unit = {
    val f1: Int => Int = _ + 1
    val f2: Int => Int = _ * 2
    val f3: Int => Int = _ + 1
    val f4: Int => Int = _ * 2
    val f5: Int => Int = _ + 1

    val composed = f1
      .andThen(f2)
      .andThen(f3)
      .andThen(f4)
      .andThen(f5)
      .andThen(f1)
      .andThen(f2)
      .andThen(f3)
      .andThen(f4)
      .andThen(f5)

    h.consume(composed(0))
  }

  /** Simple Eru effect creation and execution baseline. */
  @Benchmark
  def simpleEruBaseline(h: Blackhole): Unit = {
    val result = Eru.succeed(42).unsafeRunSync()
    h.consume(result)
  }

  /** Raw allocation baseline: allocate a single object and consume it. */
  @Benchmark
  def allocationBaseline(h: Blackhole): Unit = {
    h.consume(new Object())
  }
}
