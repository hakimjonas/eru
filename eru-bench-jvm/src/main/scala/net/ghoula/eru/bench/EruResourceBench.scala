package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** Resource-discipline microbenchmarks for Eru (ensure/bracket).
  *
  * Parameters control number of finalizers (K) and use-phase outcome. We measure throughput under:
  *   - ensureK: K finalizers in FILO order on success/typed-failure
  *   - bracketUse: acquire/use/release with success/typed-failure in use
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
class EruResourceBench {

  @Param(Array("1", "4", "8", "16"))
  var kStr: String = "4"

  @Param(Array("success", "typedFailure"))
  var outcome: String = "success"

  private var k: Int = 4

  private var ensuredProg: Eru[String, Int] = Eru.succeed(0)
  private var bracketProg: Eru[String, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    k = math.max(1, kStr.toInt)

    val base: Eru[String, Int] = outcome match {
      case "success" => Eru.succeed(42)
      case "typedFailure" => Eru.fail("boom")
      case _ => Eru.succeed(42)
    }

    val finalizer: Eru[Any, Unit] = Eru.unit // small, non-failing finalizer

    // Build K ensures around the base program
    ensuredProg = (0 until k).foldLeft(base) { (acc, _) => acc.ensure(finalizer) }

    // Bracket acquire/use/release where use may fail based on outcome
    val acquire: Eru[Nothing, String] = Eru.succeed("res")
    val release: String => Eru[Any, Unit] = _ => finalizer
    val use: String => Eru[String, Int] = _ => base

    bracketProg = acquire.bracket(release)(use)
  }

  @Benchmark
  def ensureK(h: Blackhole): Unit = {
    outcome match {
      case "success" => h.consume(ensuredProg.unsafeRunSync())
      case "typedFailure" => h.consume(ensuredProg.attempt.unsafeRunSync())
      case _ => h.consume(ensuredProg.unsafeRunSync())
    }
  }

  @Benchmark
  def bracketUse(h: Blackhole): Unit = {
    outcome match {
      case "success" => h.consume(bracketProg.unsafeRunSync())
      case "typedFailure" => h.consume(bracketProg.attempt.unsafeRunSync())
      case _ => h.consume(bracketProg.unsafeRunSync())
    }
  }
}
