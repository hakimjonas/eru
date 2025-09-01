package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** Error-handling microbenchmarks for Eru.
  *
  * Scenarios:
  *   - recoverMatch: failure recovered by pattern; success bypass
  *   - recoverNoMatch: failure not matched -> remains failure (attempt boundary)
  *   - mapErrorOnFailure: transforms error value
  *   - orElseFallback: fallback only applied on failure
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
class EruErrorHandlingBench {

  @Param(Array("success", "failure"))
  var path: String = "success"

  private var base: Eru[String, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    base = path match {
      case "success" => Eru.succeed(42)
      case _ => Eru.fail("boom")
    }
  }

  @Benchmark
  def recoverMatch(h: Blackhole): Unit = {
    val p = base.recover { case "boom" => 1 }
    h.consume(p.attempt.unsafeRunSync())
  }

  @Benchmark
  def recoverNoMatch(h: Blackhole): Unit = {
    val p = base.recover { case "other" => 1 }
    h.consume(p.attempt.unsafeRunSync())
  }

  @Benchmark
  def mapErrorOnFailure(h: Blackhole): Unit = {
    val p = base.mapError(e => s"mapped:$e")
    h.consume(p.attempt.unsafeRunSync())
  }

  @Benchmark
  def orElseFallback(h: Blackhole): Unit = {
    val fallback = Eru.succeed(7)
    val p = base.orElse(fallback)
    h.consume(p.attempt.unsafeRunSync())
  }
}
