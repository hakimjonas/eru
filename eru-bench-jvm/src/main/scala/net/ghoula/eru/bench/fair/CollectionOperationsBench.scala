package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.implicits.*
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 8: Collection Operations Benchmarks
  *
  * Tests collection-based effect operations:
  *   - Sequential collection processing (traverse, sequence)
  *   - Parallel collection processing (parTraverse, parSequence)
  *   - Collection folding operations
  *   - Batch effect execution patterns
  *
  * Expected runtime: ~4 minutes Coverage: Collection-oriented effect composition and execution
  */
class CollectionOperationsBench extends FairBenchmarkBase {

  private val COLLECTION_SIZE = 20

  // =============================================================================
  // Sequential Collection Processing
  // =============================================================================

  @Benchmark
  def eruSequenceBasic(): List[Int] = runEru {
    val effects = (1 to COLLECTION_SIZE).map(Eru.succeed(_)).toList
    Eru.collectAll(effects)
  }

  @Benchmark
  def zioSequenceBasic(): List[Int] = runZio {
    val effects = (1 to COLLECTION_SIZE).map(ZIO.succeed(_)).toList
    ZIO.collectAll(effects)
  }

  @Benchmark
  def ioSequenceBasic(): List[Int] = runIO {
    val effects = (1 to COLLECTION_SIZE).map(IO.pure(_)).toList
    effects.sequence
  }

  // =============================================================================
  // Sequential Traverse Operations
  // =============================================================================

  @Benchmark
  def eruTraverseBasic(): List[Int] = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.foreach(items)(x => Eru.succeed(x * 2))
  }

  @Benchmark
  def zioTraverseBasic(): List[Int] = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreach(items)(x => ZIO.succeed(x * 2))
  }

  @Benchmark
  def ioTraverseBasic(): List[Int] = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.traverse(x => IO.pure(x * 2))
  }

  // =============================================================================
  // Parallel Collection Processing
  // =============================================================================

  @Benchmark
  def eruParSequence(): List[Int] = runEru {
    val effects = (1 to COLLECTION_SIZE).map(Eru.succeed(_)).toList
    // Use sequential for now since parSequence doesn't exist yet
    Eru.collectAll(effects)
  }

  @Benchmark
  def zioParSequence(): List[Int] = runZio {
    val effects = (1 to COLLECTION_SIZE).map(ZIO.succeed(_)).toList
    ZIO.collectAllPar(effects)
  }

  @Benchmark
  def ioParSequence(): List[Int] = runIO {
    val effects = (1 to COLLECTION_SIZE).map(IO.pure(_)).toList
    effects.parSequence
  }

  // =============================================================================
  // Parallel Traverse Operations
  // =============================================================================

  @Benchmark
  def eruParTraverse(): List[Int] = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    // Use sequential for now since parForeach doesn't exist yet
    Eru.foreach(items)(x => Eru.succeed(x * 2))
  }

  @Benchmark
  def zioParTraverse(): List[Int] = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreachPar(items)(x => ZIO.succeed(x * 2))
  }

  @Benchmark
  def ioParTraverse(): List[Int] = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.parTraverse(x => IO.pure(x * 2))
  }

  // =============================================================================
  // Collection Folding Operations
  // =============================================================================

  @Benchmark
  def eruFoldLeft(): Int = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.foldLeft(items)(0) { (acc, item) =>
      Eru.succeed(acc + item)
    }
  }

  @Benchmark
  def zioFoldLeft(): Int = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foldLeft(items)(0) { (acc, item) =>
      ZIO.succeed(acc + item)
    }
  }

  @Benchmark
  def ioFoldLeft(): Int = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.foldM(0) { (acc, item) =>
      IO.pure(acc + item)
    }
  }

  // =============================================================================
  // Collection with Error Handling
  // =============================================================================

  @Benchmark
  def eruTraverseWithErrors(): List[Int] = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.foreach(items) { x =>
      if (x % 7 == 0) {
        Eru.fail("error").attempt.map {
          case Result.Success(v) => v
          case Result.Failure(_) => x * 2
        }
      } else {
        Eru.succeed(x * 2)
      }
    }
  }

  @Benchmark
  def zioTraverseWithErrors(): List[Int] = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreach(items) { x =>
      if (x % 7 == 0) {
        ZIO.fail("error").either.map {
          case Right(v) => v
          case Left(_) => x * 2
        }
      } else {
        ZIO.succeed(x * 2)
      }
    }
  }

  @Benchmark
  def ioTraverseWithErrors(): List[Int] = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.traverse { x =>
      if (x % 7 == 0) {
        IO.raiseError[Int](new RuntimeException("error")).attempt.map {
          case Right(v) => v
          case Left(_) => x * 2
        }
      } else {
        IO.pure(x * 2)
      }
    }
  }

  // =============================================================================
  // Mixed Sequential and Parallel Patterns
  // =============================================================================

  @Benchmark
  def eruMixedPattern(): Int = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    for {
      // Sequential processing
      doubled <- Eru.foreach(items)(x => Eru.succeed(x * 2))
      // Sequential aggregation (parallel will be added in Phase 5)
      results <- Eru.collectAll(doubled.map(Eru.succeed(_)))
      // Final reduction
      sum <- Eru.succeed(results.sum)
    } yield sum
  }

  @Benchmark
  def zioMixedPattern(): Int = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    for {
      // Sequential processing
      doubled <- ZIO.foreach(items)(x => ZIO.succeed(x * 2))
      // Parallel aggregation
      results <- ZIO.collectAllPar(doubled.map(ZIO.succeed(_)))
      // Final reduction
      sum <- ZIO.succeed(results.sum)
    } yield sum
  }

  @Benchmark
  def ioMixedPattern(): Int = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    for {
      // Sequential processing
      doubled <- items.traverse(x => IO.pure(x * 2))
      // Parallel aggregation
      results <- doubled.map(IO.pure(_)).parSequence
      // Final reduction
      sum <- IO.pure(results.sum)
    } yield sum
  }
}
