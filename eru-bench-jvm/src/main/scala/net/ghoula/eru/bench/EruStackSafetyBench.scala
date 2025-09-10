package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** Stack-safety and deep-chain throughput microbenchmarks for Eru.
  *
  * These benchmarks build deep flatMap/map chains and execute them to validate performance
  * characteristics over depth while staying within safe recursion limits for the current runtime.
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
class EruStackSafetyBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  @Param(Array("256", "512", "1024"))
  var depthStr: String = "256"

  private var depth: Int = 256
  private var flatChain: Eru[Nothing, Int] = Eru.succeed(0)
  private var mapChain: Eru[Nothing, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    depth = depthStr.toInt

    // Deep flatMap chain with pure steps
    var p1: Eru[Nothing, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) { p1 = p1.flatMap(n => Eru.succeed(n + 1)); i += 1 }
    flatChain = p1

    // Deep map chain
    var p2: Eru[Nothing, Int] = Eru.succeed(0)
    i = 0
    while (i < depth) { p2 = p2.map(_ + 1); i += 1 }
    mapChain = p2
  }

  @Benchmark
  def runFlatChain(h: Blackhole): Unit = {
    h.consume(flatChain.unsafeRunSync())
  }

  @Benchmark
  def runMapChain(h: Blackhole): Unit = {
    h.consume(mapChain.unsafeRunSync())
  }
}
