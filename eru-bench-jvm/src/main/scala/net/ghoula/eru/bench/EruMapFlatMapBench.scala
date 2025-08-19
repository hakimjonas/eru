package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.{
  Benchmark,
  BenchmarkMode,
  Fork,
  Measurement,
  Mode,
  OutputTimeUnit,
  Param,
  Scope,
  Setup,
  State,
  Warmup
}
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.Eru

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
  value = 3,
  jvmArgs = Array(
    "-server",
    "-Xms2G",
    "-Xmx2G", // Fixed heap to reduce GC variance
    "-XX:+UseG1GC", // Consistent garbage collector
    "-XX:+UnlockExperimentalVMOptions"
  )
)
@Warmup(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 15, time = 3, timeUnit = TimeUnit.SECONDS)
class EruMapFlatMapBench {

  @Param(Array("10", "100", "1000"))
  var depthStr: String = "10"
  private var depth: Int = 10

  private var mapped: Eru[Nothing, Int] = Eru.succeed(0)
  private var flatMapped: Eru[Nothing, Int] = Eru.succeed(0)
  private var mixed: Eru[Nothing, Int] = Eru.succeed(0)
  private var pureFlat: Eru[Nothing, Int] = Eru.succeed(0)
  private var mixedPure: Eru[Throwable, Int] = Eru.succeed(0)

  @Setup
  def setup(): Unit = {
    depth = depthStr.toInt
    mapped = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.map(_ + 1)
    }
    flatMapped = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    mixed = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, idx) =>
      if ((idx & 1) == 0) acc.map(_ + 1)
      else acc.flatMap(i => Eru.succeed(i + 1))
    }
    pureFlat = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(i => Eru.succeed(i + 1))
    }
    mixedPure = (0 until depth).foldLeft(Eru.succeed(0)) { (acc, idx) =>
      if ((idx & 3) == 0) acc.map(_ + 1)
      else if ((idx & 1) == 0) acc.flatMap(i => Eru.succeed(i + 1))
      else acc.flatMap(i => Eru.effect(i + 1))
    }
  }

  @Benchmark
  def runMapped(h: Blackhole): Unit = {
    h.consume(mapped.unsafeRunSync())
  }

  @Benchmark
  def runFlatMapped(h: Blackhole): Unit = {
    h.consume(flatMapped.unsafeRunSync())
  }

  @Benchmark
  def runMixed(h: Blackhole): Unit = {
    h.consume(mixed.unsafeRunSync())
  }

  @Benchmark
  def runPureFlat(h: Blackhole): Unit = {
    h.consume(pureFlat.unsafeRunSync())
  }

  @Benchmark
  def runMixedPure(h: Blackhole): Unit = {
    h.consume(mixedPure.unsafeRunSync())
  }
}
