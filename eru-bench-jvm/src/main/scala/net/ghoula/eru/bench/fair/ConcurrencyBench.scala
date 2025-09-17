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
  // Complex Parallel Composition (FAIR: Bulk parallel operations)
  // =============================================================================

  @Benchmark
  def eruComplexParallel(): Int = runEru {
    val effects = (1 to TEST_ITERATIONS).map(i => Eru.succeed(i)).toList
    runtime.parSequence(effects).map(_.sum)
  }

  @Benchmark
  def zioComplexParallel(): Int = runZio {
    val effects = (1 to TEST_ITERATIONS).map(i => ZIO.succeed(i))
    ZIO.collectAllPar(effects).map(_.sum)
  }

  @Benchmark
  def ioComplexParallel(): Int = runIO {
    val effects = (1 to TEST_ITERATIONS).map(i => IO.pure(i)).toList
    effects.parSequence.map(_.sum)
  }

  // =============================================================================
  // RaceAll Operations
  // =============================================================================

  @Benchmark
  def eruRaceAll(): (Int, Int) = runEru {
    val effects = List(
      Eru.succeed(10),
      Eru.succeed(20),
      Eru.succeed(30)
    )
    runtime.raceAll(effects)
  }

  @Benchmark
  def zioRaceAll(): (Int, Int) = runZio {
    val effects = List(
      ZIO.succeed(10),
      ZIO.succeed(20),
      ZIO.succeed(30)
    )
    ZIO
      .raceAll(effects(0), effects.tail)
      .map(value => (value, 0)) // ZIO doesn't return index, simulate structure
  }

  @Benchmark
  def ioRaceAll(): (Int, Int) = runIO {
    val effects = List(
      IO.pure(10),
      IO.pure(20),
      IO.pure(30)
    )
    // Cats Effect doesn't have raceAll, simulate with nested races
    IO.race(effects(0), IO.race(effects(1), effects(2))).map {
      case Left(v) => (v, 0)
      case Right(Left(v)) => (v, 1)
      case Right(Right(v)) => (v, 2)
    }
  }

  // =============================================================================
  // Timeout Operations
  // =============================================================================

  @Benchmark
  def eruTimeout(): Int = runEru {
    Eru.succeed(TEST_VALUE).timeout(java.time.Duration.ofSeconds(1))
  }

  @Benchmark
  def zioTimeout(): Int = runZio {
    ZIO
      .succeed(TEST_VALUE)
      .timeout(zio.Duration.fromSeconds(1))
      .map(_.getOrElse(0)) // Handle timeout as None
  }

  @Benchmark
  def ioTimeout(): Int = runIO {
    IO.pure(TEST_VALUE).timeout(scala.concurrent.duration.Duration(1, "second"))
  }

  // =============================================================================
  // Direct zipPar Chaining (Unfavorable pattern comparison)
  // =============================================================================

  @Benchmark
  def eruZipParChaining(): Int = runEru {
    val effects = (1 to TEST_ITERATIONS).map(i => Eru.succeed(i))
    val combined = effects.foldLeft(Eru.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def zioZipParChaining(): Int = runZio {
    val effects = (1 to TEST_ITERATIONS).map(i => ZIO.succeed(i))
    val combined = effects.foldLeft(ZIO.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def ioZipParChaining(): Int = runIO {
    val effects = (1 to TEST_ITERATIONS).map(i => IO.pure(i))
    val combined = effects.foldLeft(IO.pure(0)) { (acc, eff) =>
      (acc, eff).parMapN((sum, value) => sum + value)
    }
    combined
  }
}
