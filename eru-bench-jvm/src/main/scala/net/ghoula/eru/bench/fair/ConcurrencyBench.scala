package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.syntax.all.*
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 5: Concurrency & Parallelism Benchmarks
  *
  * Tests concurrent and parallel execution patterns:
  *   - Race operations with multiple contestants
  *   - Fork/await basic patterns
  *   - Parallel composition (zipPar)
  *   - Concurrent effect coordination
  *
  * Expected runtime: ~5 minutes Coverage: Concurrent execution and parallel computation primitives
  */
class ConcurrencyBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Race Operations
  // =============================================================================

  @Benchmark
  def eruRaceBasic(): String = runEru {
    val fast = Eru.succeed("fast")
    val slow = Eru.succeed("slow")
    fast.race(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def zioRaceBasic(): String = runZio {
    val fast = ZIO.succeed("fast")
    val slow = ZIO.succeed("slow")
    fast.raceEither(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def ioRaceBasic(): String = runIO {
    val fast = IO.pure("fast")
    val slow = IO.pure("slow")
    IO.race(fast, slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  // =============================================================================
  // Fork/Await Patterns
  // =============================================================================

  @Benchmark
  def eruForkAwait(): Int = runEru {
    for {
      fiber <- Eru.succeed(TEST_VALUE).fork
      result <- fiber.await.flatMap(Eru.fromExit(_))
    } yield result
  }

  @Benchmark
  def zioForkAwait(): Int = runZio {
    for {
      fiber <- ZIO.succeed(TEST_VALUE).fork
      exit <- fiber.await
      result <- exit match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
    } yield result
  }

  @Benchmark
  def ioForkAwait(): Int = runIO {
    for {
      fiber <- IO.pure(TEST_VALUE).start
      result <- fiber.joinWithNever
    } yield result
  }

  // =============================================================================
  // Parallel Composition (zipPar)
  // =============================================================================

  @Benchmark
  def eruZipPar(): Int = runEru {
    val left = Eru.succeed(10)
    val right = Eru.succeed(20)
    left.zipPar(right).map { case (a, b) => a + b + 12 }
  }

  @Benchmark
  def zioZipPar(): Int = runZio {
    val left = ZIO.succeed(10)
    val right = ZIO.succeed(20)
    left.zipPar(right).map { case (a, b) => a + b + 12 }
  }

  @Benchmark
  def ioZipPar(): Int = runIO {
    val left = IO.pure(10)
    val right = IO.pure(20)
    (left, right).parTupled.map { case (a, b) => a + b + 12 }
  }

  // =============================================================================
  // Multiple Fork/Await
  // =============================================================================

  @Benchmark
  def eruMultipleFork(): Int = runEru {
    for {
      fiber1 <- Eru.succeed(10).fork
      fiber2 <- Eru.succeed(20).fork
      fiber3 <- Eru.succeed(12).fork
      result1 <- fiber1.await.flatMap(Eru.fromExit(_))
      result2 <- fiber2.await.flatMap(Eru.fromExit(_))
      result3 <- fiber3.await.flatMap(Eru.fromExit(_))
      total <- Eru.succeed(result1 + result2 + result3)
    } yield total
  }

  @Benchmark
  def zioMultipleFork(): Int = runZio {
    for {
      fiber1 <- ZIO.succeed(10).fork
      fiber2 <- ZIO.succeed(20).fork
      fiber3 <- ZIO.succeed(12).fork
      exit1 <- fiber1.await
      exit2 <- fiber2.await
      exit3 <- fiber3.await
      result1 <- exit1 match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
      result2 <- exit2 match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
      result3 <- exit3 match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
      total <- ZIO.succeed(result1 + result2 + result3)
    } yield total
  }

  @Benchmark
  def ioMultipleFork(): Int = runIO {
    for {
      fiber1 <- IO.pure(10).start
      fiber2 <- IO.pure(20).start
      fiber3 <- IO.pure(12).start
      result1 <- fiber1.joinWithNever
      result2 <- fiber2.joinWithNever
      result3 <- fiber3.joinWithNever
      total <- IO.pure(result1 + result2 + result3)
    } yield total
  }

  // =============================================================================
  // Complex Parallel Composition
  // =============================================================================

  @Benchmark
  def eruComplexParallel(): Int = runEru {
    val effects = (1 to TEST_ITERATIONS).map(i => Eru.succeed(i))
    val combined = effects.foldLeft(Eru.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def zioComplexParallel(): Int = runZio {
    val effects = (1 to TEST_ITERATIONS).map(i => ZIO.succeed(i))
    val combined = effects.foldLeft(ZIO.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def ioComplexParallel(): Int = runIO {
    val effects = (1 to TEST_ITERATIONS).map(i => IO.pure(i))
    val combined = effects.foldLeft(IO.pure(0)) { (acc, eff) =>
      // Use parMapN to match the zipPar semantics of Eru/ZIO
      (acc, eff).parMapN((sum, value) => sum + value)
    }
    combined
  }
}
