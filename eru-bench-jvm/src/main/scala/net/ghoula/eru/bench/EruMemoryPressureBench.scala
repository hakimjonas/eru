package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Duration
import java.util.concurrent.TimeUnit

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.prelude.*

/** Memory pressure microbenchmarks for Eru.
  *
  * Designed to be run with GC profiler: -prof gc Scenarios:
  *   - composition: mixed pure/effect steps
  *   - resource: ensure-heavy (K finalizers)
  *   - retryZero: bounded attempts with base = ZERO
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
class EruMemoryPressureBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Param(Array("composition", "resource", "retryZero"))
  var scenario: String = "composition"

  @Param(Array("10", "100"))
  var sizeStr: String = "10"

  private var size: Int = 10
  private var prog: Eru[String | Throwable, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    size = sizeStr.toInt

    scenario match {
      case "composition" =>
        // mix pure succeeds and effect boundaries
        var p: Eru[Throwable | String, Int] = Eru.succeed(0)
        var i = 0
        while (i < size) {
          if ((i & 3) == 0) p = p.flatMap(n => Eru.effect(n + 1))
          else p = p.flatMap(n => Eru.succeed(n + 1))
          i += 1
        }
        prog = p.mapError(_.toString)

      case "resource" =>
        val base: Eru[String, Int] = Eru.succeed(1)
        val fin: Eru[Any, Unit] = Eru.unit
        val ensured = (0 until size).foldLeft(base) { (acc, _) => acc.ensure(fin) }
        prog = ensured

      case "retryZero" =>
        val maxRetries = math.max(0, size / 5)
        var attempts = 0
        val flaky: Eru[String | Throwable, Int] = Eru.effect { attempts += 1; attempts }.flatMap { n =>
          if n < (maxRetries + 1) then Eru.fail("retry") else Eru.succeed(42)
        }
        prog = flaky.retry(EruRuntime.Policy.Exponential(Duration.ZERO, maxRetries))
    }
  }

  @Benchmark
  def run(h: Blackhole): Unit = {
    h.consume(prog.attempt.unsafeRunSync())
  }
}
