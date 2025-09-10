package net.ghoula.eru.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import org.openjdk.jmh.annotations.*
import zio.{Unsafe, ZIO}

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.prelude.*

/** Comprehensive concurrency benchmarks comparing Eru, ZIO, and Cats Effect.
  *
  * This benchmark suite uses only public, user-facing APIs to provide fair comparison of real-world
  * usage patterns. Each library is tested using its natural idioms and recommended approaches,
  * reflecting actual developer experience.
  *
  * ==Philosophy: Fair Public API Testing==
  *
  * Rather than forcing artificial equivalence or using internal methods, these benchmarks respect
  * each library's design philosophy and measure performance as users would actually experience it
  * in production applications.
  *
  * ==Expected Performance Impact==
  *
  * Based on recent optimizations eliminating defensive error handling patterns, Eru should show
  * 20-35% improvements in primitive operations while maintaining clean, user-friendly APIs.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ConcurrencyBench {
  private val runtime = EruRuntime.create()
  given EruRuntime = runtime

  /** The contention level parameter - number of concurrent operations */
  @Param(Array("1", "4", "16"))
  var contention: Int = uninitialized

  /** The operation count parameter - operations per benchmark run */
  @Param(Array("100", "1000"))
  var operations: Int = uninitialized

  // =============================================================================
  // Ref Sequential Updates - Testing optimized Ref performance
  // =============================================================================

  /** Benchmarks Eru's Ref with sequential updates using natural API patterns.
    *
    * Uses the idiomatic Eru approach with for-comprehensions and standard combinators. This
    * exercises the optimized Ref implementation that eliminates defensive patterns.
    */
  @Benchmark
  def eruRefSequential(): Int = {
    val program = for {
      ref <- Eru.ref(0)
      _ <- Eru.foreachDiscard(1 to operations)(i => ref.update(_ + i))
      result <- ref.get
    } yield result

    program.unsafeRunSync()
  }

  /** Benchmarks ZIO's Ref with sequential updates using ZIO's natural patterns. */
  @Benchmark
  def zioRefSequential(): Int = {
    val program = for {
      ref <- zio.Ref.make(0)
      _ <- ZIO.foreachDiscard(1 to operations)(i => ref.update(_ + i))
      result <- ref.get
    } yield result

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's Ref with sequential updates using CE's natural patterns. */
  @Benchmark
  def catsEffectRefSequential(): Int = {
    val program = for {
      ref <- cats.effect.Ref[IO].of(0)
      _ <- (1 to operations).toList.traverse_(i => ref.update(_ + i).void)
      result <- ref.get
    } yield result

    program.unsafeRunSync()
  }

  // =============================================================================
  // Semaphore Basic Operations - Testing acquisition patterns
  // =============================================================================

  /** Benchmarks Eru's Semaphore using natural try/acquire/release patterns. */
  @Benchmark
  def eruSemaphoreBasic(): Int = {
    val program = for {
      semaphore <- Eru.semaphore(contention)
      ref <- Eru.ref(0)
      _ <- Eru.foreachDiscard(1 to operations) { _ =>
        semaphore.tryAcquire.flatMap { acquired =>
          if (acquired) {
            ref.update(_ + 1).flatMap(_ => semaphore.release)
          } else {
            Eru.unit
          }
        }
      }
      result <- ref.get
    } yield result

    program.unsafeRunSync()
  }

  /** Benchmarks ZIO's Semaphore using ZIO's natural patterns. */
  @Benchmark
  def zioSemaphoreBasic(): Int = {
    val program = for {
      semaphore <- zio.Semaphore.make(contention)
      results <- ZIO.foldLeft(1 to operations)(0) { (count, _) =>
        semaphore.withPermit(ZIO.succeed(count + 1))
      }
    } yield results

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's Semaphore using CE's natural patterns. */
  @Benchmark
  def catsEffectSemaphoreBasic(): Int = {
    val program = for {
      semaphore <- cats.effect.std.Semaphore[IO](contention)
      results <- (1 to operations).foldLeft(IO.pure(0)) { (acc, _) =>
        acc.flatMap { count =>
          semaphore.tryAcquire.flatMap { acquired =>
            if (acquired) {
              semaphore.release.as(count + 1)
            } else {
              IO.pure(count)
            }
          }
        }
      }
    } yield results

    program.unsafeRunSync()
  }

  // =============================================================================
  // Deferred/Promise Coordination - Testing blocking semantics
  // =============================================================================

  /** Benchmarks Eru's Deferred using natural producer/consumer patterns.
    *
    * This showcases the new blocking-based Deferred that eliminated polling. Uses fork/await
    * patterns that real users would write.
    */
  @Benchmark
  def eruDeferredBasic(): Int = {
    val program = for {
      deferred <- Eru.deferred[Int]
      waiterFiber <- Eru.fork(deferred.await)
      producerFiber <- Eru.fork {
        // Simulate some work before producing value
        Eru
          .foreachDiscard(1 to operations / 10)(_ => Eru.unit)
          .flatMap(_ => deferred.complete(operations))
      }
      _ <- producerFiber.await
      result <- waiterFiber.await.map {
        case Exit.Success(value) => value
        case _ => 0
      }
    } yield result

    program.unsafeRunSync()
  }

  /** Benchmarks ZIO's Promise using ZIO's natural patterns. */
  @Benchmark
  def zioPromiseBasic(): Int = {
    val program = for {
      promise <- zio.Promise.make[Nothing, Int]
      waiterFiber <- promise.await.fork
      producerFiber <- {
        ZIO.foreachDiscard(1 to operations / 10)(_ => ZIO.unit) *>
          promise.succeed(operations)
      }.fork
      _ <- producerFiber.join
      result <- waiterFiber.join
    } yield result

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's Deferred using CE's natural patterns. */
  @Benchmark
  def catsEffectDeferredBasic(): Int = {
    val program = for {
      deferred <- cats.effect.Deferred[IO, Int]
      waiterFiber <- deferred.get.start
      producerFiber <- {
        (1 to operations / 10).toList.traverse_(_ => IO.unit) *>
          deferred.complete(operations)
      }.start
      _ <- producerFiber.joinWithNever
      result <- waiterFiber.joinWithNever
    } yield result

    program.unsafeRunSync()
  }

  // =============================================================================
  // Mixed Coordination - Testing realistic patterns
  // =============================================================================

  /** Benchmarks Eru using multiple primitives together in realistic patterns.
    *
    * This tests how the optimizations compound when using multiple coordination primitives
    * together, which is common in real applications.
    */
  @Benchmark
  def eruMixedBasic(): Int = {
    val program = for {
      ref <- Eru.ref(0)
      semaphore <- Eru.semaphore(1)
      deferred <- Eru.deferred[Int]

      // Producer that coordinates access to shared state
      producer <- Eru.fork {
        Eru
          .foreachDiscard(1 to operations / 10) { i =>
            semaphore.tryAcquire.flatMap { acquired =>
              if (acquired) {
                ref.update(_ + i).flatMap(_ => semaphore.release)
              } else Eru.unit
            }
          }
          .flatMap { _ =>
            ref.get.flatMap(deferred.complete)
          }
      }

      // Consumer that waits for final result
      consumer <- Eru.fork(deferred.await)

      _ <- producer.await
      result <- consumer.await.map {
        case Exit.Success(value) => value
        case _ => 0
      }
    } yield result

    program.unsafeRunSync()
  }
}
