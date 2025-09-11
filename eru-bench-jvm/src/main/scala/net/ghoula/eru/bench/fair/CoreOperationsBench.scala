package net.ghoula.eru.bench.fair

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 1: Core Operations Benchmarks
  * 
  * Tests fundamental effect system operations:
  * - Basic effect creation (succeed, pure)
  * - Effect composition (flatMap, map) 
  * - Effect chaining and transformation
  * - Sequential execution patterns
  * 
  * Expected runtime: ~2 minutes
  * Coverage: The most basic building blocks of effect systems
  */
class CoreOperationsBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Effect Creation
  // =============================================================================

  @Benchmark
  def eruSucceed(): Int = runEru(Eru.succeed(TEST_VALUE))

  @Benchmark  
  def zioSucceed(): Int = runZio(ZIO.succeed(TEST_VALUE))

  @Benchmark
  def ioSucceed(): Int = runIO(IO.pure(TEST_VALUE))

  // =============================================================================
  // Single Map Operation
  // =============================================================================

  @Benchmark
  def eruMap(): Int = runEru(Eru.succeed(21).map(_ * 2))

  @Benchmark
  def zioMap(): Int = runZio(ZIO.succeed(21).map(_ * 2))

  @Benchmark
  def ioMap(): Int = runIO(IO.pure(21).map(_ * 2))

  // =============================================================================
  // Single FlatMap Operation  
  // =============================================================================

  @Benchmark
  def eruFlatMap(): Int = runEru(Eru.succeed(21).flatMap(n => Eru.succeed(n * 2)))

  @Benchmark
  def zioFlatMap(): Int = runZio(ZIO.succeed(21).flatMap(n => ZIO.succeed(n * 2)))

  @Benchmark
  def ioFlatMap(): Int = runIO(IO.pure(21).flatMap(n => IO.pure(n * 2)))

  // =============================================================================
  // Effect Chaining (Multiple Operations)
  // =============================================================================

  @Benchmark
  def eruChain(): Int = runEru {
    for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20) 
      c <- Eru.succeed(12)
      result <- Eru.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def zioChain(): Int = runZio {
    for {
      a <- ZIO.succeed(10)
      b <- ZIO.succeed(20)
      c <- ZIO.succeed(12) 
      result <- ZIO.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def ioChain(): Int = runIO {
    for {
      a <- IO.pure(10)
      b <- IO.pure(20)
      c <- IO.pure(12)
      result <- IO.pure(a + b + c)
    } yield result
  }

  // =============================================================================
  // Longer Chain (10 operations)
  // =============================================================================

  @Benchmark
  def eruLongChain(): Int = runEru {
    (1 to TEST_ITERATIONS).foldLeft(Eru.succeed(0)) { (acc, n) =>
      acc.flatMap(sum => Eru.succeed(sum + n))
    }
  }

  @Benchmark
  def zioLongChain(): Int = runZio {
    (1 to TEST_ITERATIONS).foldLeft(ZIO.succeed(0)) { (acc, n) =>
      acc.flatMap(sum => ZIO.succeed(sum + n))
    }
  }

  @Benchmark
  def ioLongChain(): Int = runIO {
    (1 to TEST_ITERATIONS).foldLeft(IO.pure(0)) { (acc, n) =>
      acc.flatMap(sum => IO.pure(sum + n))
    }
  }
}