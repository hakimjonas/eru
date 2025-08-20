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
class CompetitiveBench {

  @Param(Array("10", "100", "200", "400", "800"))
  var depth: Int = uninitialized

  // --- Programs to be benchmarked ---
  var eruProgram: Eru[Throwable, Int] = uninitialized
  var zioProgram: UIO[Int] = uninitialized
  var catsProgram: IO[Int] = uninitialized

  // --- Pure continuation functions ---
  private val pureEru: Int => Eru[Throwable, Int] = Eru.succeed
  private val pureZio: Int => UIO[Int] = ZIO.succeed
  private val pureCats: Int => IO[Int] = IO.pure

  @Setup(Level.Trial)
  def setup(): Unit = {
    // --- Eru Setup ---
    var eru: Eru[Throwable, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) {
      if (i % 4 == 0) {
        // Force a new effectful operation at each step
        eru = eru.flatMap(_ => Eru.effect(i))
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
      if (i % 4 == 0) {
        // ZIO.succeed is effectful and constructs a new ZIO value
        zio = zio.flatMap(_ => ZIO.succeed(i))
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
      if (i % 4 == 0) {
        // IO.apply is effectful and captures the expression
        cats = cats.flatMap(_ => IO(i))
      } else {
        cats = cats.flatMap(pureCats)
      }
      i += 1
    }
    catsProgram = cats
  }

  // --- Benchmarks ---

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
}
