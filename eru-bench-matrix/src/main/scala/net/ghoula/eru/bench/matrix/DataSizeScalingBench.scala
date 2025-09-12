package net.ghoula.eru.bench.matrix

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Data Size Scaling Matrix Benchmarks
  *
  * Tests performance scaling across different data size parameters:
  *   - Collection size scaling (10, 100, 1K elements)
  *   - Payload size scaling (1KB, 10KB, 100KB data)
  *   - Memory allocation patterns with increasing data sizes
  *   - Throughput characteristics under memory pressure
  *   - GC impact with varying allocation rates
  *
  * Key metrics to analyze:
  *   - Throughput vs collection size relationship
  *   - Memory allocation overhead patterns
  *   - GC frequency and pause time impact
  *   - Cache efficiency with different data sizes
  */
class DataSizeScalingBench extends MatrixBenchmarkBase {

  // =============================================================================
  // Collection Size Scaling Tests
  // =============================================================================

  @Benchmark
  def eruCollectionProcessingScaling(): List[Int] = runEru {
    val collection = (1 to collectionSize).toList
    val effects = collection.map(i => generateWorkload(i))
    executeParallelEru(effects)
  }

  @Benchmark
  def zioCollectionProcessingScaling(): List[Int] = runZio {
    val collection = (1 to collectionSize).toList  
    val effects = collection.map(i => generateZioWorkload(i))
    executeParallelZio(effects)
  }

  @Benchmark
  def ioCollectionProcessingScaling(): List[Int] = runIO {
    val collection = (1 to collectionSize).toList
    val effects = collection.map(i => generateIOWorkload(i))
    executeParallelIO(effects)
  }

  // =============================================================================
  // Sequential Collection Processing
  // =============================================================================

  @Benchmark
  def eruSequentialCollectionScaling(): List[Int] = runEru {
    val collection = (1 to collectionSize).toList
    collection.foldLeft(Eru.succeed(List.empty[Int])) { (acc, item) =>
      for {
        list <- acc
        processed <- generateWorkload(item)
      } yield processed :: list
    }.map(_.reverse)
  }

  @Benchmark
  def zioSequentialCollectionScaling(): List[Int] = runZio {
    val collection = (1 to collectionSize).toList
    collection.foldLeft(ZIO.succeed(List.empty[Int])) { (acc, item) =>
      for {
        list <- acc  
        processed <- generateZioWorkload(item)
      } yield processed :: list
    }.map(_.reverse)
  }

  @Benchmark
  def ioSequentialCollectionScaling(): List[Int] = runIO {
    val collection = (1 to collectionSize).toList
    collection.foldLeft(IO.pure(List.empty[Int])) { (acc, item) =>
      for {
        list <- acc
        processed <- generateIOWorkload(item)
      } yield processed :: list  
    }.map(_.reverse)
  }

  // =============================================================================
  // Payload Size Scaling Tests
  // =============================================================================

  @Benchmark
  def eruPayloadScaling(): Array[Byte] = runEru {
    val data = generateTestData()
    Eru.succeed {
      // Simulate processing payload
      val processed = new Array[Byte](data.length)
      for (i <- data.indices) {
        processed(i) = ((data(i) + i) % 256).toByte
      }
      processed
    }
  }

  @Benchmark
  def zioPayloadScaling(): Array[Byte] = runZio {
    val data = generateTestData()
    ZIO.succeed {
      val processed = new Array[Byte](data.length)
      for (i <- data.indices) {
        processed(i) = ((data(i) + i) % 256).toByte  
      }
      processed
    }
  }

  @Benchmark
  def ioPayloadScaling(): Array[Byte] = runIO {
    val data = generateTestData()
    IO {
      val processed = new Array[Byte](data.length)
      for (i <- data.indices) {
        processed(i) = ((data(i) + i) % 256).toByte
      }
      processed
    }
  }

  // =============================================================================
  // Memory Allocation Scaling Tests
  // =============================================================================

  @Benchmark
  def eruAllocationScaling(): List[Array[Byte]] = runEru {
    val effects = (1 to collectionSize).map { _ =>
      Eru.succeed {
        val size = dataSize match {
          case "small" => 1024
          case "medium" => 10 * 1024  
          case "large" => 100 * 1024
        }
        new Array[Byte](size)
      }
    }.toList
    
    executeParallelEru(effects)
  }

  @Benchmark
  def zioAllocationScaling(): List[Array[Byte]] = runZio {
    val effects = (1 to collectionSize).map { _ =>
      ZIO.succeed {
        val size = dataSize match {
          case "small" => 1024
          case "medium" => 10 * 1024
          case "large" => 100 * 1024  
        }
        new Array[Byte](size)
      }
    }.toList
    
    executeParallelZio(effects)
  }

  @Benchmark  
  def ioAllocationScaling(): List[Array[Byte]] = runIO {
    val effects = (1 to collectionSize).map { _ =>
      IO {
        val size = dataSize match {
          case "small" => 1024
          case "medium" => 10 * 1024
          case "large" => 100 * 1024
        }
        new Array[Byte](size)
      }
    }.toList
    
    executeParallelIO(effects)
  }

  // =============================================================================
  // Batch Processing Scaling Tests  
  // =============================================================================

