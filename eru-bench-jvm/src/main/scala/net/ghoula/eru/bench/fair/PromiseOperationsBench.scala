package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Isolated benchmarks for Promise/Deferred operations. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class PromiseOperationsBench extends FairBenchmarkBase {

  private given eruRuntime: EruRuntime = EruRuntime.create()

  // Just test the full cycle vs individual parts

  @Benchmark
  def eruPromiseFullCycle(): Int = runEru {
    for {
      p <- Eru.promise[Nothing, Int]
      _ <- p.succeed(42).eru
      result <- p.await.eru
    } yield result
  }

  @Benchmark
  def eruDeferredFullCycle(): Int = runEru {
    for {
      d <- Eru.deferred[Int]
      _ <- d.complete(42).eru
      result <- d.await.eru
    } yield result
  }

  @Benchmark
  def eruRefFullCycle(): Int = runEru {
    for {
      ref <- Eru.ref(0)
      _ <- ref.set(42)
      result <- ref.get
    } yield result
  }

  @Benchmark
  def eruPromiseCreateOnly(): Promise[Nothing, Int] = runEru {
    Eru.promise[Nothing, Int]
  }

  @Benchmark
  def eruDeferredCreateOnly(): Deferred[Int] = runEru {
    Eru.deferred[Int]
  }

  @Benchmark
  def eruRefCreateOnly(): Ref[Int] = runEru {
    Eru.ref(0)
  }
}
