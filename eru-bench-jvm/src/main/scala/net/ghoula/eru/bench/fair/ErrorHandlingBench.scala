package net.ghoula.eru.bench.fair

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

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
}
