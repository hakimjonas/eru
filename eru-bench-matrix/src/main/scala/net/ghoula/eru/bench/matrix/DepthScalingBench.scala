package net.ghoula.eru.bench.matrix

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Depth Scaling Matrix Benchmarks
  *
  * Tests performance scaling across different composition depth parameters:
  *   - Chain depth scaling (10, 50, 100, 500 sequential operations)
  *   - Nesting level scaling (5, 10, 25 levels of nested composition)
  *   - Map chain scaling (pure transformation chains)
  *   - FlatMap chain scaling (monadic composition chains)
  *   - Mixed composition patterns (alternating map/flatMap)
  *
  * Key metrics to analyze:
  *   - Stack safety at extreme depths
  *   - Chain fusion optimization effectiveness
  *   - Memory allocation patterns with deep composition
  *   - Performance degradation patterns
  */
class DepthScalingBench extends MatrixBenchmarkBase {

  // Scaling dimensions (var required by JMH @Param injection)
  @Param(Array("10", "500"))
  var chainDepth: Int = 100

  @Param(Array("5", "25"))
  var nestingLevel: Int = 10

  @Param(Array("cpu-bound", "mixed"))
  var workloadType: String = "cpu-bound"

  // Fixed dimensions
  val threadCount: Int = 1
  val fiberCount: Int = 100
  val concurrencyLevel: Int = 100
  val collectionSize: Int = 100
  val dataSize: String = "medium"

  // =============================================================================
  // Linear Chain Depth Scaling
  // =============================================================================

  @Benchmark
  def eruLinearChainScaling(): Int = runEru {
    generateEruChain(chainDepth, 1)
  }

  @Benchmark
  def zioLinearChainScaling(): Int = runZio {
    generateZioChain(chainDepth, 1)
  }

  @Benchmark
  def ioLinearChainScaling(): Int = runIO {
    generateIOChain(chainDepth, 1)
  }

  // =============================================================================
  // Map Chain Depth Scaling (Pure Transformations)
  // =============================================================================

  @Benchmark
  def eruMapChainScaling(): Int = runEru {
    (1 to chainDepth).foldLeft(Eru.succeed(1)) { (acc, i) =>
      acc.map(_ + i)
    }
  }

  @Benchmark
  def zioMapChainScaling(): Int = runZio {
    (1 to chainDepth).foldLeft(ZIO.succeed(1)) { (acc, i) =>
      acc.map(_ + i)
    }
  }

  @Benchmark
  def ioMapChainScaling(): Int = runIO {
    (1 to chainDepth).foldLeft(IO.pure(1)) { (acc, i) =>
      acc.map(_ + i)
    }
  }

  // =============================================================================
  // FlatMap Chain Depth Scaling (Monadic Composition)
  // =============================================================================

  @Benchmark
  def eruFlatMapChainScaling(): Int = runEru {
    (1 to chainDepth).foldLeft(Eru.succeed(1)) { (acc, i) =>
      acc.flatMap(n => Eru.succeed(n + i))
    }
  }

  @Benchmark
  def zioFlatMapChainScaling(): Int = runZio {
    (1 to chainDepth).foldLeft(ZIO.succeed(1)) { (acc, i) =>
      acc.flatMap(n => ZIO.succeed(n + i))
    }
  }

  @Benchmark
  def ioFlatMapChainScaling(): Int = runIO {
    (1 to chainDepth).foldLeft(IO.pure(1)) { (acc, i) =>
      acc.flatMap(n => IO.pure(n + i))
    }
  }

  // =============================================================================
  // Mixed Composition Chains
  // =============================================================================

  @Benchmark
  def eruMixedChainScaling(): Int = runEru {
    (1 to chainDepth).foldLeft(Eru.succeed(1)) { (acc, i) =>
      if (i % 2 == 0) {
        acc.map(_ + i)
      } else {
        acc.flatMap(n => Eru.succeed(n + i))
      }
    }
  }

  @Benchmark
  def zioMixedChainScaling(): Int = runZio {
    (1 to chainDepth).foldLeft(ZIO.succeed(1)) { (acc, i) =>
      if (i % 2 == 0) {
        acc.map(_ + i)
      } else {
        acc.flatMap(n => ZIO.succeed(n + i))
      }
    }
  }

  @Benchmark
  def ioMixedChainScaling(): Int = runIO {
    (1 to chainDepth).foldLeft(IO.pure(1)) { (acc, i) =>
      if (i % 2 == 0) {
        acc.map(_ + i)
      } else {
        acc.flatMap(n => IO.pure(n + i))
      }
    }
  }

  // =============================================================================
  // Nested Composition Depth Scaling
  // =============================================================================

  @Benchmark
  def eruNestedCompositionScaling(): Int = runEru {
    generateNestedEruComposition(nestingLevel, 3)
  }

  @Benchmark
  def zioNestedCompositionScaling(): Int = runZio {
    generateNestedZioComposition(nestingLevel, 3)
  }

  @Benchmark
  def ioNestedCompositionScaling(): Int = runIO {
    generateNestedIOComposition(nestingLevel, 3)
  }

