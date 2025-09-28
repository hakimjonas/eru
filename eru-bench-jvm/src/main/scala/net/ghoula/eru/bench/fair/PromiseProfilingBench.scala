package net.ghoula.eru.bench.fair

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Detailed profiling benchmark to understand Promise/Deferred bottlenecks. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
class PromiseProfilingBench extends FairBenchmarkBase {

  private given eruRuntime: EruRuntime = EruRuntime.create()

  // Break down Promise operations to find bottlenecks

  // Step 1: Just Ref creation
  @Benchmark
  def refCreationOnly(): Ref[Int] = runEru {
    Eru.ref(0)
  }

  // Step 2: Promise creation (includes Ref)
  @Benchmark
  def promiseCreationOnly(): Promise[Nothing, Int] = runEru {
    Eru.promise[Nothing, Int]
  }

  // Step 3: Promise succeed (no await)
  @Benchmark
  def promiseSucceedNoAwait(): Boolean = runEru {
    for {
      p <- Eru.promise[Nothing, Int]
      result <- p.succeed(42).eru
    } yield result
  }

  // Step 4: Promise await on already completed
  private var completedPromise: Promise[Nothing, Int] = uninitialized

  @Setup
  def setup(): Unit = {
    completedPromise = runEru {
      for {
        p <- Eru.promise[Nothing, Int]
        _ <- p.succeed(42).eru
      } yield p
    }
  }

  @Benchmark
  def promiseAwaitAlreadyCompleted(): Int = runEru {
    completedPromise.await.eru
  }

  // Step 5: Full cycle (what we normally benchmark)
  @Benchmark
  def promiseFullCycle(): Int = runEru {
    for {
      p <- Eru.promise[Nothing, Int]
      _ <- p.succeed(42).eru
      result <- p.await.eru
    } yield result
  }

  // Step 6: Multiple promises created sequentially
  @Benchmark
  def threePromisesCreation(): (Promise[Nothing, Int], Promise[Nothing, Int], Promise[Nothing, Int]) = runEru {
    for {
      p1 <- Eru.promise[Nothing, Int]
      p2 <- Eru.promise[Nothing, Int]
      p3 <- Eru.promise[Nothing, Int]
    } yield (p1, p2, p3)
  }

  // Step 7: Multiple promises full cycle (current benchmark)
  @Benchmark
  def threePromisesFullCycle(): Int = runEru {
    for {
      p1 <- Eru.promise[Nothing, Int]
      p2 <- Eru.promise[Nothing, Int]
      p3 <- Eru.promise[Nothing, Int]
      _ <- p1.succeed(10).eru
      _ <- p2.succeed(20).eru
      _ <- p3.succeed(12).eru
      a <- p1.await.eru
      b <- p2.await.eru
      c <- p3.await.eru
    } yield a + b + c
  }

  // Compare with Deferred
  @Benchmark
  def deferredFullCycle(): Int = runEru {
    for {
      d <- Eru.deferred[Int]
      _ <- d.complete(42).eru
      result <- d.await.eru
    } yield result
  }

  // Test state access pattern
  @Benchmark
  def promiseStateCheck(): Boolean = runEru {
    for {
      p <- Eru.promise[Nothing, Int]
      _ <- p.succeed(42).eru
      isDone <- p.isDone.eru
    } yield isDone
  }
}
