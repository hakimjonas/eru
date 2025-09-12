package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.implicits.*
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 3: State Management Benchmarks
  *
  * Tests concurrent state management operations:
  *   - Ref creation and basic operations
  *   - Atomic updates and modifications
  *   - State transformation patterns
  *   - Sequential state access patterns
  *
  * Expected runtime: ~3 minutes Coverage: Concurrent state primitives and atomic operations
  */
class StateManagementBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Ref Operations
  // =============================================================================

  @Benchmark
  def eruRefBasic(): Int = runEru {
    for {
      ref <- Eru.ref(0)
      _ <- ref.set(TEST_VALUE)
      result <- ref.get
    } yield result
  }

  @Benchmark
  def zioRefBasic(): Int = runZio {
    for {
      ref <- zio.Ref.make(0)
      _ <- ref.set(TEST_VALUE)
      result <- ref.get
    } yield result
  }

  @Benchmark
  def ioRefBasic(): Int = runIO {
    for {
      ref <- cats.effect.Ref[IO].of(0)
      _ <- ref.set(TEST_VALUE)
      result <- ref.get
    } yield result
  }

  // =============================================================================
  // Atomic Update Operations
  // =============================================================================

  @Benchmark
  def eruRefUpdate(): Int = runEru {
    for {
      ref <- Eru.ref(0)
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      _ <- ref.update(_ + 12)
      result <- ref.get
    } yield result
  }

  @Benchmark
  def zioRefUpdate(): Int = runZio {
    for {
      ref <- zio.Ref.make(0)
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      _ <- ref.update(_ + 12)
      result <- ref.get
    } yield result
  }

  @Benchmark
  def ioRefUpdate(): Int = runIO {
    for {
      ref <- cats.effect.Ref[IO].of(0)
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      _ <- ref.update(_ + 12)
      result <- ref.get
    } yield result
  }

  // =============================================================================
  // Modify with Return Value
  // =============================================================================

  @Benchmark
  def eruRefModify(): Int = runEru {
    for {
      ref <- Eru.ref(TEST_VALUE)
      result <- ref.modify(n => (n * 2, n * 2))
    } yield result
  }

  @Benchmark
  def zioRefModify(): Int = runZio {
    for {
      ref <- zio.Ref.make(TEST_VALUE)
      result <- ref.modify(n => (n * 2, n * 2))
    } yield result
  }

  @Benchmark
  def ioRefModify(): Int = runIO {
    for {
      ref <- cats.effect.Ref[IO].of(TEST_VALUE)
      result <- ref.modify(n => (n * 2, n * 2))
    } yield result
  }

  // =============================================================================
  // Multiple Refs Coordination
  // =============================================================================

  @Benchmark
  def eruMultipleRefs(): Int = runEru {
    for {
      ref1 <- Eru.ref(10)
      ref2 <- Eru.ref(20)
      ref3 <- Eru.ref(12)
      _ <- ref1.update(_ + 5)
      _ <- ref2.update(_ + 5)
      _ <- ref3.update(_ + 5)
      a <- ref1.get
      b <- ref2.get
      c <- ref3.get
      result <- Eru.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def zioMultipleRefs(): Int = runZio {
    for {
      ref1 <- zio.Ref.make(10)
      ref2 <- zio.Ref.make(20)
      ref3 <- zio.Ref.make(12)
      _ <- ref1.update(_ + 5)
      _ <- ref2.update(_ + 5)
      _ <- ref3.update(_ + 5)
      a <- ref1.get
      b <- ref2.get
      c <- ref3.get
      result <- ZIO.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def ioMultipleRefs(): Int = runIO {
    for {
      ref1 <- cats.effect.Ref[IO].of(10)
      ref2 <- cats.effect.Ref[IO].of(20)
      ref3 <- cats.effect.Ref[IO].of(12)
      _ <- ref1.update(_ + 5)
      _ <- ref2.update(_ + 5)
      _ <- ref3.update(_ + 5)
      a <- ref1.get
      b <- ref2.get
      c <- ref3.get
      result <- IO.pure(a + b + c)
    } yield result
  }

  // =============================================================================
  // Ref with Complex State Updates
  // =============================================================================

  @Benchmark
  def eruRefComplexUpdate(): Int = runEru {
    for {
      ref <- Eru.ref(1)
      _ <- (1 to TEST_ITERATIONS).foldLeft(Eru.unit) { (acc, n) =>
        acc.flatMap(_ => ref.update(_ + n).map(_ => ()))
      }
      result <- ref.get
    } yield result
  }

  @Benchmark
  def zioRefComplexUpdate(): Int = runZio {
    for {
      ref <- zio.Ref.make(1)
      _ <- ZIO.foreachDiscard(1 to TEST_ITERATIONS)(n => ref.update(_ + n))
      result <- ref.get
    } yield result
  }

  @Benchmark
  def ioRefComplexUpdate(): Int = runIO {
    for {
      ref <- cats.effect.Ref[IO].of(1)
      _ <- (1 to TEST_ITERATIONS).toList.traverse_(n => ref.update(_ + n))
      result <- ref.get
    } yield result
  }
}
