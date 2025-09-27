package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.syntax.all.*
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Pure Value Optimization Benchmarks
  *
  * These benchmarks specifically test how well frameworks optimize operations on already-computed
  * pure values. This is a valid optimization scenario that occurs in real code when combining
  * pre-computed results, but should not be confused with actual concurrent execution benchmarks.
  *
  * IMPORTANT: These benchmarks favor frameworks that detect and optimize pure values. Results
  * should be interpreted with this context in mind.
  */
class PureValueOptimizationBench extends FairBenchmarkBase {

  // =============================================================================
  // Race with Pure Values (tests optimization, not concurrency)
  // =============================================================================

  @Benchmark
  def eruRacePureValues(): String = runEru {
    val fast = Eru.succeed("fast") // Pure value - no effect
    val slow = Eru.succeed("slow") // Pure value - no effect
    fast.race(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def zioRacePureValues(): String = runZio {
    val fast = ZIO.succeed("fast") // Pure value - no effect
    val slow = ZIO.succeed("slow") // Pure value - no effect
    fast.raceEither(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def ioRacePureValues(): String = runIO {
    val fast = IO.pure("fast") // Pure value - no effect
    val slow = IO.pure("slow") // Pure value - no effect
    IO.race(fast, slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  // =============================================================================
  // ZipPar with Pure Values (tests optimization, not parallelism)
  // =============================================================================

  @Benchmark
  def eruZipParPureValues(): Int = runEru {
    val left = Eru.succeed(10) // Pure value - no effect
    val right = Eru.succeed(20) // Pure value - no effect
    left.zipPar(right).map { case (a, b) => a + b + 12 }
  }

  @Benchmark
  def zioZipParPureValues(): Int = runZio {
    val left = ZIO.succeed(10) // Pure value - no effect
    val right = ZIO.succeed(20) // Pure value - no effect
    left.zipPar(right).map { case (a, b) => a + b + 12 }
  }

  @Benchmark
  def ioZipParPureValues(): Int = runIO {
    val left = IO.pure(10) // Pure value - no effect
    val right = IO.pure(20) // Pure value - no effect
    (left, right).parTupled.map { case (a, b) => a + b + 12 }
  }

  // =============================================================================
  // Fork/Await with Pure Values (tests optimization of pre-computed results)
  // =============================================================================

  @Benchmark
  def eruForkAwaitPureValues(): Int = runEru {
    for {
      fiber <- Eru.succeed(TEST_VALUE).fork // Forking a pure value
      result <- fiber.await.flatMap(Eru.fromExit(_))
    } yield result
  }

  @Benchmark
  def zioForkAwaitPureValues(): Int = runZio {
    for {
      fiber <- ZIO.succeed(TEST_VALUE).fork // Forking a pure value
      exit <- fiber.await
      result <- exit match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
    } yield result
  }

  @Benchmark
  def ioForkAwaitPureValues(): Int = runIO {
    for {
      fiber <- IO.pure(TEST_VALUE).start // Starting with a pure value
      result <- fiber.joinWithNever
    } yield result
  }

  // =============================================================================
  // ZipPar Chaining with Pure Values (tests repeated optimization)
  // =============================================================================

  @Benchmark
  def eruZipParChainingPureValues(): Int = runEru {
    val effects = (1 to TEST_ITERATIONS).map(i => Eru.succeed(i))
    val combined = effects.foldLeft(Eru.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def zioZipParChainingPureValues(): Int = runZio {
    val effects = (1 to TEST_ITERATIONS).map(i => ZIO.succeed(i))
    val combined = effects.foldLeft(ZIO.succeed(0)) { (acc, eff) =>
      acc.zipPar(eff).map { case (sum, value) => sum + value }
    }
    combined
  }

  @Benchmark
  def ioZipParChainingPureValues(): Int = runIO {
    val effects = (1 to TEST_ITERATIONS).map(i => IO.pure(i))
    val combined = effects.foldLeft(IO.pure(0)) { (acc, eff) =>
      (acc, eff).parMapN((sum, value) => sum + value)
    }
    combined
  }

  // =============================================================================
  // Parallel Sequence with Pure Values (tests batch optimization)
  // =============================================================================

  @Benchmark
  def eruParSequencePureValues(): Int = runEru {
    val effects = (1 to TEST_ITERATIONS).map(i => Eru.succeed(i)).toList
    runtime.parSequence(effects).map(_.sum)
  }

  @Benchmark
  def zioParSequencePureValues(): Int = runZio {
    val effects = (1 to TEST_ITERATIONS).map(i => ZIO.succeed(i))
    ZIO.collectAllPar(effects).map(_.sum)
  }

  @Benchmark
  def ioParSequencePureValues(): Int = runIO {
    val effects = (1 to TEST_ITERATIONS).map(i => IO.pure(i)).toList
    effects.parSequence.map(_.sum)
  }
}
