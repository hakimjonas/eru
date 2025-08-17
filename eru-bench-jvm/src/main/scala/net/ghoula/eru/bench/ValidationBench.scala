package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.Eru

/** JMH validation benchmarks to test JVM optimization behavior.
  *
  * These benchmarks help validate that the JVM's optimizations (Dead Code Elimination, Constant
  * Folding, etc.) are working as expected and that our main benchmarks are actually measuring real
  * work rather than being optimized away.
  *
  * According to JMH recommendations, these validation benchmarks should show:
  *   - Dead code elimination: Near-zero execution time when results aren't consumed
  *   - Constant folding: Very fast execution when operations can be folded to constants
  *   - Proper blackhole usage: Significant difference when blackhole is used vs not used
  *
  * Run with: sbt "project eruBenchJVM; jmh:run .*ValidationBench.*"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class ValidationBench {

  /** Test for Dead Code Elimination - this should be optimized to near-zero time.
    *
    * This benchmark creates Eru effects and compositions but doesn't consume their results. If Dead
    * Code Elimination is working properly, the JVM should optimize away most or all of this work,
    * resulting in very fast execution times.
    *
    * If this benchmark shows significant execution time, it indicates that either DCE isn't working
    * or our effects have unavoidable side effects.
    */
  @Benchmark
  def deadCodeEliminationTest(): Unit = {
    // Create complex effects but don't consume results - should be eliminated
    val unused1 = Eru.succeed(42).map(_ + 1).map(_ * 2).flatMap(x => Eru.succeed(x + 10))
    val unused2 = Eru.succeed("hello").map(_.toUpperCase).flatMap(s => Eru.succeed(s + " world"))
    val unused3: Eru[Nothing, String] = unused1.zip(unused2).map { case (i, s) => s"$i: $s" }

    // Don't consume any results - JVM should eliminate all this work
    // Prevent unused variable warnings - these are intentionally unused for DCE testing
    val _ = (unused1, unused2, unused3)
  }

  /** Test for Dead Code Elimination with controlled consumption.
    *
    * This is the control for the deadCodeEliminationTest. It does the same work but consumes the
    * result through blackhole. The difference between this and deadCodeEliminationTest shows how
    * much work the JVM eliminated.
    */
  @Benchmark
  def deadCodeEliminationControl(h: Blackhole): Unit = {
    // Same work as deadCodeEliminationTest but consume results
    val result1 = Eru.succeed(42).map(_ + 1).map(_ * 2).flatMap(x => Eru.succeed(x + 10)).unsafeRunSync()
    val result2 = Eru.succeed("hello").map(_.toUpperCase).flatMap(s => Eru.succeed(s + " world")).unsafeRunSync()
    val result3 = Eru.succeed(result1).zip(Eru.succeed(result2)).map { case (i, s) => s"$i: $s" }.unsafeRunSync()

    h.consume(result3)
  }

  /** Test for Constant Folding - this should be very fast due to compile-time optimization.
    *
    * This benchmark performs operations that should be foldable to constants at compile time. If
    * constant folding is working, this should execute much faster than equivalent dynamic
    * computation.
    */
  @Benchmark
  def constantFoldingTest(h: Blackhole): Unit = {
    // These operations should be foldable to constants
    val constant1 = Eru.succeed(42).map(_ + 0).map(identity).unsafeRunSync()
    val constant2 = Eru.succeed(10).map(_ * 1).map(_ + 0).unsafeRunSync()
    val constant3 = Eru.succeed("hello").map(identity).map(_.toString).unsafeRunSync()

    h.consume((constant1, constant2, constant3))
  }

  /** Test for Constant Folding control with dynamic values.
    *
    * This benchmark performs similar operations to constantFoldingTest but with dynamic values that
    * can't be folded at compile time. The difference shows how much constant folding helped in the
    * previous test.
    */
  @Benchmark
  def constantFoldingControl(h: Blackhole): Unit = {
    // Use System.nanoTime() to prevent constant folding
    val dynamic = (System.nanoTime() % 100).toInt
    val zero = dynamic - dynamic // Dynamic zero
    val one = (dynamic / dynamic) // Dynamic one

    val result1 = Eru.succeed(42).map(_ + zero).map(x => x).unsafeRunSync()
    val result2 = Eru.succeed(10).map(_ * one).map(_ + zero).unsafeRunSync()
    val result3 = Eru.succeed("hello").map(x => x).map(_.toString).unsafeRunSync()

    h.consume((result1, result2, result3))
  }

  /** Test blackhole effectiveness - without blackhole consumption.
    *
    * This benchmark performs meaningful work but doesn't consume the results through blackhole. If
    * blackholes are necessary, this should be much faster than the blackhole version due to dead
    * code elimination.
    */
  @Benchmark
  def blackholeValidationWithoutConsumption(): Int = {
    val program = Eru
      .succeed(0)
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))

    program.unsafeRunSync() // Return result but caller might not consume it
  }

  /** Test blackhole effectiveness - with blackhole consumption.
    *
    * This benchmark performs the same work as blackholeValidationWithoutConsumption but consumes
    * the result through blackhole. The difference shows whether blackhole consumption is necessary
    * to prevent optimization.
    */
  @Benchmark
  def blackholeValidationWithConsumption(h: Blackhole): Unit = {
    val program = Eru
      .succeed(0)
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))

    val result = program.unsafeRunSync()
    h.consume(result) // Explicit blackhole consumption
  }

  /** Test for loop unrolling and optimization behavior.
    *
    * This benchmark tests how the JVM handles repetitive operations. It helps validate that our
    * main benchmarks with repeated operations are measuring realistic performance rather than
    * over-optimized scenarios.
    */
  @Benchmark
  def loopOptimizationTest(h: Blackhole): Unit = {
    var acc = Eru.succeed(0)

    // Simple loop that might be unrolled
    for (i <- 1 to 10) {
      acc = acc.flatMap(x => Eru.succeed(x + i))
    }

    val result = acc.unsafeRunSync()
    h.consume(result)
  }

  /** Test allocation vs computation balance.
    *
    * This benchmark helps understand whether performance differences in main benchmarks are due to
    * computation overhead or allocation patterns.
    */
  @Benchmark
  def allocationVsComputationTest(h: Blackhole): Unit = {
    // Heavy allocation, light computation
    val effects = (1 to 10).map(i => Eru.succeed(i)).toList

    // Chain effects with flatMap to create allocation overhead
    val combined = effects.foldLeft(Eru.succeed(0)) { (acc, effect) =>
      acc.flatMap(sum => effect.map(value => sum + value))
    }

    val result = combined.unsafeRunSync()
    h.consume(result)
  }
}
