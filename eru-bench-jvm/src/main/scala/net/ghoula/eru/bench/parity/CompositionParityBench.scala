package net.ghoula.eru.bench.parity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.{UIO, Unsafe, ZIO}

import java.util.concurrent.TimeUnit

import net.ghoula.eru.CorePrelude.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
  value = 1,
  jvmArgs = Array("-server", "-Xms2G", "-Xmx2G", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions")
)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class CompositionParityBench {

  @Param(Array("8", "32", "64", "128"))
  var depthStr: String = "32"

  @Param(Array("success", "shortCircuitAtHalf"))
  var path: String = "success"

  private var depth: Int = 32

  private var eruProg: Eru[String, Int] = Eru.succeed(0)
  private var zioProg: UIO[Int] = ZIO.succeed(0)
  private var ceProg: IO[Int] = IO.pure(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    depth = depthStr.toInt
    val failAt = if (path == "shortCircuitAtHalf") depth / 2 else -1

    // Eru
    var e: Eru[String, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) {
      if (failAt >= 0 && i == failAt) e = e.flatMap(_ => Eru.fail("boom"))
      else e = e.flatMap(n => Eru.succeed(n + 1))
      i += 1
    }
    eruProg = e.recover { case _ => -1 }

    // ZIO
    var z = ZIO.succeed(0)
    i = 0
    while (i < depth) {
      if (failAt >= 0 && i == failAt)
        z = z.flatMap(_ => ZIO.fail("boom")).either.map(_.fold(_ => -1, identity))
      else z = z.flatMap(n => ZIO.succeed(n + 1))
      i += 1
    }
    zioProg = z

    // Cats Effect
    var c = IO.pure(0)
    i = 0
    while (i < depth) {
      if (failAt >= 0 && i == failAt)
        c = c.flatMap(_ => IO.raiseError(new RuntimeException("boom"))).attempt.map(_.fold(_ => -1, identity))
      else c = c.flatMap(n => IO.pure(n + 1))
      i += 1
    }
    ceProg = c
  }

  @Benchmark def eru(): Int = eruProg.unsafeRunSync()
  @Benchmark def zio(): Int = Unsafe.unsafe { implicit u =>
    _root_.zio.Runtime.default.unsafe.run(zioProg).getOrThrowFiberFailure()
  }
  @Benchmark def catsEffect(): Int = ceProg.unsafeRunSync()
}
