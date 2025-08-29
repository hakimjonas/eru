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
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class ResourceParityBench {

  @Param(Array("success", "failure"))
  var outcome: String = "success"

  private var eruProg: Eru[String | Throwable, Int] = Eru.succeed(0)
  private var zioProg: UIO[Int] = ZIO.succeed(0)
  private var ceProg: IO[Int] = IO.pure(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    // Eru
    def eAcq: Eru[Throwable, String] = Eru.effect("res")
    def eRel(r: String): Eru[Throwable, Unit] = { val _ = r; Eru.effect(()) }
    def eUse(r: String): Eru[String, Int] = if (outcome == "success") Eru.succeed(r.length) else Eru.fail("boom")
    eruProg = eAcq.bracket(eRel)(eUse).recover { case _ => -1 }

    // ZIO
    def zAcq: UIO[String] = ZIO.succeed("res")
    def zRel(r: String): UIO[Unit] = { val _ = r; ZIO.succeed(()) }
    def zUse(r: String): ZIO[Any, String, Int] = if (outcome == "success") ZIO.succeed(r.length) else ZIO.fail("boom")
    zioProg = ZIO.acquireReleaseWith(zAcq)(zRel)(zUse).either.map(_.fold(_ => -1, identity))

    // Cats Effect
    def cAcq: IO[String] = IO.pure("res")
    def cRel(r: String): IO[Unit] = { val _ = r; IO.pure(()) }
    def cUse(r: String): IO[Int] = if (outcome == "success") IO.pure(r.length) else IO.raiseError(new RuntimeException("boom"))
    ceProg = cAcq.bracket(cUse)(cRel).attempt.map(_.fold(_ => -1, identity))
  }

  @Benchmark def eru(): Int = eruProg.unsafeRunSync()
  @Benchmark def zio(): Int = Unsafe.unsafe { implicit u => _root_.zio.Runtime.default.unsafe.run(zioProg).getOrThrowFiberFailure() }
  @Benchmark def catsEffect(): Int = ceProg.unsafeRunSync()
}
