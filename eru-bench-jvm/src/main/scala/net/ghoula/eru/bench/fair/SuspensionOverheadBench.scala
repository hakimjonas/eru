package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Isolated benchmark to measure Suspending/Immediate wrapper overhead. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class SuspensionOverheadBench extends FairBenchmarkBase {

  import scala.compiletime.uninitialized

  // Pre-created values to isolate the wrapper overhead
  private var plainEru: Eru[Nothing, Int] = uninitialized
  private var immediateWrapped: Immediate[Nothing, Int] = uninitialized
  private var suspendingWrapped: Suspending[Nothing, Int] = uninitialized

  @Setup
  def setup(): Unit = {
    plainEru = Eru.succeed(42)
    immediateWrapped = new Immediate(plainEru)
    suspendingWrapped = new Suspending(plainEru)
  }

  @Benchmark
  def plainEruAccess(): Eru[Nothing, Int] = {
    plainEru
  }

  @Benchmark
  def immediateEruAccess(): Eru[Nothing, Int] = {
    immediateWrapped.eru
  }

  @Benchmark
  def suspendingEruAccess(): Eru[Nothing, Int] = {
    suspendingWrapped.eru
  }

  @Benchmark
  def createImmediate(): Immediate[Nothing, Int] = {
    new Immediate(plainEru)
  }

  @Benchmark
  def createSuspending(): Suspending[Nothing, Int] = {
    new Suspending(plainEru)
  }

  @Benchmark
  def plainEruRun(): Int = runEru {
    plainEru
  }

  @Benchmark
  def immediateRun(): Int = runEru {
    immediateWrapped.eru
  }

  @Benchmark
  def suspendingRun(): Int = runEru {
    suspendingWrapped.eru
  }
}
