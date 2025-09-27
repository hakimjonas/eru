package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.syntax.all.*
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Fair Concurrency Benchmarks with Actual Effects
  *
  * These benchmarks test real concurrent behavior with actual effects, not just pure value
  * optimization. They provide a fair comparison between frameworks by using operations that
  * actually require concurrency machinery.
  *
  * Two categories:
  *   - Light effects: Minimal computation that still requires effect execution
  *   - Heavy effects: Operations with actual delays that benefit from parallelism
  */
class FairConcurrencyBench extends FairBenchmarkBase {

  // =============================================================================
  // Race with Light Effects (actual computation)
  // =============================================================================

  @Benchmark
  def eruRaceLightEffects(): Int = runEru {
    val fast = Eru.effect {
      val result = scala.util.Random.nextInt(100)
      result
    }
    val slow = Eru.effect {
      val result = scala.util.Random.nextInt(100) + 100
      result
    }
    fast.race(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def zioRaceLightEffects(): Int = runZio {
    val fast = ZIO.attempt {
      val result = scala.util.Random.nextInt(100)
      result
    }
    val slow = ZIO.attempt {
      val result = scala.util.Random.nextInt(100) + 100
      result
    }
    fast.raceEither(slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  @Benchmark
  def ioRaceLightEffects(): Int = runIO {
    val fast = IO.delay {
      val result = scala.util.Random.nextInt(100)
      result
    }
    val slow = IO.delay {
      val result = scala.util.Random.nextInt(100) + 100
      result
    }
    IO.race(fast, slow).map {
      case Left(result) => result
      case Right(result) => result
    }
  }

  // =============================================================================
  // ZipPar with Light Effects
  // =============================================================================

  @Benchmark
  def eruZipParLightEffects(): Int = runEru {
    val left = Eru.effect {
      scala.util.Random.nextInt(50)
    }
    val right = Eru.effect {
      scala.util.Random.nextInt(50)
    }
    left.zipPar(right).map { case (a, b) => a + b }
  }

  @Benchmark
  def zioZipParLightEffects(): Int = runZio {
    val left = ZIO.attempt {
      scala.util.Random.nextInt(50)
    }
    val right = ZIO.attempt {
      scala.util.Random.nextInt(50)
    }
    left.zipPar(right).map { case (a, b) => a + b }
  }

  @Benchmark
  def ioZipParLightEffects(): Int = runIO {
    val left = IO.delay {
      scala.util.Random.nextInt(50)
    }
    val right = IO.delay {
      scala.util.Random.nextInt(50)
    }
    (left, right).parTupled.map { case (a, b) => a + b }
  }

  // =============================================================================
  // Fork/Await with Light Effects
  // =============================================================================

  @Benchmark
  def eruForkAwaitLightEffects(): Int = runEru {
    for {
      fiber <- Eru.effect {
        val result = scala.util.Random.nextInt(100)
        result * 2
      }.fork
      result <- fiber.await.flatMap(Eru.fromExit(_))
    } yield result
  }

  @Benchmark
  def zioForkAwaitLightEffects(): Int = runZio {
    for {
      fiber <- ZIO.attempt {
        val result = scala.util.Random.nextInt(100)
        result * 2
      }.fork
      exit <- fiber.await
      result <- exit match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
    } yield result
  }

  @Benchmark
  def ioForkAwaitLightEffects(): Int = runIO {
    for {
      fiber <- IO.delay {
        val result = scala.util.Random.nextInt(100)
        result * 2
      }.start
      result <- fiber.joinWithNever
    } yield result
  }

  // =============================================================================
  // Mixed Workload (combining pure and effectful)
  // =============================================================================

  @Benchmark
  def eruMixedWorkload(): Int = runEru {
    for {
      pure1 <- Eru.succeed(10)
      effect1 <- Eru.effect(scala.util.Random.nextInt(20))
      pure2 <- Eru.succeed(30)
      fiber <- Eru.effect(scala.util.Random.nextInt(40)).fork
      effect2 <- Eru.effect(scala.util.Random.nextInt(50))
      fiberResult <- fiber.await.flatMap(Eru.fromExit(_))
    } yield pure1 + effect1 + pure2 + effect2 + fiberResult
  }

  @Benchmark
  def zioMixedWorkload(): Int = runZio {
    for {
      pure1 <- ZIO.succeed(10)
      effect1 <- ZIO.attempt(scala.util.Random.nextInt(20))
      pure2 <- ZIO.succeed(30)
      fiber <- ZIO.attempt(scala.util.Random.nextInt(40)).fork
      effect2 <- ZIO.attempt(scala.util.Random.nextInt(50))
      exit <- fiber.await
      fiberResult <- exit match {
        case zio.Exit.Success(value) => ZIO.succeed(value)
        case zio.Exit.Failure(cause) => ZIO.failCause(cause)
      }
    } yield pure1 + effect1 + pure2 + effect2 + fiberResult
  }

  @Benchmark
  def ioMixedWorkload(): Int = runIO {
    for {
      pure1 <- IO.pure(10)
      effect1 <- IO.delay(scala.util.Random.nextInt(20))
      pure2 <- IO.pure(30)
      fiber <- IO.delay(scala.util.Random.nextInt(40)).start
      effect2 <- IO.delay(scala.util.Random.nextInt(50))
      fiberResult <- fiber.joinWithNever
    } yield pure1 + effect1 + pure2 + effect2 + fiberResult
  }

  // =============================================================================
  // Parallel Collection Processing with Effects
  // =============================================================================

  @Benchmark
  def eruParallelEffects(): Int = runEru {
    val effects = (1 to 5)
      .map(i =>
        Eru.effect {
          scala.util.Random.nextInt(i * 10)
        }
      )
      .toList
    runtime.parSequence(effects).map(_.sum)
  }

  @Benchmark
  def zioParallelEffects(): Int = runZio {
    val effects = (1 to 5).map(i =>
      ZIO.attempt {
        scala.util.Random.nextInt(i * 10)
      }
    )
    ZIO.collectAllPar(effects).map(_.sum)
  }

  @Benchmark
  def ioParallelEffects(): Int = runIO {
    val effects = (1 to 5)
      .map(i =>
        IO.delay {
          scala.util.Random.nextInt(i * 10)
        }
      )
      .toList
    effects.parSequence.map(_.sum)
  }
}
