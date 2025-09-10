package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*

/** Eru-only concurrency-adjacent microbenchmarks.
  *
  * Notes:
  *   - zipPar/race have sequential semantics in the current runtime and are included for Eru
  *     internal regression tracking (not for cross-library comparison).
  *   - Includes small Ref/Deferred/Semaphore micros to gauge overhead.
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
class EruConcurrencyLiteBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  private var a: Eru[Nothing, Int] = Eru.succeed(1)
  private var b: Eru[Nothing, Int] = Eru.succeed(2)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    a = Eru.succeed(1)
    b = Eru.succeed(2)
  }

  @Benchmark
  def zipParPair(h: Blackhole): Unit = {
    h.consume(a.zipPar(b).unsafeRunSync(): AnyRef)
  }

  @Benchmark
  def racePair(h: Blackhole): Unit = {
    h.consume(a.race(b).unsafeRunSync(): AnyRef)
  }

  @Benchmark
  def forkAwait(h: Blackhole): Unit = {
    val fiber = a.fork.unsafeRunSync()
    h.consume(fiber.await.unsafeRunSync(): AnyRef)
  }

  @Benchmark
  def refThroughput(h: Blackhole): Unit = {
    val p = for {
      r <- Eru.ref(0)
      _ <- r.update(_ + 1)
      v <- r.get
    } yield v
    h.consume(java.lang.Integer.valueOf(p.unsafeRunSync()))
  }

  @Benchmark
  def deferredCompleteAwait(h: Blackhole): Unit = {
    val p = for {
      d <- Eru.deferred[Int]
      _ <- d.complete(42)
      v <- d.await
    } yield v
    h.consume(java.lang.Integer.valueOf(p.unsafeRunSync()))
  }

  @Benchmark
  def semaphoreWithPermit(h: Blackhole): Unit = {
    val p = for {
      s <- Eru.semaphore(1)
      v <- s.withPermit(Eru.succeed(7))
    } yield v
    h.consume(p.unsafeRunSync())
  }
}
