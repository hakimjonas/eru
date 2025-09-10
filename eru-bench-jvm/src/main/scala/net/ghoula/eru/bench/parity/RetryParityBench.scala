package net.ghoula.eru.bench.parity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.{UIO, Unsafe, ZIO}

import java.time.Duration
import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*
import net.ghoula.eru.{CorePrelude as C, EruRuntime}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class RetryParityBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Param(Array("0", "1", "3", "5", "10"))
  var maxRetriesStr: String = "3"

  @Param(Array("1", "2", "3", "6", "12"))
  var successIndexStr: String = "3"

  private var maxRetries: Int = 3
  private var successAt: Int = 3

  private var eruProg: C.Eru[String | Throwable, Int] = C.Eru.succeed(0)
  private var zioProg: UIO[Int] = ZIO.succeed(0)
  private var ceProg: IO[Int] = IO.pure(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    maxRetries = math.max(0, maxRetriesStr.toInt)
    successAt = math.max(1, successIndexStr.toInt)

    // Eru with deterministic ZERO-delay policies
    def flakyEru: C.Eru[String | Throwable, Int] = {
      var attempts = 0
      C.Eru.effect { attempts += 1; attempts }.flatMap { n =>
        if n < successAt then C.Eru.fail("retry") else C.Eru.succeed(42)
      }
    }
    eruProg = flakyEru.retry(EruRuntime.Policy.Exponential(Duration.ZERO, maxRetries)).recover { case _ => -1 }

    // ZIO manual bounded retry (no sleep)
    def zioOnce(i: Int): ZIO[Any, String, Int] = if (i + 1 < successAt) ZIO.fail("retry") else ZIO.succeed(42)
    def zioRetry(i: Int): ZIO[Any, String, Int] = zioOnce(i).catchAll {
      case "retry" if i < maxRetries => zioRetry(i + 1)
      case other => ZIO.fail(other)
    }
    zioProg = zioRetry(0).either.map(_.fold(_ => -1, identity))

    // Cats Effect manual bounded retry (no sleep)
    def ceOnce(i: Int): IO[Int] = if (i + 1 < successAt) IO.raiseError(new RuntimeException("retry")) else IO.pure(42)
    def ceRetry(i: Int): IO[Int] = ceOnce(i).handleErrorWith {
      case _: RuntimeException if i < maxRetries => ceRetry(i + 1)
      case e => IO.raiseError(e)
    }
    ceProg = ceRetry(0).attempt.map(_.fold(_ => -1, identity))
  }

  @Benchmark def eru(): Int = eruProg.unsafeRunSync()
  @Benchmark def zio(): Int = Unsafe.unsafe { implicit u =>
    _root_.zio.Runtime.default.unsafe.run(zioProg).getOrThrowFiberFailure()
  }
  @Benchmark def catsEffect(): Int = ceProg.unsafeRunSync()
}
