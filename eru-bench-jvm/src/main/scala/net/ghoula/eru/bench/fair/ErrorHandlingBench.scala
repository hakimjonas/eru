package net.ghoula.eru.bench.fair

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.ParallelErrors
import net.ghoula.eru.prelude.*

/** Category 2: Error Handling Benchmarks
  *
  * Tests error handling and recovery operations:
  *   - Error creation and propagation
  *   - Error recovery and transformation
  *   - Attempt/either conversion patterns
  *   - Error handling in chains
  *
  * Expected runtime: ~2 minutes Coverage: Error management across different failure scenarios
  */
class ErrorHandlingBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Error Creation and Recovery
  // =============================================================================

  @Benchmark
  def eruFailRecover(): Int = runEru {
    Eru.fail(TEST_ERROR).attempt.map {
      case Result.Success(value) => value
      case Result.Failure(_) => TEST_VALUE
    }
  }

  @Benchmark
  def zioFailRecover(): Int = runZio {
    ZIO.fail(TEST_ERROR).either.map {
      case Right(value) => value
      case Left(_) => TEST_VALUE
    }
  }

  @Benchmark
  def ioFailRecover(): Int = runIO {
    IO.raiseError[Int](new RuntimeException(TEST_ERROR)).attempt.map {
      case Right(value) => value
      case Left(_) => TEST_VALUE
    }
  }

  // =============================================================================
  // Successful Path with Error Handling
  // =============================================================================

  @Benchmark
  def eruSuccessfulAttempt(): Int = runEru {
    Eru.succeed(TEST_VALUE).attempt.map {
      case Result.Success(value) => value
      case Result.Failure(_) => 0
    }
  }

  @Benchmark
  def zioSuccessfulEither(): Int = runZio {
    ZIO.attempt(TEST_VALUE).either.map {
      case Right(value) => value
      case Left(_) => 0
    }
  }

  @Benchmark
  def ioSuccessfulAttempt(): Int = runIO {
    IO.pure(TEST_VALUE).attempt.map {
      case Right(value) => value
      case Left(_) => 0
    }
  }

  // =============================================================================
  // Error in Chain Recovery
  // =============================================================================

  @Benchmark
  def eruChainWithErrorRecovery(): Int = runEru {
    for {
      a <- Eru.succeed(10)
      b <- Eru.fail(TEST_ERROR).attempt.map {
        case Result.Success(v) => v
        case Result.Failure(_) => 20
      }
      c <- Eru.succeed(12)
      result <- Eru.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def zioChainWithErrorRecovery(): Int = runZio {
    for {
      a <- ZIO.succeed(10)
      b <- ZIO.fail(TEST_ERROR).either.map {
        case Right(v) => v
        case Left(_) => 20
      }
      c <- ZIO.succeed(12)
      result <- ZIO.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def ioChainWithErrorRecovery(): Int = runIO {
    for {
      a <- IO.pure(10)
      b <- IO.raiseError[Int](new RuntimeException(TEST_ERROR)).attempt.map {
        case Right(v) => v
        case Left(_) => 20
      }
      c <- IO.pure(12)
      result <- IO.pure(a + b + c)
    } yield result
  }

  // =============================================================================
  // Multiple Error Recovery Points
  // =============================================================================

  @Benchmark
  def eruMultipleErrorRecovery(): Int = runEru {
    val effect1 = Eru.fail("error1").attempt.map {
      case Result.Success(v) => v
      case Result.Failure(_) => 10
    }
    val effect2 = Eru.fail("error2").attempt.map {
      case Result.Success(v) => v
      case Result.Failure(_) => 20
    }
    val effect3 = Eru.succeed(12)

    for {
      a <- effect1
      b <- effect2
      c <- effect3
      result <- Eru.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def zioMultipleErrorRecovery(): Int = runZio {
    val effect1 = ZIO.fail("error1").either.map {
      case Right(v) => v
      case Left(_) => 10
    }
    val effect2 = ZIO.fail("error2").either.map {
      case Right(v) => v
      case Left(_) => 20
    }
    val effect3 = ZIO.succeed(12)

    for {
      a <- effect1
      b <- effect2
      c <- effect3
      result <- ZIO.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def ioMultipleErrorRecovery(): Int = runIO {
    val effect1 = IO.raiseError[Int](new RuntimeException("error1")).attempt.map {
      case Right(v) => v
      case Left(_) => 10
    }
    val effect2 = IO.raiseError[Int](new RuntimeException("error2")).attempt.map {
      case Right(v) => v
      case Left(_) => 20
    }
    val effect3 = IO.pure(12)

    for {
      a <- effect1
      b <- effect2
      c <- effect3
      result <- IO.pure(a + b + c)
    } yield result
  }

  // =============================================================================
  // Retry Operations
  // =============================================================================

  @Benchmark
  def eruRetryN(): Int = runEru {
    var attempts = 0
    val effect = Eru.effect {
      attempts += 1
      if (attempts < 3) throw new RuntimeException("retry me")
      else TEST_VALUE
    }
    effect.retryN(3)
  }

  @Benchmark
  def zioRetryN(): Int = runZio {
    var attempts = 0
    val effect = ZIO.attempt {
      attempts += 1
      if (attempts < 3) throw new RuntimeException("retry me")
      else TEST_VALUE
    }
    effect.retry(zio.Schedule.recurs(3))
  }

  @Benchmark
  def ioRetryN(): Int = runIO {
    var attempts = 0
    val effect = IO {
      attempts += 1
      if (attempts < 3) throw new RuntimeException("retry me")
      else TEST_VALUE
    }
    // Cats Effect retry simulation
    def retryLoop(n: Int): IO[Int] =
      effect.handleErrorWith { _ =>
        if (n > 0) retryLoop(n - 1) else effect
      }
    retryLoop(3)
  }

  // =============================================================================
  // OrElse Operations
  // =============================================================================

  @Benchmark
  def eruOrElse(): Int = runEru {
    Eru.fail(TEST_ERROR).orElse(Eru.succeed(TEST_VALUE))
  }

  @Benchmark
  def zioOrElse(): Int = runZio {
    ZIO.fail(TEST_ERROR).orElse(ZIO.succeed(TEST_VALUE))
  }

  @Benchmark
  def ioOrElse(): Int = runIO {
    IO.raiseError[Int](new RuntimeException(TEST_ERROR)).handleErrorWith(_ => IO.pure(TEST_VALUE))
  }

  // =============================================================================
  // Parallel Error Collection (New)
  // =============================================================================

  @Benchmark
  def eruZipParAllBothFail(): (Int, Int) = runEru {
    runtime.zipParAll(
      Eru.fail("error1").orElse(Eru.succeed(10)),
      Eru.fail("error2").orElse(Eru.succeed(20))
    )
  }

  @Benchmark
  def eruZipParAllOneFails(): (Int, Int) = runEru {
    runtime.zipParAll(
      Eru.succeed(10),
      Eru.fail("error2").orElse(Eru.succeed(20))
    )
  }

  @Benchmark
  def eruParSequenceAllSuccess(): List[Int] = runEru {
    val effects = List(
      Eru.succeed(1),
      Eru.succeed(2),
      Eru.succeed(3),
      Eru.succeed(4),
      Eru.succeed(5)
    )
    runtime.parSequenceAll(effects)
  }

  @Benchmark
  def eruParSequenceSuccess(): List[Int] = runEru {
    val effects = List(
      Eru.succeed(1),
      Eru.succeed(2),
      Eru.succeed(3),
      Eru.succeed(4),
      Eru.succeed(5)
    )
    runtime.parSequence(effects)
  }

  @Benchmark
  def zioParallelSuccess(): List[Int] = runZio {
    import zio.ZIO.collectAllPar
    val effects = List(
      ZIO.succeed(1),
      ZIO.succeed(2),
      ZIO.succeed(3),
      ZIO.succeed(4),
      ZIO.succeed(5)
    )
    collectAllPar(effects)
  }

  @Benchmark
  def ioParallelSuccess(): List[Int] = runIO {
    import cats.implicits._
    val effects = List(
      IO.pure(1),
      IO.pure(2),
      IO.pure(3),
      IO.pure(4),
      IO.pure(5)
    )
    effects.parSequence
  }

  // =============================================================================
  // Parallel with Actual Work (Realistic Scenarios)
  // =============================================================================

  @Benchmark
  def eruParSequenceWithWork(): List[Int] = runEru {
    val effects = List(
      Eru.effect { Thread.sleep(1); 1 },
      Eru.effect { Thread.sleep(1); 2 },
      Eru.effect { Thread.sleep(1); 3 },
      Eru.effect { Thread.sleep(1); 4 },
      Eru.effect { Thread.sleep(1); 5 }
    )
    runtime.parSequence(effects)
  }

  @Benchmark
  def eruParSequenceAllWithWork(): List[Int] = runEru {
    val effects = List(
      Eru.effect { Thread.sleep(1); 1 },
      Eru.effect { Thread.sleep(1); 2 },
      Eru.effect { Thread.sleep(1); 3 },
      Eru.effect { Thread.sleep(1); 4 },
      Eru.effect { Thread.sleep(1); 5 }
    )
    runtime.parSequenceAll(effects)
  }

  @Benchmark
  def zioParallelWithWork(): List[Int] = runZio {
    import zio.ZIO.collectAllPar
    val effects = List(
      ZIO.attempt { Thread.sleep(1); 1 },
      ZIO.attempt { Thread.sleep(1); 2 },
      ZIO.attempt { Thread.sleep(1); 3 },
      ZIO.attempt { Thread.sleep(1); 4 },
      ZIO.attempt { Thread.sleep(1); 5 }
    )
    collectAllPar(effects)
  }

  @Benchmark
  def ioParallelWithWork(): List[Int] = runIO {
    import cats.implicits._
    val effects = List(
      IO { Thread.sleep(1); 1 },
      IO { Thread.sleep(1); 2 },
      IO { Thread.sleep(1); 3 },
      IO { Thread.sleep(1); 4 },
      IO { Thread.sleep(1); 5 }
    )
    effects.parSequence
  }

  // =============================================================================
  // Parallel with Mixed Success/Failure
  // =============================================================================

  @Benchmark
  def eruParSequenceAllMixed(): Either[Any, List[Int]] = runEru {
    val effects = List(
      Eru.succeed(1),
      Eru.effect { Thread.sleep(1); 2 },
      Eru.fail("error1"),
      Eru.effect { Thread.sleep(1); 4 },
      Eru.fail("error2")
    )
    runtime.parSequenceAll(effects).attempt.map {
      case Result.Success(list) => Right(list)
      case Result.Failure(ParallelErrors(first, rest)) => Left(s"Errors: $first, ${rest.mkString(", ")}")
      case Result.Failure(e) => Left(s"Error: $e")
    }
  }

  @Benchmark
  def eruParSequenceMixed(): Either[String, List[Int]] = runEru {
    val effects = List(
      Eru.succeed(1),
      Eru.effect { Thread.sleep(1); 2 },
      Eru.fail("error1"),
      Eru.effect { Thread.sleep(1); 4 },
      Eru.fail("error2")
    )
    runtime.parSequence(effects).attempt.map {
      case Result.Success(list) => Right(list)
      case Result.Failure(e: String) => Left(e)
      case Result.Failure(e) => Left(e.toString)
    }
  }
}
