package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(
  value = 3,
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
class EruValidationBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Benchmark
  def pureFusionBaseline(h: Blackhole): Unit = {
    h.consume(1000)
  }

  @Benchmark
  def pureFusionOptimized(h: Blackhole): Unit = {
    val prog = (0 until 1000).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    h.consume(prog.unsafeRunSync())
  }

  @Benchmark
  def pureFusionForced(h: Blackhole): Unit = {
    val prog = (0 until 1000).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.effect(i + 1))
    }
    h.consume(prog.unsafeRunSync())
  }
}
