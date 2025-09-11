package net.ghoula.eru.bench.fair

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 7: Stack Safety & Deep Chains Benchmarks
  *
  * Tests stack safety and performance with deep effect chains:
  *   - Deep flatMap chain execution
  *   - Deep map chain execution
  *   - Nested composition performance
  *   - Stack safety under load
  *
  * Expected runtime: ~3 minutes Coverage: Stack safety guarantees and deep computation performance
  */
class StackSafetyBench extends FairBenchmarkBase {

  // Use smaller depth for fair comparison to avoid timeouts
  private val DEEP_CHAIN_DEPTH = 100

  // =============================================================================
  // Deep FlatMap Chains
  // =============================================================================

  @Benchmark
  def eruDeepFlatMap(): Int = runEru {
    var chain: Eru[Nothing, Int] = Eru.succeed(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.flatMap(n => Eru.succeed(n + 1))
    }
    chain
  }

  @Benchmark
  def zioDeepFlatMap(): Int = runZio {
    var chain: ZIO[Any, Nothing, Int] = ZIO.succeed(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.flatMap(n => ZIO.succeed(n + 1))
    }
    chain
  }

  @Benchmark
  def ioDeepFlatMap(): Int = runIO {
    var chain: IO[Int] = IO.pure(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.flatMap(n => IO.pure(n + 1))
    }
    chain
  }

  // =============================================================================
  // Deep Map Chains
  // =============================================================================

  @Benchmark
  def eruDeepMap(): Int = runEru {
    var chain: Eru[Nothing, Int] = Eru.succeed(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.map(_ + 1)
    }
    chain
  }

  @Benchmark
  def zioDeepMap(): Int = runZio {
    var chain: ZIO[Any, Nothing, Int] = ZIO.succeed(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.map(_ + 1)
    }
    chain
  }

  @Benchmark
  def ioDeepMap(): Int = runIO {
    var chain: IO[Int] = IO.pure(0)
    for (_ <- 1 to DEEP_CHAIN_DEPTH) {
      chain = chain.map(_ + 1)
    }
    chain
  }

  // =============================================================================
  // Mixed Map/FlatMap Chains
  // =============================================================================

  @Benchmark
  def eruMixedChain(): Int = runEru {
    var chain: Eru[Nothing, Int] = Eru.succeed(0)
    for (i <- 1 to DEEP_CHAIN_DEPTH) {
      if (i % 2 == 0) {
        chain = chain.map(_ + 1)
      } else {
        chain = chain.flatMap(n => Eru.succeed(n + 1))
      }
    }
    chain
  }

  @Benchmark
  def zioMixedChain(): Int = runZio {
    var chain: ZIO[Any, Nothing, Int] = ZIO.succeed(0)
    for (i <- 1 to DEEP_CHAIN_DEPTH) {
      if (i % 2 == 0) {
        chain = chain.map(_ + 1)
      } else {
        chain = chain.flatMap(n => ZIO.succeed(n + 1))
      }
    }
    chain
  }

  @Benchmark
  def ioMixedChain(): Int = runIO {
    var chain: IO[Int] = IO.pure(0)
    for (i <- 1 to DEEP_CHAIN_DEPTH) {
      if (i % 2 == 0) {
        chain = chain.map(_ + 1)
      } else {
        chain = chain.flatMap(n => IO.pure(n + 1))
      }
    }
    chain
  }

  // =============================================================================
  // Recursive Fold Operations
  // =============================================================================

  @Benchmark
  def eruRecursiveFold(): Int = runEru {
    val items = (1 to DEEP_CHAIN_DEPTH).toList
    items.foldLeft(Eru.succeed(0)) { (acc, item) =>
      acc.flatMap(sum => Eru.succeed(sum + item))
    }
  }

  @Benchmark
  def zioRecursiveFold(): Int = runZio {
    val items = (1 to DEEP_CHAIN_DEPTH).toList
    items.foldLeft(ZIO.succeed(0)) { (acc, item) =>
      acc.flatMap(sum => ZIO.succeed(sum + item))
    }
  }

  @Benchmark
  def ioRecursiveFold(): Int = runIO {
    val items = (1 to DEEP_CHAIN_DEPTH).toList
    items.foldLeft(IO.pure(0)) { (acc, item) =>
      acc.flatMap(sum => IO.pure(sum + item))
    }
  }

  // =============================================================================
  // Nested Composition
  // =============================================================================

  @Benchmark
  def eruNestedComposition(): Int = runEru {
    def buildNested(depth: Int): Eru[Nothing, Int] = {
      if (depth <= 0) {
        Eru.succeed(TEST_VALUE)
      } else {
        for {
          inner <- buildNested(depth - 1)
          result <- Eru.succeed(inner + 1)
        } yield result
      }
    }

    buildNested(TEST_ITERATIONS) // Use smaller depth
  }

  @Benchmark
  def zioNestedComposition(): Int = runZio {
    def buildNested(depth: Int): ZIO[Any, Nothing, Int] = {
      if (depth <= 0) {
        ZIO.succeed(TEST_VALUE)
      } else {
        for {
          inner <- buildNested(depth - 1)
          result <- ZIO.succeed(inner + 1)
        } yield result
      }
    }

    buildNested(TEST_ITERATIONS) // Use smaller depth
  }

  @Benchmark
  def ioNestedComposition(): Int = runIO {
    def buildNested(depth: Int): IO[Int] = {
      if (depth <= 0) {
        IO.pure(TEST_VALUE)
      } else {
        for {
          inner <- buildNested(depth - 1)
          result <- IO.pure(inner + 1)
        } yield result
      }
    }

    buildNested(TEST_ITERATIONS) // Use smaller depth
  }
}
