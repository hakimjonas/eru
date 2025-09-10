package net.ghoula.eru.bench.parity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.{Unsafe, ZIO}

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class ErrorHandlingParityBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Param(Array("success", "failure"))
  var path: String = "success"

  private var eruBase: Eru[String, Int] = Eru.succeed(0)
  private var zioBase: ZIO[Any, String, Int] = ZIO.succeed(0)
  private var ceBase: IO[Int] = IO.pure(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    path match {
      case "success" =>
        eruBase = Eru.succeed(42)
        zioBase = ZIO.succeed(42)
        ceBase = IO.pure(42)
      case _ =>
        eruBase = Eru.fail("boom")
        zioBase = ZIO.fail("boom")
        ceBase = IO.raiseError(new RuntimeException("boom"))
    }
  }

  @Benchmark def eruRecover(): Int =
    eruBase.recover { case "boom" => 1 }.unsafeRunSync()

  @Benchmark def zioRecover(): Int =
    Unsafe.unsafe { implicit u =>
      val p = zioBase.catchAll {
        case "boom" => ZIO.succeed(1)
        case other => ZIO.fail(other)
      }
      _root_.zio.Runtime.default.unsafe.run(p).getOrThrowFiberFailure()
    }

  @Benchmark def ceRecover(): Int =
    ceBase.handleErrorWith {
      case _: RuntimeException => IO.pure(1)
      case other => IO.raiseError(other)
    }.unsafeRunSync()
}
