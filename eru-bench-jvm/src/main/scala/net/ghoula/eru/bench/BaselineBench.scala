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
  *   - Simple value operations for comparison
  *
  * These baselines help validate that other benchmarks are measuring real work and not being
  * optimized away by the JVM.
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

  /** Measures absolute minimum JMH overhead.
    *
    * This benchmark does the absolute minimum amount of work possible - just consuming a constant
    * value through the blackhole. This establishes the baseline overhead of the JMH framework
    * itself.
    */
  @Benchmark
  def absoluteBaseline(h: Blackhole): Unit = {
    h.consume(42)
  }

  /** Measures raw function composition overhead without any effects.
    *
    * This benchmark chains 10 simple functions together without using any effect system. This
    * provides a baseline for what raw function composition costs, which we can compare against
    * Eru's map chains.
    */
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

    val result = composed(0)
    h.consume(result)
  }

  /** Measures raw computation without function composition.
    *
    * This benchmark performs the same arithmetic operations as rawFunctionComposition but without
    * function composition overhead. This helps isolate the cost of function composition itself.
    */
  @Benchmark
  def rawComputation(h: Blackhole): Unit = {
    var x = 0
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    x = (x + 1) * 2
    h.consume(x)
  }

  /** Measures simple Eru effect creation overhead.
    *
    * This benchmark creates and executes a simple Eru.succeed effect without any composition. This
    * establishes the baseline overhead of the Eru effect system for the simplest possible
    * operation.
    */
  @Benchmark
  def simpleEruBaseline(h: Blackhole): Unit = {
    val result = Eru.succeed(42).unsafeRunSync()
    h.consume(result)
  }

  /** Measures Eru map chain vs equivalent raw function composition.
    *
    * This benchmark creates a 10-deep map chain in Eru and compares it to the raw function
    * composition baseline. This helps validate that Eru's map optimization is working effectively.
    */
  @Benchmark
  def eruMapChainBaseline(h: Blackhole): Unit = {
    val program = Eru
      .succeed(0)
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 1)
      .map(_ * 2)

    val result = program.unsafeRunSync()
    h.consume(result)
  }

  /** Measures object allocation overhead.
    *
    * This benchmark creates and consumes simple objects to establish the baseline cost of object
    * allocation, which can help interpret other benchmark results in terms of allocation overhead.
    */
  @Benchmark
  def allocationBaseline(h: Blackhole): Unit = {
    val obj = new Object()
    val tuple = (42, "hello", obj)
    val list = List(1, 2, 3, 4, 5)
    h.consume((tuple, list))
  }
}
