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

  @Benchmark
  def eruForeachDiscard(): Unit = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.foreachDiscard(items)(x => Eru.succeed(x * 2))
  }

  @Benchmark
  def zioForeachDiscard(): Unit = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreachDiscard(items)(x => ZIO.succeed(x * 2))
  }

  @Benchmark
  def ioForeachDiscard(): Unit = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.traverse_(x => IO.pure(x * 2))
  }

  @Benchmark
  def eruCollectAllDiscard(): Unit = runEru {
    val effects = (1 to COLLECTION_SIZE).map(Eru.succeed(_)).toList
    Eru.collectAllDiscard(effects)
  }

  @Benchmark
  def zioCollectAllDiscard(): Unit = runZio {
    val effects = (1 to COLLECTION_SIZE).map(ZIO.succeed(_)).toList
    ZIO.collectAllDiscard(effects)
  }

  @Benchmark
  def ioCollectAllDiscard(): Unit = runIO {
    val effects = (1 to COLLECTION_SIZE).map(IO.pure(_)).toList
    effects.sequence_
  }

  // =============================================================================
  // Parallel Collection Processing
  // =============================================================================

  @Benchmark
  def eruParSequence(): List[Int] = runEru {
    val effects = (1 to COLLECTION_SIZE).map(Eru.succeed(_)).toList
    // Use runtime parSequence for parallel execution
    runtime.parSequence(effects)
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
    // Use runtime parTraverse for parallel execution
    runtime.parTraverse(items)(x => Eru.succeed(x * 2))
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

  @Benchmark
  def eruFoldRight(): Int = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.foldRight(items)(0) { (item, acc) =>
      Eru.succeed(item + acc)
    }
  }

  @Benchmark
  def zioFoldRight(): Int = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foldRight(items)(0) { (item, acc) =>
      ZIO.succeed(item + acc)
    }
  }

  @Benchmark
  def ioFoldRight(): Int = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.foldRight(IO.pure(0)) { (item, accIO) =>
      accIO.map(acc => item + acc)
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
      // Parallel aggregation using runtime parSequence
      results <- runtime.parSequence(doubled.map(Eru.succeed(_)))
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

  // =============================================================================
  // Partition Operations (Predicate-based)
  // =============================================================================

  @Benchmark
  def eruPartition(): (List[Int], List[Int]) = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    Eru.partition(items)(i => Eru.succeed(i % 2 == 0))
  }

  @Benchmark
  def zioPartition(): (List[Int], List[Int]) = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreach(items)(i => ZIO.succeed((i, i % 2 == 0))).map { results =>
      val (evens, odds) = results.partition(_._2)
      (evens.map(_._1), odds.map(_._1))
    }
  }

  @Benchmark
  def ioPartition(): (List[Int], List[Int]) = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.traverse(i => IO.pure(i % 2 == 0).map(pred => (i, pred))).map { results =>
      val (evens, odds) = results.partition(_._2)
      (evens.map(_._1), odds.map(_._1))
    }
  }

  // =============================================================================
  // Parallel Operations with Actual Work
  // =============================================================================

  @Benchmark
  def eruParSequenceWithWork(): List[Int] = runEru {
    val effects = (1 to COLLECTION_SIZE).map { i =>
      Eru.effect {
        // Simple work - the issue is sequential forking, not the work type
        scala.util.Random.nextInt(100) + i
      }
    }.toList
    runtime.parSequence(effects)
  }

  @Benchmark
  def zioParSequenceWithWork(): List[Int] = runZio {
    val effects = (1 to COLLECTION_SIZE).map { i =>
      ZIO.attempt {
        scala.util.Random.nextInt(100) + i
      }
    }.toList
    ZIO.collectAllPar(effects)
  }

  @Benchmark
  def ioParSequenceWithWork(): List[Int] = runIO {
    val effects = (1 to COLLECTION_SIZE).map { i =>
      IO.delay {
        scala.util.Random.nextInt(100) + i
      }
    }.toList
    effects.parSequence
  }

  @Benchmark
  def eruParTraverseWithWork(): List[Int] = runEru {
    val items = (1 to COLLECTION_SIZE).toList
    runtime.parTraverse(items) { x =>
      Eru.effect {
        // Simple work - the issue is sequential forking, not the work type
        scala.util.Random.nextInt(100) + x * 2
      }
    }
  }

  @Benchmark
  def zioParTraverseWithWork(): List[Int] = runZio {
    val items = (1 to COLLECTION_SIZE).toList
    ZIO.foreachPar(items) { x =>
      ZIO.attempt {
        scala.util.Random.nextInt(100) + x * 2
      }
    }
  }

  @Benchmark
  def ioParTraverseWithWork(): List[Int] = runIO {
    val items = (1 to COLLECTION_SIZE).toList
    items.parTraverse { x =>
      IO.delay {
        scala.util.Random.nextInt(100) + x * 2
      }
    }
  }
}