  // =============================================================================
  // Error Handling Chain Depth Scaling
  // =============================================================================

  @Benchmark
  def eruErrorChainScaling(): Int = runEru {
    val chain = (1 to chainDepth).foldLeft(Eru.succeed(1)) { (acc, i) =>
      acc.flatMap { n =>
        if (i == chainDepth / 2) {
          Eru.fail("mid-chain-error").recover { case "mid-chain-error" =>
            n + i + 1000 // Recovery marker
          }
        } else {
          Eru.succeed(n + i)
        }
      }
    }
    chain
  }

  @Benchmark
  def zioErrorChainScaling(): Int = runZio {
    val chain = (1 to chainDepth).foldLeft(ZIO.succeed(1)) { (acc, i) =>
      acc.flatMap { n =>
        if (i == chainDepth / 2) {
          ZIO.fail("mid-chain-error").catchAll { case "mid-chain-error" =>
            ZIO.succeed(n + i + 1000) // Recovery marker
          }
        } else {
          ZIO.succeed(n + i)
        }
      }
    }
    chain
  }

  @Benchmark
  def ioErrorChainScaling(): Int = runIO {
    val chain = (1 to chainDepth).foldLeft(IO.pure(1)) { (acc, i) =>
      acc.flatMap { n =>
        if (i == chainDepth / 2) {
          IO.raiseError(new RuntimeException("mid-chain-error")).handleError { case _: RuntimeException =>
            n + i + 1000 // Recovery marker
          }
        } else {
          IO.pure(n + i)
        }
      }
    }
    chain
  }

  // =============================================================================
  // Resource Management Chain Depth Scaling
  // =============================================================================

  @Benchmark
  def eruResourceChainScaling(): Int = runEru {
    def buildResourceChain(depth: Int): Eru[Nothing, Int] = {
      if (depth <= 0) {
        Eru.succeed(1)
      } else {
        Eru.succeed(depth).bracket(_ => Eru.succeed(())) { resource =>
          buildResourceChain(depth - 1).map(_ + resource)
        }
      }
    }
    buildResourceChain(nestingLevel)
  }

  @Benchmark
  def zioResourceChainScaling(): Int = runZio {
    def buildResourceChain(depth: Int): ZIO[Any, Nothing, Int] = {
      if (depth <= 0) {
        ZIO.succeed(1)
      } else {
        ZIO.acquireReleaseWith(ZIO.succeed(depth))(_ => ZIO.succeed(())) { resource =>
          buildResourceChain(depth - 1).map(_ + resource)
        }
      }
    }
    buildResourceChain(nestingLevel)
  }

  @Benchmark
  def ioResourceChainScaling(): Int = runIO {
    def buildResourceChain(depth: Int): IO[Int] = {
      if (depth <= 0) {
        IO.pure(1)
      } else {
        IO.pure(depth)
          .bracket { resource =>
            buildResourceChain(depth - 1).map(_ + resource)
          }(_ => IO.unit)
      }
    }
    buildResourceChain(nestingLevel)
  }

  // =============================================================================
  // Concurrent Depth Scaling (Parallel Deep Chains)
  // =============================================================================

  @Benchmark
  def eruConcurrentDepthScaling(): List[Int] = runEru {
    val deepChains = (1 to concurrencyLevel).map(i => generateEruChain(chainDepth / 10, i)).toList

    executeParallelEru(deepChains)
  }

  @Benchmark
  def zioConcurrentDepthScaling(): List[Int] = runZio {
    val deepChains = (1 to concurrencyLevel).map(i => generateZioChain(chainDepth / 10, i)).toList

    executeParallelZio(deepChains)
  }

  @Benchmark
  def ioConcurrentDepthScaling(): List[Int] = runIO {
    val deepChains = (1 to concurrencyLevel).map(i => generateIOChain(chainDepth / 10, i)).toList

    executeParallelIO(deepChains)
  }

  // =============================================================================
  // Helper Methods for Nested Composition Generation
  // =============================================================================

  /** Generate nested ZIO composition */
  private def generateNestedZioComposition(levels: Int, width: Int): ZIO[Any, Nothing, Int] = {
    def buildLevel(level: Int): ZIO[Any, Nothing, Int] = {
      if (level <= 0) {
        ZIO.succeed(1)
      } else {
        val effects = (1 to width).map(_ => buildLevel(level - 1)).toList
        effects.foldLeft(ZIO.succeed(0)) { (acc, effect) =>
          for {
            a <- acc
            b <- effect
          } yield a + b
        }
      }
    }
    buildLevel(levels)
  }

  /** Generate nested IO composition */
  private def generateNestedIOComposition(levels: Int, width: Int): IO[Int] = {
    def buildLevel(level: Int): IO[Int] = {
      if (level <= 0) {
        IO.pure(1)
      } else {
        val effects = (1 to width).map(_ => buildLevel(level - 1)).toList
        effects.foldLeft(IO.pure(0)) { (acc, effect) =>
          for {
            a <- acc
            b <- effect
          } yield a + b
        }
      }
    }
    buildLevel(levels)
  }
}
