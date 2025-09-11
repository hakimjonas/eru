package net.ghoula.eru.bench.fair

import cats.effect.IO
import org.openjdk.jmh.annotations.*
import zio.ZIO

import net.ghoula.eru.prelude.*

/** Category 4: Coordination Primitives Benchmarks
  * 
  * Tests concurrent coordination mechanisms:
  * - Deferred/Promise operations
  * - Semaphore operations
  * - Basic synchronization patterns
  * - Producer/consumer coordination
  * 
  * Expected runtime: ~4 minutes
  * Coverage: Inter-fiber coordination and synchronization
  */
class CoordinationBench extends FairBenchmarkBase {

  // =============================================================================
  // Basic Deferred/Promise Operations
  // =============================================================================

  @Benchmark
  def eruDeferredBasic(): Int = runEru {
    for {
      deferred <- Eru.deferred[Int]
      _ <- deferred.complete(TEST_VALUE)
      result <- deferred.await
    } yield result
  }

  @Benchmark
  def zioPromiseBasic(): Int = runZio {
    for {
      promise <- zio.Promise.make[Nothing, Int]
      _ <- promise.succeed(TEST_VALUE)
      result <- promise.await
    } yield result
  }

  @Benchmark
  def ioDeferredBasic(): Int = runIO {
    for {
      deferred <- cats.effect.Deferred[IO, Int]
      _ <- deferred.complete(TEST_VALUE)
      result <- deferred.get
    } yield result
  }

  // =============================================================================
  // Multiple Deferred Operations
  // =============================================================================

  @Benchmark
  def eruMultipleDeferred(): Int = runEru {
    for {
      d1 <- Eru.deferred[Int]
      d2 <- Eru.deferred[Int] 
      d3 <- Eru.deferred[Int]
      _ <- d1.complete(10)
      _ <- d2.complete(20)
      _ <- d3.complete(12)
      a <- d1.await
      b <- d2.await
      c <- d3.await
      result <- Eru.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def zioMultiplePromise(): Int = runZio {
    for {
      p1 <- zio.Promise.make[Nothing, Int]
      p2 <- zio.Promise.make[Nothing, Int]
      p3 <- zio.Promise.make[Nothing, Int]
      _ <- p1.succeed(10)
      _ <- p2.succeed(20)
      _ <- p3.succeed(12)
      a <- p1.await
      b <- p2.await
      c <- p3.await
      result <- ZIO.succeed(a + b + c)
    } yield result
  }

  @Benchmark
  def ioMultipleDeferred(): Int = runIO {
    for {
      d1 <- cats.effect.Deferred[IO, Int]
      d2 <- cats.effect.Deferred[IO, Int]
      d3 <- cats.effect.Deferred[IO, Int]
      _ <- d1.complete(10)
      _ <- d2.complete(20)
      _ <- d3.complete(12)
      a <- d1.get
      b <- d2.get
      c <- d3.get
      result <- IO.pure(a + b + c)
    } yield result
  }

  // =============================================================================
  // Semaphore Operations (where available)
  // =============================================================================

  @Benchmark
  def eruSemaphoreBasic(): Int = runEru {
    for {
      sem <- Eru.semaphore(1)
      ref <- Eru.ref(0)
      result <- sem.withPermit(ref.update(_ + TEST_VALUE).flatMap(_ => ref.get))
        .map(_.getOrElse(0))
    } yield result
  }

  @Benchmark
  def zioSemaphoreBasic(): Int = runZio {
    for {
      sem <- zio.Semaphore.make(1)
      ref <- zio.Ref.make(0)
      result <- sem.withPermit(ref.update(_ + TEST_VALUE) *> ref.get)
    } yield result
  }

  @Benchmark
  def ioSemaphoreBasic(): Int = runIO {
    for {
      sem <- cats.effect.std.Semaphore[IO](1)
      ref <- cats.effect.Ref[IO].of(0)
      result <- sem.permit.use(_ => ref.update(_ + TEST_VALUE) *> ref.get)
    } yield result
  }

  // =============================================================================
  // Combined Coordination (Ref + Deferred)
  // =============================================================================

  @Benchmark
  def eruCombinedCoordination(): Int = runEru {
    for {
      ref <- Eru.ref(0)
      deferred <- Eru.deferred[Int]
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      current <- ref.get
      _ <- deferred.complete(current + 12)
      result <- deferred.await
    } yield result
  }

  @Benchmark
  def zioCombinedCoordination(): Int = runZio {
    for {
      ref <- zio.Ref.make(0)
      promise <- zio.Promise.make[Nothing, Int]
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      current <- ref.get
      _ <- promise.succeed(current + 12)
      result <- promise.await
    } yield result
  }

  @Benchmark
  def ioCombinedCoordination(): Int = runIO {
    for {
      ref <- cats.effect.Ref[IO].of(0)
      deferred <- cats.effect.Deferred[IO, Int]
      _ <- ref.update(_ + 10)
      _ <- ref.update(_ + 20)
      current <- ref.get
      _ <- deferred.complete(current + 12)
      result <- deferred.get
    } yield result
  }
}