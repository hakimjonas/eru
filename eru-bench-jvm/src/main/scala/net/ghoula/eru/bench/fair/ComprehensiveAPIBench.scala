package net.ghoula.eru.bench.fair

import cats.effect.std.{Queue as IOQueue, Semaphore as IOSemaphore}
import cats.effect.syntax.all.*
import cats.effect.{Deferred as IODeferred, IO, Ref as IORef}
import cats.syntax.all.*
import org.openjdk.jmh.annotations.*
import zio.{Promise as ZPromise, Queue as ZQueue, Ref as ZRef, Semaphore as ZSemaphore, ZIO}

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*
import net.ghoula.eru.{Queue, Ref, Semaphore}

/** Comprehensive API Benchmark Suite
  *
  * Tests all major Eru APIs against equivalent operations in Cats Effect and ZIO. Reports
  * performance vs the better-performing competitor for each operation.
  *
  * Categories:
  *   1. Core operations (flatMap, map, error handling)
  *   2. Concurrency primitives (fork, race, zipPar)
  *   3. Coordination (Promise, Queue, Semaphore, Ref)
  *   4. Collection operations (parSequence, parTraverse, raceAll)
  *   5. Error handling (recover, recoverWith, attempt)
  *   6. Resource management (bracket, ensuring)
  */
class ComprehensiveAPIBench extends FairBenchmarkBase {

  // =============================================================================
  // Core Effect Construction & Composition (with actual effects)
  // =============================================================================

  @Benchmark
  def eruFlatMapChain(): Int = runEru {
    Eru
      .effect(TEST_VALUE)
      .flatMap(x => Eru.effect(x * 2))
      .flatMap(x => Eru.effect(x + 1))
      .flatMap(x => Eru.succeed(x * 3))
  }

  @Benchmark
  def zioFlatMapChain(): Int = runZio {
    ZIO
      .attempt(TEST_VALUE)
      .flatMap(x => ZIO.attempt(x * 2))
      .flatMap(x => ZIO.attempt(x + 1))
      .flatMap(x => ZIO.succeed(x * 3))
  }

  @Benchmark
  def ioFlatMapChain(): Int = runIO {
    IO.delay(TEST_VALUE)
      .flatMap(x => IO.delay(x * 2))
      .flatMap(x => IO.delay(x + 1))
      .flatMap(x => IO.pure(x * 3))
  }

  // =============================================================================
  // Error Handling (with actual errors)
  // =============================================================================

  @Benchmark
  def eruErrorRecovery(): String = runEru {
    Eru.effect {
      if (TEST_VALUE % 2 == 0) throw new Exception("error")
      else "success"
    }.recover { case _: Exception => "recovered" }
  }

  @Benchmark
  def zioErrorRecovery(): String = runZio {
    ZIO.attempt {
      if (TEST_VALUE % 2 == 0) throw new Exception("error")
      else "success"
    }.orElse(ZIO.succeed("recovered"))
  }

  @Benchmark
  def ioErrorRecovery(): String = runIO {
    IO.delay {
      if (TEST_VALUE % 2 == 0) throw new Exception("error")
      else "success"
    }.handleError { _ => "recovered" }
  }

  // =============================================================================
  // Promise/Deferred Operations
  // =============================================================================

  @Benchmark
  def eruPromiseComplete(): Int = runEru {
    for {
      promise <- Eru.promise[Nothing, Int]
      _ <- promise.succeed(42).eru.fork
      result <- promise.await.eru
    } yield result
  }

  @Benchmark
  def zioPromiseComplete(): Int = runZio {
    for {
      promise <- ZPromise.make[Nothing, Int]
      _ <- promise.succeed(42).fork
      result <- promise.await
    } yield result
  }

  @Benchmark
  def ioPromiseComplete(): Int = runIO {
    for {
      deferred <- IODeferred[IO, Int]
      _ <- deferred.complete(42).start
      result <- deferred.get
    } yield result
  }

  // =============================================================================
  // Queue Operations (bounded)
  // =============================================================================

