package net.ghoula.eru.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.{UIO, Unsafe, ZIO}

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import net.ghoula.eru.Eru

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(2) // Multiple forks for more reliable results
class CompetitiveBench {

  // Extended depth range with more points around the crossover area (300-800)
  @Param(Array("10", "50", "100", "200", "300", "400", "500", "600", "700", "800", "1200", "1600"))
  var depth: Int = uninitialized

  // --- Programs to be benchmarked ---
  var eruProgram: Eru[Throwable, Int] = uninitialized
  var zioProgram: UIO[Int] = uninitialized
  var catsProgram: IO[Int] = uninitialized

  // Pure chain programs for separate testing
  var eruPureProgram: Eru[Nothing, Int] = uninitialized
  var zioPureProgram: UIO[Int] = uninitialized
  var catsPureProgram: IO[Int] = uninitialized

  // --- Standardized effect constructors for fairness ---
  private val effectfulEru: Int => Eru[Throwable, Int] = i => Eru.effect(i)
  private val pureEru: Int => Eru[Throwable, Int] = Eru.succeed

  private val effectfulZio: Int => UIO[Int] = i => ZIO.attempt(i).orDie
  private val pureZio: Int => UIO[Int] = ZIO.succeed

  private val effectfulCats: Int => IO[Int] = i => IO(i)
  private val pureCats: Int => IO[Int] = IO.pure

  // Pure-only constructors
  private val pureOnlyEru: Int => Eru[Nothing, Int] = Eru.succeed
  private val pureOnlyZio: Int => UIO[Int] = ZIO.succeed
  private val pureOnlyCats: Int => IO[Int] = IO.pure

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Force GC before setup to start with clean heap
    System.gc()

    // More balanced work distribution: 50% effectful, 50% pure
    val effectfulRatio = 0.5

    // --- Mixed (Effectful + Pure) Setup ---

    // --- Eru Setup ---
    var eru: Eru[Throwable, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) {
      if (i.toDouble / depth < effectfulRatio) {
        eru = eru.flatMap(effectfulEru)
      } else {
        eru = eru.flatMap(pureEru)
      }
      i += 1
    }
    eruProgram = eru

    // --- ZIO Setup ---
    var zio: UIO[Int] = ZIO.succeed(0)
    i = 0
    while (i < depth) {
      if (i.toDouble / depth < effectfulRatio) {
        zio = zio.flatMap(effectfulZio)
      } else {
        zio = zio.flatMap(pureZio)
      }
      i += 1
    }
    zioProgram = zio

    // --- Cats Effect Setup ---
    var cats: IO[Int] = IO.pure(0)
    i = 0
    while (i < depth) {
      if (i.toDouble / depth < effectfulRatio) {
        cats = cats.flatMap(effectfulCats)
      } else {
        cats = cats.flatMap(pureCats)
      }
      i += 1
    }
    catsProgram = cats

    // --- Pure-only Chain Setup ---

    // --- Eru Pure Setup ---
    var eruPure: Eru[Nothing, Int] = Eru.succeed(0)
    i = 0
    while (i < depth) {
      eruPure = eruPure.flatMap(x => pureOnlyEru(x + 1))
      i += 1
    }
    eruPureProgram = eruPure

    // --- ZIO Pure Setup ---
    var zioPure: UIO[Int] = ZIO.succeed(0)
    i = 0
    while (i < depth) {
      zioPure = zioPure.flatMap(x => pureOnlyZio(x + 1))
      i += 1
    }
    zioPureProgram = zioPure

    // --- Cats Effect Pure Setup ---
    var catsPure: IO[Int] = IO.pure(0)
    i = 0
    while (i < depth) {
      catsPure = catsPure.flatMap(x => pureOnlyCats(x + 1))
      i += 1
    }
    catsPureProgram = catsPure
  }

  // --- Mixed (Effectful + Pure) Benchmarks ---

  @Benchmark
  def eru(): Int = {
    eruProgram.unsafeRunSync()
  }

  @Benchmark
  def zio(): Int = {
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(zioProgram).getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def catsEffect(): Int = {
    catsProgram.unsafeRunSync()
  }

  // --- Pure Chain Benchmarks ---

  @Benchmark
  def eruPureChain(): Int = {
    eruPureProgram.unsafeRunSync()
  }

  @Benchmark
  def zioPureChain(): Int = {
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(zioPureProgram).getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def catsEffectPureChain(): Int = {
    catsPureProgram.unsafeRunSync()
  }

  // --- Map Chain Benchmarks (Eru's strength) ---

  @Benchmark
  def eruMapChain(): Int = {
    var program: Eru[Nothing, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) {
      program = program.map(_ + 1)
      i += 1
    }
    program.unsafeRunSync()
  }

  @Benchmark
  def zioMapChain(): Int = {
    var program: UIO[Int] = ZIO.succeed(0)
    var i = 0
    while (i < depth) {
      program = program.map(_ + 1)
      i += 1
    }
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def catsEffectMapChain(): Int = {
    var program: IO[Int] = IO.pure(0)
    var i = 0
    while (i < depth) {
      program = program.map(_ + 1)
      i += 1
    }
    program.unsafeRunSync()
  }

  // --- Memory Pressure Test ---
  @TearDown(Level.Iteration)
  def cleanup(): Unit = {
    // Force GC between iterations to test under memory pressure
    System.gc()
  }
}
