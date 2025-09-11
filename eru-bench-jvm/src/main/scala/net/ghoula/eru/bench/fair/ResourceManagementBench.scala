package net.ghoula.eru.bench.fair

import cats.effect.{IO, Resource}
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 6: Resource Management Benchmarks
  *
  * Tests resource management and cleanup patterns:
  *   - Bracket acquire/use/release operations
  *   - Ensure/finalizer execution
  *   - Multiple finalizer coordination (FILO order)
  *   - Resource cleanup under success/failure scenarios
  *
  * Expected runtime: ~4 minutes Coverage: Resource discipline and cleanup guarantees
  */
class ResourceManagementBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Bracket Operations
  // =============================================================================

  @Benchmark
  def eruBracketSuccess(): Int = runEru {
    val acquire = Eru.succeed("resource")
    val release = (_: String) => Eru.unit
    val use = (_: String) => Eru.succeed(TEST_VALUE)

    acquire.bracket(release)(use)
  }

  @Benchmark
  def zioBracketSuccess(): Int = runZio {
    val acquire = ZIO.succeed("resource")
    val release = (_: String) => ZIO.unit
    val use = (_: String) => ZIO.succeed(TEST_VALUE)

    ZIO.acquireReleaseWith(acquire)(release)(use)
  }

  @Benchmark
  def ioBracketSuccess(): Int = runIO {
    val acquire = IO.pure("resource")
    val release = (_: String) => IO.unit
    val use = (_: String) => IO.pure(TEST_VALUE)

    Resource.make(acquire)(release).use(use)
  }

  // =============================================================================
  // Bracket with Error Recovery
  // =============================================================================

  @Benchmark
  def eruBracketWithError(): Int = runEru {
    val acquire = Eru.succeed("resource")
    val release = (_: String) => Eru.unit
    val use = (_: String) =>
      Eru.fail("error").attempt.map {
        case Result.Success(v) => v
        case Result.Failure(_) => TEST_VALUE
      }

    acquire.bracket(release)(use)
  }

  @Benchmark
  def zioBracketWithError(): Int = runZio {
    val acquire = ZIO.succeed("resource")
    val release = (_: String) => ZIO.unit
    val use = (_: String) =>
      ZIO.fail("error").either.map {
        case Right(v) => v
        case Left(_) => TEST_VALUE
      }

    ZIO.acquireReleaseWith(acquire)(release)(use)
  }

  @Benchmark
  def ioBracketWithError(): Int = runIO {
    val acquire = IO.pure("resource")
    val release = (_: String) => IO.unit
    val use = (_: String) =>
      IO.raiseError[Int](new RuntimeException("error")).attempt.map {
        case Right(v) => v
        case Left(_) => TEST_VALUE
      }

    Resource.make(acquire)(release).use(use)
  }

  // =============================================================================
  // Basic Ensure/Finally Operations
  // =============================================================================

  @Benchmark
  def eruEnsureSuccess(): Int = runEru {
    Eru.succeed(TEST_VALUE).ensure(Eru.unit)
  }

  @Benchmark
  def zioEnsureSuccess(): Int = runZio {
    ZIO.succeed(TEST_VALUE).ensuring(ZIO.unit)
  }

  @Benchmark
  def ioEnsureSuccess(): Int = runIO {
    IO.pure(TEST_VALUE).guarantee(IO.unit)
  }

  // =============================================================================
  // Ensure with Error Handling
  // =============================================================================

  @Benchmark
  def eruEnsureWithError(): Int = runEru {
    Eru
      .fail("error")
      .attempt
      .map {
        case Result.Success(v) => v
        case Result.Failure(_) => TEST_VALUE
      }
      .ensure(Eru.unit)
  }

  @Benchmark
  def zioEnsureWithError(): Int = runZio {
    ZIO
      .fail("error")
      .either
      .map {
        case Right(v) => v
        case Left(_) => TEST_VALUE
      }
      .ensuring(ZIO.unit)
  }

  @Benchmark
  def ioEnsureWithError(): Int = runIO {
    IO.raiseError[Int](new RuntimeException("error"))
      .attempt
      .map {
        case Right(v) => v
        case Left(_) => TEST_VALUE
      }
      .guarantee(IO.unit)
  }

  // =============================================================================
  // Multiple Finalizers (FILO Order)
  // =============================================================================

  @Benchmark
  def eruMultipleFinalizers(): Int = runEru {
    Eru
      .succeed(TEST_VALUE)
      .ensure(Eru.unit) // Finalizer 1
      .ensure(Eru.unit) // Finalizer 2
      .ensure(Eru.unit) // Finalizer 3 (should run first - FILO)
  }

  @Benchmark
  def zioMultipleFinalizers(): Int = runZio {
    ZIO
      .succeed(TEST_VALUE)
      .ensuring(ZIO.unit) // Finalizer 1
      .ensuring(ZIO.unit) // Finalizer 2
      .ensuring(ZIO.unit) // Finalizer 3
  }

  @Benchmark
  def ioMultipleFinalizers(): Int = runIO {
    IO.pure(TEST_VALUE)
      .guarantee(IO.unit) // Finalizer 1
      .guarantee(IO.unit) // Finalizer 2
      .guarantee(IO.unit) // Finalizer 3
  }

  // =============================================================================
  // Complex Resource Pattern
  // =============================================================================

  @Benchmark
  def eruComplexResource(): Int = runEru {
    val acquire1 = Eru.succeed("resource1")
    val acquire2 = Eru.succeed("resource2")
    val release1 = (_: String) => Eru.unit
    val release2 = (_: String) => Eru.unit

    acquire1.bracket(release1) { res1 =>
      acquire2.bracket(release2) { res2 =>
        Eru.succeed(res1.length + res2.length + TEST_VALUE)
      }
    }
  }

  @Benchmark
  def zioComplexResource(): Int = runZio {
    val acquire1 = ZIO.succeed("resource1")
    val acquire2 = ZIO.succeed("resource2")
    val release1 = (_: String) => ZIO.unit
    val release2 = (_: String) => ZIO.unit

    ZIO.acquireReleaseWith(acquire1)(release1) { res1 =>
      ZIO.acquireReleaseWith(acquire2)(release2) { res2 =>
        ZIO.succeed(res1.length + res2.length + TEST_VALUE)
      }
    }
  }

  @Benchmark
  def ioComplexResource(): Int = runIO {
    val acquire1 = IO.pure("resource1")
    val acquire2 = IO.pure("resource2")
    val release1 = (_: String) => IO.unit
    val release2 = (_: String) => IO.unit

    Resource.make(acquire1)(release1).use { res1 =>
      Resource.make(acquire2)(release2).use { res2 =>
        IO.pure(res1.length + res2.length + TEST_VALUE)
      }
    }
  }
}