  @Benchmark
  def eruQueueOfferTake(): Int = runEru {
    for {
      queue <- Queue.bounded[Int](10)
      _ <- queue.put(42).eru
      _ <- queue.put(43).eru
      first <- queue.take.eru
      second <- queue.take.eru
    } yield first + second
  }

  @Benchmark
  def zioQueueOfferTake(): Int = runZio {
    for {
      queue <- ZQueue.bounded[Int](10)
      _ <- queue.offer(42)
      _ <- queue.offer(43)
      first <- queue.take
      second <- queue.take
    } yield first + second
  }

  @Benchmark
  def ioQueueOfferTake(): Int = runIO {
    for {
      queue <- IOQueue.bounded[IO, Int](10)
      _ <- queue.offer(42)
      _ <- queue.offer(43)
      first <- queue.take
      second <- queue.take
    } yield first + second
  }

  // =============================================================================
  // Semaphore Operations
  // =============================================================================

  @Benchmark
  def eruSemaphoreAcquireRelease(): Int = runEru {
    for {
      sem <- Semaphore.make(2)
      _ <- sem.acquire.eru
      _ <- sem.acquire.eru
      _ <- sem.release.eru
      _ <- sem.release.eru
    } yield 42
  }

  @Benchmark
  def zioSemaphoreAcquireRelease(): Int = runZio {
    for {
      sem <- ZSemaphore.make(2)
      _ <- sem.withPermit(ZIO.unit)
      _ <- sem.withPermit(ZIO.unit)
    } yield 42
  }

  @Benchmark
  def ioSemaphoreAcquireRelease(): Int = runIO {
    for {
      sem <- IOSemaphore[IO](2)
      _ <- sem.acquire
      _ <- sem.acquire
      _ <- sem.release
      _ <- sem.release
    } yield 42
  }

  // =============================================================================
  // Ref Operations
  // =============================================================================

  @Benchmark
  def eruRefModify(): Int = runEru {
    for {
      ref <- Ref.make(0)
      _ <- ref.update(_ + 1)
      _ <- ref.update(_ * 2)
      _ <- ref.modify(x => (x + 10, x))
      result <- ref.get
    } yield result
  }

  @Benchmark
  def zioRefModify(): Int = runZio {
    for {
      ref <- ZRef.make(0)
      _ <- ref.update(_ + 1)
      _ <- ref.update(_ * 2)
      _ <- ref.modify(x => (x, x + 10))
      result <- ref.get
    } yield result
  }

  @Benchmark
  def ioRefModify(): Int = runIO {
    for {
      ref <- IORef[IO].of(0)
      _ <- ref.update(_ + 1)
      _ <- ref.update(_ * 2)
      _ <- ref.modify(x => (x + 10, x))
      result <- ref.get
    } yield result
  }

  // =============================================================================
  // Parallel Collection Operations (with effects)
  // =============================================================================

  @Benchmark
  def eruParSequence(): Int = runEru {
    val effects = (1 to 5).map(i => Eru.effect(i * 2)).toList
    runtime.parSequence(effects).map(_.sum)
  }

  @Benchmark
  def zioParSequence(): Int = runZio {
    val effects = (1 to 5).map(i => ZIO.attempt(i * 2))
    ZIO.collectAllPar(effects).map(_.sum)
  }

  @Benchmark
  def ioParSequence(): Int = runIO {
    val effects = (1 to 5).map(i => IO.delay(i * 2)).toList
    effects.parSequence.map(_.sum)
  }

  // =============================================================================
  // ParTraverse Operations
  // =============================================================================

  @Benchmark
  def eruParTraverse(): Int = runEru {
    val inputs = List(1, 2, 3, 4, 5)
    runtime.parTraverse(inputs)(i => Eru.effect(i * 10)).map(_.sum)
  }

  @Benchmark
  def zioParTraverse(): Int = runZio {
    val inputs = List(1, 2, 3, 4, 5)
    ZIO.foreachPar(inputs)(i => ZIO.attempt(i * 10)).map(_.sum)
  }

  @Benchmark
  def ioParTraverse(): Int = runIO {
    val inputs = List(1, 2, 3, 4, 5)
    inputs.parTraverse(i => IO.delay(i * 10)).map(_.sum)
  }

