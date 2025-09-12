package net.ghoula.eru.bench.matrix

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.Unsafe

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Random

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.prelude.*

/** Base class for parametric matrix benchmarks.
  *
  * Provides comprehensive parameter matrices for testing performance across multiple dimensions:
  *   - Concurrency levels (thread count, fiber count)
  *   - Data sizes (collection sizes, payload sizes)
  *   - Composition depth (chain depth, nesting levels)
  *   - Workload patterns (CPU-bound, IO-bound, mixed)
  *
  * Uses extended measurement periods for statistical confidence.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3) // Multiple forks for statistical confidence
abstract class MatrixBenchmarkBase {

  // =============================================================================
  // Concurrency Matrix Parameters
  // =============================================================================

  @Param(Array("1", "2", "4", "8", "16"))
  var threadCount: Int = 1

  @Param(Array("10", "100", "1000"))
  var fiberCount: Int = 100

  @Param(Array("100", "1000", "10000"))
  var concurrencyLevel: Int = 1000

  // =============================================================================
  // Data Size Matrix Parameters
  // =============================================================================

  @Param(Array("10", "100", "1000"))
  var collectionSize: Int = 100

  @Param(Array("small", "medium", "large"))
  var dataSize: String = "medium"

  // =============================================================================
  // Depth Matrix Parameters
  // =============================================================================

  @Param(Array("10", "50", "100", "500"))
  var chainDepth: Int = 100

  @Param(Array("5", "10", "25"))
  var nestingLevel: Int = 10

  // =============================================================================
  // Workload Pattern Parameters
  // =============================================================================

  @Param(Array("cpu-bound", "io-bound", "mixed"))
  var workloadType: String = "cpu-bound"

  // =============================================================================
  // Runtime Setup
  // =============================================================================

  protected var runtime: EruRuntime = uninitialized
  implicit protected var implicitRuntime: EruRuntime = uninitialized
  protected var random: Random = uninitialized

  @Setup(Level.Trial)
  def setupRuntime(): Unit = {
    runtime = EruRuntime.create()
    implicitRuntime = runtime
    random = new Random(42) // Fixed seed for reproducibility
  }

  @TearDown(Level.Trial)
  def teardownRuntime(): Unit = {
    // Cleanup if needed
  }

  // =============================================================================
  // Data Generation Helpers
  // =============================================================================

  /** Generate test data based on dataSize parameter */
  protected def generateTestData(): Array[Byte] = dataSize match {
    case "small" => new Array[Byte](1024) // 1KB
    case "medium" => new Array[Byte](10 * 1024) // 10KB
    case "large" => new Array[Byte](100 * 1024) // 100KB
  }

  /** Generate test collection of specified size */
  protected def generateTestCollection[A](generator: Int => A): List[A] = {
    (1 to collectionSize).map(generator).toList
  }

  /** Generate workload based on workloadType parameter */
  protected def generateWorkload(input: Int): Eru[Nothing, Int] = workloadType match {
    case "cpu-bound" =>
      Eru.succeed {
        // CPU-intensive computation
        var result = input
        for (i <- 1 to 1000) {
          result = (result * 31 + i) % 1000007
        }
        result
      }
    case "io-bound" =>
      Eru.succeed {
        // Simulate IO delay
        Thread.sleep(1)
        input * 2
      }
    case "mixed" =>
      Eru.succeed {
        // Mix of CPU and IO
        val cpuResult = (input * 31) % 1000007
        if (cpuResult % 10 == 0) Thread.sleep(1)
        cpuResult
      }
  }

  // =============================================================================
  // Effect Execution Helpers
  // =============================================================================

  /** Helper for running Eru effects */
  protected def runEru[A](effect: Eru[?, A]): A = effect.unsafeRunSync()

  /** Helper for running ZIO effects */
  protected def runZio[A](effect: zio.ZIO[Any, ?, A]): A = {
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
  }

  /** Helper for running Cats Effect IO */
  protected def runIO[A](effect: IO[A]): A = effect.unsafeRunSync()

  // =============================================================================
  // Chain Generation Helpers
  // =============================================================================

  /** Generate effect chain of specified depth */
  protected def generateEruChain(depth: Int, initial: Int): Eru[Nothing, Int] = {
    (1 to depth).foldLeft(Eru.succeed(initial)) { (acc, i) =>
      acc.flatMap(n => Eru.succeed(n + i))
    }
  }

  /** Generate ZIO chain of specified depth */
  protected def generateZioChain(depth: Int, initial: Int): zio.ZIO[Any, Nothing, Int] = {
    (1 to depth).foldLeft(zio.ZIO.succeed(initial)) { (acc, i) =>
      acc.flatMap(n => zio.ZIO.succeed(n + i))
    }
  }

  /** Generate IO chain of specified depth */
  protected def generateIOChain(depth: Int, initial: Int): IO[Int] = {
    (1 to depth).foldLeft(IO.pure(initial)) { (acc, i) =>
      acc.flatMap(n => IO.pure(n + i))
    }
  }

  // =============================================================================
  // Nested Composition Helpers
  // =============================================================================

  /** Generate nested Eru composition */
  protected def generateNestedEruComposition(levels: Int, width: Int): Eru[Nothing, Int] = {
    def buildLevel(level: Int): Eru[Nothing, Int] = {
      if (level <= 0) {
        Eru.succeed(1)
      } else {
        val effects = (1 to width).map(_ => buildLevel(level - 1)).toList
        effects.foldLeft(Eru.succeed(0)) { (acc, effect) =>
          for {
            a <- acc
            b <- effect
          } yield a + b
        }
      }
    }
    buildLevel(levels)
  }

  // =============================================================================
  // Parallel Execution Helpers
  // =============================================================================

  /** Execute effects in parallel with controlled concurrency */
  protected def executeParallelEru[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] = {
    parSequence(effects)
  }

  /** Execute effects in parallel using ZIO */
  protected def executeParallelZio[A](effects: List[zio.ZIO[Any, Nothing, A]]): zio.ZIO[Any, Nothing, List[A]] = {
    zio.ZIO.collectAllPar(effects)
  }

  /** Execute effects in parallel using Cats Effect */
  protected def executeParallelIO[A](effects: List[IO[A]]): IO[List[A]] = {
    effects.parSequence
  }

  // =============================================================================
  // Performance Measurement Helpers
  // =============================================================================

  /** Log current parameter configuration (for debugging) */
  protected def logConfiguration(): Unit = {
    // Note: JMH captures this in benchmark metadata
    // Configuration available via parameter annotations
  }
}