  @Benchmark
  def eruBatchProcessingScaling(): List[List[Int]] = runEru {
    val batchSize = collectionSize / 10 max 1
    val batches = (1 to collectionSize).grouped(batchSize).toList
    
    val batchEffects = batches.map { batch =>
      val batchWork = batch.toList.map(i => generateWorkload(i))
      executeParallelEru(batchWork)
    }
    
    executeParallelEru(batchEffects)
  }

  @Benchmark
  def zioBatchProcessingScaling(): List[List[Int]] = runZio {
    val batchSize = collectionSize / 10 max 1
    val batches = (1 to collectionSize).grouped(batchSize).toList
    
    val batchEffects = batches.map { batch =>
      val batchWork = batch.toList.map(i => generateZioWorkload(i))
      executeParallelZio(batchWork)
    }
    
    executeParallelZio(batchEffects)
  }

  @Benchmark
  def ioBatchProcessingScaling(): List[List[Int]] = runIO {
    val batchSize = collectionSize / 10 max 1  
    val batches = (1 to collectionSize).grouped(batchSize).toList
    
    val batchEffects = batches.map { batch =>
      val batchWork = batch.toList.map(i => generateIOWorkload(i))
      executeParallelIO(batchWork)
    }
    
    executeParallelIO(batchEffects)
  }

  // =============================================================================
  // Memory-Intensive State Operations
  // =============================================================================

  @Benchmark
  def eruMemoryIntensiveState(): Int = runEru {
    for {
      ref <- Eru.ref(new Array[Int](collectionSize))
      stateEffects = (1 to collectionSize).map { i =>
        ref.update { arr =>
          val newArr = arr.clone()
          if (i < newArr.length) newArr(i) = i
          newArr
        }
      }.toList
      _ <- parSequence(stateEffects)
      finalArray <- ref.get
    } yield finalArray.sum
  }

  @Benchmark
  def zioMemoryIntensiveState(): Int = runZio {
    for {
      ref <- zio.Ref.make(new Array[Int](collectionSize))
      _ <- ZIO.collectAllPar((1 to collectionSize).map { i =>
        ref.update { arr =>
          val newArr = arr.clone()
          if (i < newArr.length) newArr(i) = i
          newArr
        }
      }.toList)
      finalArray <- ref.get
    } yield finalArray.sum
  }

  @Benchmark
  def ioMemoryIntensiveState(): Int = runIO {
    for {
      ref <- IO.ref(new Array[Int](collectionSize))
      _ <- (1 to collectionSize).map { i =>
        ref.update { arr =>
          val newArr = arr.clone()  
          if (i < newArr.length) newArr(i) = i
          newArr
        }
      }.toList.parSequence
      finalArray <- ref.get
    } yield finalArray.sum
  }

  // =============================================================================
  // Stream Processing Simulation
  // =============================================================================

  @Benchmark
  def eruStreamProcessingScaling(): List[Int] = runEru {
    def processChunk(chunk: List[Int]): Eru[Nothing, Int] = {
      chunk.foldLeft(Eru.succeed(0)) { (acc, item) =>
        for {
          sum <- acc
          processed <- generateWorkload(item)
        } yield sum + processed
      }
    }

    val chunks = (1 to collectionSize).grouped(10).toList
    val chunkEffects = chunks.map(chunk => processChunk(chunk.toList))
    executeParallelEru(chunkEffects)
  }

  @Benchmark  
  def zioStreamProcessingScaling(): List[Int] = runZio {
    def processChunk(chunk: List[Int]): ZIO[Any, Nothing, Int] = {
      chunk.foldLeft(ZIO.succeed(0)) { (acc, item) =>
        for {
          sum <- acc
          processed <- generateZioWorkload(item)
        } yield sum + processed  
      }
    }

    val chunks = (1 to collectionSize).grouped(10).toList
    val chunkEffects = chunks.map(chunk => processChunk(chunk.toList))
    executeParallelZio(chunkEffects)
  }

  @Benchmark
  def ioStreamProcessingScaling(): List[Int] = runIO {
    def processChunk(chunk: List[Int]): IO[Int] = {
      chunk.foldLeft(IO.pure(0)) { (acc, item) =>
        for {
          sum <- acc
          processed <- generateIOWorkload(item)
        } yield sum + processed
      }
    }

    val chunks = (1 to collectionSize).grouped(10).toList
    val chunkEffects = chunks.map(chunk => processChunk(chunk.toList))
    executeParallelIO(chunkEffects)
  }

  // =============================================================================
  // Helper Methods for Platform-Specific Workload Generation
  // =============================================================================

  /** Generate ZIO workload based on workloadType parameter */
  private def generateZioWorkload(input: Int): ZIO[Any, Nothing, Int] = workloadType match {
    case "cpu-bound" => ZIO.succeed {
      var result = input
      for (i <- 1 to 100) {  // Reduced iterations for data scaling tests
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
      if (cpuResult % 100 == 0) Thread.sleep(1)  
      cpuResult
    }
  }

  /** Generate IO workload based on workloadType parameter */  
  private def generateIOWorkload(input: Int): IO[Int] = workloadType match {
    case "cpu-bound" => IO {
      var result = input
      for (i <- 1 to 100) {
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
      if (cpuResult % 100 == 0) Thread.sleep(1)
      cpuResult
    }
  }
}