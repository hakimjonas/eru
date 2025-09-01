package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** Runner overhead microbenchmarks for Eru.
  *
  * Measures the cost of constructing and executing small/medium programs at the observable boundary
  * via unsafeRunSync and attempt.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
  value = 1,
  jvmArgs = Array(
    "-server",
    "-Xms2G",
    "-Xmx2G",
    "-XX:+UseG1GC",
    "-XX:+UnlockExperimentalVMOptions"
  )
)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
class EruRunnerOverheadBench {

  @Param(Array("small", "medium"))
  var size: String = "small"

  private var smallProgSuccess: Eru[Nothing, Int] = Eru.succeed(0)
  private var mediumProgSuccess: Eru[Nothing, Int] = Eru.succeed(0)

  private var smallProgTypedFail: Eru[String, Int] = Eru.succeed(0)
  private var mediumProgTypedFail: Eru[String, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    smallProgSuccess = Eru
      .succeed(1)
      .map(_ + 1)
      .flatMap(i => Eru.succeed(i + 1))
      .map(_ * 2)

    mediumProgSuccess = {
      var p: Eru[Nothing, Int] = Eru.succeed(0)
      var i = 0
      while (i < 64) { p = p.flatMap(n => Eru.succeed(n + 1)); i += 1 }
      p
    }

    smallProgTypedFail = Eru.fail("boom").recover { case _ => 1 }.map(_ + 1)

    mediumProgTypedFail = {
      // a few steps then a failure, then recover
      val pre = (0 until 8).foldLeft(Eru.succeed(0): Eru[String, Int])((acc, _) => acc.map(_ + 1))
      pre.flatMap(_ => Eru.fail("x")).recover { case _ => 42 }
    }
  }

  @Benchmark
  def runUnsafeSmall(h: Blackhole): Unit = {
    h.consume(smallProgSuccess.unsafeRunSync())
  }

  @Benchmark
  def runUnsafeMedium(h: Blackhole): Unit = {
    h.consume(mediumProgSuccess.unsafeRunSync())
  }

  @Benchmark
  def runAttemptSmall(h: Blackhole): Unit = {
    h.consume(smallProgTypedFail.attempt.unsafeRunSync())
  }

  @Benchmark
  def runAttemptMedium(h: Blackhole): Unit = {
    h.consume(mediumProgTypedFail.attempt.unsafeRunSync())
  }
}