  // =============================================================================
  // RaceAll Operations
  // =============================================================================

  @Benchmark
  def eruRaceAll(): Int = runEru {
    val effects = (1 to 3)
      .map(i =>
        Eru.effect {
          try {
            Thread.sleep(i)
            i * 10
          } catch {
            case _: InterruptedException => i * 10
          }
        }
      )
      .toList
    runtime.raceAll(effects).map(_._1)
  }

  @Benchmark
  def zioRaceAll(): Int = runZio {
    val effects = (1 to 3)
      .map(i =>
        ZIO.attempt {
          try {
            Thread.sleep(i)
            i * 10
          } catch {
            case _: InterruptedException => i * 10
          }
        }
      )
      .toList
    ZIO.raceAll(effects.head, effects.tail)
  }

  @Benchmark
  def ioRaceAll(): Int = runIO {
    def raceMany[A](effects: List[IO[A]]): IO[A] = effects match {
      case Nil => IO.raiseError(new NoSuchElementException)
      case single :: Nil => single
      case first :: second :: rest =>
        val paired = IO.race(first, second).map(_.merge)
        raceMany(paired :: rest)
    }

    val effects = (1 to 3)
      .map(i =>
        IO.delay {
          try {
            Thread.sleep(i)
            i * 10
          } catch {
            case _: InterruptedException => i * 10
          }
        }
      )
      .toList
    raceMany(effects)
  }

  // =============================================================================
  // Bracket/Resource Management
  // =============================================================================

  @Benchmark
  def eruBracket(): Int = runEru {
    Eru
      .effect(TEST_VALUE)
      .bracket(_ => Eru.unit) { resource =>
        Eru.effect(resource * 2)
      }
  }

  @Benchmark
  def zioBracket(): Int = runZio {
    ZIO.acquireReleaseWith(
      ZIO.attempt(TEST_VALUE)
    )(_ => ZIO.unit) { resource =>
      ZIO.attempt(resource * 2)
    }
  }

  @Benchmark
  def ioBracket(): Int = runIO {
    IO.delay(TEST_VALUE)
      .bracket(resource => IO.delay(resource * 2))(_ => IO.unit)
  }

  // =============================================================================
  // Sleep/Timeout Operations (very short delays for benchmarking)
  // =============================================================================

  @Benchmark
  def eruTimeout(): Option[Int] = runEru {
    val effect = Eru.effect {
      // Fast path - completes before timeout
      TEST_VALUE
    }
    effect
      .timeout(java.time.Duration.ofSeconds(1)) // 1s timeout - never triggers
      .map(Some(_))
      .recover { case _: java.util.concurrent.TimeoutException => None }
  }

  @Benchmark
  def zioTimeout(): Option[Int] = runZio {
    val effect = ZIO.attempt {
      // Fast path - completes before timeout
      TEST_VALUE
    }
    effect.timeout(zio.Duration.fromSeconds(1))
  }

  @Benchmark
  def ioTimeout(): Option[Int] = runIO {
    val effect = IO.delay {
      // Fast path - completes before timeout
      TEST_VALUE
    }
    effect
      .timeout(scala.concurrent.duration.Duration(1, TimeUnit.SECONDS))
      .map(Some(_))
      .handleError(_ => None)
  }

  // =============================================================================
  // ForeachParN (bounded parallelism)
  // =============================================================================

  @Benchmark
  def eruForeachParN(): Int = runEru {
    val inputs = (1 to 10).toList
    runtime.foreachParN(3, inputs)(i => Eru.effect(i * 2)).map(_.sum)
  }

  @Benchmark
  def zioForeachParN(): Int = runZio {
    val inputs = (1 to 10).toList
    ZIO.foreachPar(inputs)(i => ZIO.attempt(i * 2)).map(_.sum)
  }

  @Benchmark
  def ioForeachParN(): Int = runIO {
    val inputs = (1 to 10).toList
    inputs.parTraverseN(3)(i => IO.delay(i * 2)).map(_.sum)
  }
}
