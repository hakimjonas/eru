package net.ghoula.eru.bench.matrix

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Concurrency Scaling Matrix Benchmarks
  * 
  * Tests performance scaling across different concurrency parameters:
  *   - Thread count scaling (1, 2, 4, 8, 16 threads)
  *   - Fiber count scaling (10, 100, 1K fibers)  
  *   - Concurrency level scaling (100, 1K, 10K concurrent operations)
  *   - Different workload patterns (CPU-bound, IO-bound, mixed)
  *
  * Key metrics to analyze:
  *   - Linear scaling vs thread count
  *   - Virtual Thread efficiency vs platform threads
  *   - Fiber overhead at different scales
  *   - Contention points and bottlenecks
  */
class ConcurrencyScalingBench extends MatrixBenchmarkBase {

  // =============================================================================
  // Fork/Await Scaling Tests
  // =============================================================================

  @Benchmark
  def eruForkAwaitScaling(): List[Int] = runEru {
    val effects = generateTestCollection(i => generateWorkload(i).fork)
    for {
      fibers <- parSequence(effects)
      results <- parSequence(fibers.map(_.await.map {
        case Exit.Success(value) => value
        case other => throw new RuntimeException(s"Unexpected exit: $other")  
      }))
    } yield results
  }

  @Benchmark  
  def zioForkAwaitScaling(): List[Int] = runZio {
    val effects = generateTestCollection(i => generateZioWorkload(i).fork)
    for {
      fibers <- ZIO.collectAllPar(effects)
      results <- ZIO.collectAllPar(fibers.map(_.await.map {
        case zio.Exit.Success(value) => value
        case other => throw new RuntimeException(s"Unexpected exit: $other")
      }))
    } yield results
  }

  @Benchmark
  def ioForkAwaitScaling(): List[Int] = runIO {
    val effects = generateTestCollection(i => generateIOWorkload(i).start)
    for {
      fibers <- effects.parSequence  
      results <- fibers.map(_.joinWithNever.map {
        case value => value
      }).parSequence
    } yield results
  }

  // =============================================================================
  // Parallel Execution Scaling Tests
  // =============================================================================

  @Benchmark
  def eruParallelScaling(): List[Int] = runEru {
    val effects = generateTestCollection(i => generateWorkload(i))
    executeParallelEru(effects)
  }

  @Benchmark
  def zioParallelScaling(): List[Int] = runZio {
    val effects = generateTestCollection(i => generateZioWorkload(i))
    executeParallelZio(effects)
  }

  @Benchmark  
  def ioParallelScaling(): List[Int] = runIO {
    val effects = generateTestCollection(i => generateIOWorkload(i))
    executeParallelIO(effects)
  }

  // =============================================================================
  // Race Operation Scaling Tests  
  // =============================================================================

  @Benchmark
  def eruRaceScaling(): Int = runEru {
    val contestants = generateTestCollection(i => generateWorkload(i).map(_ => i))
    contestants match {
      case head :: tail => 
        tail.foldLeft(head) { (winner, contestant) =>
          winner.race(contestant).map {
            case Left(value) => value
            case Right(value) => value
          }
        }
      case Nil => Eru.succeed(0)
    }
  }

  @Benchmark
  def zioRaceScaling(): Int = runZio {
    val contestants = generateTestCollection(i => generateZioWorkload(i).map(_ => i))
    contestants match {
      case head :: tail =>
        tail.foldLeft(head) { (winner, contestant) =>
          winner.raceEither(contestant).map {
            case Left(value) => value  
            case Right(value) => value
          }
        }
      case Nil => ZIO.succeed(0)
    }
  }

  @Benchmark
  def ioRaceScaling(): Int = runIO {
    val contestants = generateTestCollection(i => generateIOWorkload(i).map(_ => i))
    contestants match {
      case head :: tail =>
        tail.foldLeft(head) { (winner, contestant) =>
          IO.race(winner, contestant).map {
            case Left(value) => value
            case Right(value) => value  
          }
        }
      case Nil => IO.pure(0)
    }
  }

  // =============================================================================
  // Concurrent State Management Scaling
  // =============================================================================

  @Benchmark
  def eruConcurrentStateScaling(): Int = runEru {
    for {
      ref <- Eru.ref(0)
      fiberEffects = generateTestCollection(i => 
        ref.update(_ + i).fork
      )
      fibers <- parSequence(fiberEffects)
      _ <- parSequence(fibers.map(_.await))
      result <- ref.get
    } yield result
  }

  @Benchmark
  def zioConcurrentStateScaling(): Int = runZio {
    for {
      ref <- zio.Ref.make(0)
      fibers <- ZIO.collectAllPar(generateTestCollection(i =>
        ref.update(_ + i).fork
      ))
      _ <- ZIO.collectAllPar(fibers.map(_.await))
      result <- ref.get
    } yield result
  }

  @Benchmark
  def ioConcurrentStateScaling(): Int = runIO {
    for {
      ref <- IO.ref(0)
      fibers <- generateTestCollection(i =>
        ref.update(_ + i).start  
      ).parSequence
      _ <- fibers.map(_.joinWithNever).parSequence
      result <- ref.get
    } yield result
  }

  // =============================================================================
  // Fiber-intensive Workloads
  // =============================================================================

  @Benchmark
  def eruMassiveFiberCreation(): Int = runEru {
    val effects = (1 to fiberCount).map(i => Eru.succeed(i).fork).toList
    for {
      fibers <- parSequence(effects)
      results <- parSequence(fibers.map(_.await.map {
        case Exit.Success(value) => value
        case _ => 0
      }))
    } yield results.sum
  }

  @Benchmark  
  def zioMassiveFiberCreation(): Int = runZio {
    val effects = (1 to fiberCount).map(i => ZIO.succeed(i).fork).toList
    for {
      fibers <- ZIO.collectAllPar(effects)
      results <- ZIO.collectAllPar(fibers.map(_.await.map {
        case zio.Exit.Success(value) => value
        case _ => 0
      }))
    } yield results.sum
  }

  @Benchmark
  def ioMassiveFiberCreation(): Int = runIO {
    val effects = (1 to fiberCount).map(i => IO.pure(i).start).toList  
    for {
      fibers <- effects.parSequence
      results <- fibers.map(_.joinWithNever).parSequence
    } yield results.sum
  }

  // =============================================================================
  // Helper Methods for Platform-Specific Workload Generation
  // =============================================================================

  /** Generate ZIO workload based on workloadType parameter */
  private def generateZioWorkload(input: Int): zio.ZIO[Any, Nothing, Int] = workloadType match {
    case "cpu-bound" => ZIO.succeed {
      var result = input
      for (i <- 1 to 1000) {
        result = (result * 31 + i) % 1000007
      }
      result
    }
    case "io-bound" => ZIO.succeed {
      Thread.sleep(1)
      input * 2
    }
    case "mixed" => ZIO.succeed {
      val cpuResult = (input * 31) % 1000007
      if (cpuResult % 10 == 0) Thread.sleep(1)
      cpuResult  
    }
  }

  /** Generate IO workload based on workloadType parameter */
  private def generateIOWorkload(input: Int): IO[Int] = workloadType match {
    case "cpu-bound" => IO {
      var result = input
      for (i <- 1 to 1000) {
        result = (result * 31 + i) % 1000007  
      }
      result
    }
    case "io-bound" => IO {
      Thread.sleep(1)
      input * 2
    }
    case "mixed" => IO {
      val cpuResult = (input * 31) % 1000007
      if (cpuResult % 10 == 0) Thread.sleep(1)
      cpuResult
    }
  }
}