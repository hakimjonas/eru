package net.ghoula.eru.bench.parity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.{UIO, Unsafe, ZIO}

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class RunnerOverheadParityBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Param(Array("small", "medium"))
  var size: String = "small"

  private var eruSuccess: Eru[Nothing, Int] = Eru.succeed(0)
  private var zioSuccess: UIO[Int] = ZIO.succeed(0)
  private var ceSuccess: IO[Int] = IO.pure(0)

  private var eruFallback: Eru[String, Int] = Eru.succeed(0)
  private var zioFallback: UIO[Int] = ZIO.succeed(0)
  private var ceFallback: IO[Int] = IO.pure(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    size match {
      case "small" =>
        eruSuccess = Eru.succeed(1).map(_ + 1)
        zioSuccess = ZIO.succeed(1).map(_ + 1)
        ceSuccess = IO.pure(1).map(_ + 1)

        eruFallback = Eru.fail("boom").recover { case _ => 1 }
        zioFallback = ZIO.fail("boom").catchAll(_ => ZIO.succeed(1))
        ceFallback = IO.raiseError(new RuntimeException("boom")).handleError(_ => 1)

      case _ =>
        var e: Eru[Nothing, Int] = Eru.succeed(0)
        var z: UIO[Int] = ZIO.succeed(0)
        var c: IO[Int] = IO.pure(0)
        var i = 0; while (i < 64) { e = e.flatMap(n => Eru.succeed(n + 1)); i += 1 }
        i = 0; while (i < 64) { z = z.flatMap(n => ZIO.succeed(n + 1)); i += 1 }
        i = 0; while (i < 64) { c = c.flatMap(n => IO.pure(n + 1)); i += 1 }

        eruSuccess = e
        zioSuccess = z
        ceSuccess = c

        eruFallback = Eru.succeed(0).flatMap(_ => Eru.fail("x")).recover { case _ => 42 }
        zioFallback = ZIO.succeed(0).flatMap(_ => ZIO.fail("x")).catchAll(_ => ZIO.succeed(42))
        ceFallback = IO.pure(0).flatMap(_ => IO.raiseError(new RuntimeException("x"))).handleError(_ => 42)
    }
  }

  @Benchmark def eruUnsafe(): Int = eruSuccess.unsafeRunSync()
  @Benchmark def zioUnsafe(): Int = Unsafe.unsafe { implicit u =>
    _root_.zio.Runtime.default.unsafe.run(zioSuccess).getOrThrowFiberFailure()
  }
  @Benchmark def ceUnsafe(): Int = ceSuccess.unsafeRunSync()

  @Benchmark def eruFallbackPath(): Int = eruFallback.unsafeRunSync()
  @Benchmark def zioFallbackPath(): Int = Unsafe.unsafe { implicit u =>
    _root_.zio.Runtime.default.unsafe.run(zioFallback).getOrThrowFiberFailure()
  }
  @Benchmark def ceFallbackPath(): Int = ceFallback.unsafeRunSync()
}
