package net.ghoula.eru.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Duration
import java.util.concurrent.TimeUnit

import net.ghoula.eru.prelude.*
import net.ghoula.eru.EruRuntime

/** Retry/backoff microbenchmarks for Eru.
  *
  * These benches focus on the overhead and determinism of bounded retry policies.
  * We avoid measuring wall-clock sleeps by using base = Duration.ZERO in backoff
  * and count-bounded attempts via parameters.
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
class EruRetryBench {

  @Param(Array("0", "1", "3", "5", "10"))
  var maxRetriesStr: String = "3"

  @Param(Array("1", "2", "3", "6", "12")) // success at attempt index (1 = first try)
  var successIndexStr: String = "3"

  private var maxRetries: Int = 3
  private var successIndex: Int = 3

  private var recursProg: Eru[String | Throwable, Int] = Eru.succeed(0)
  private var expoZeroProg: Eru[String | Throwable, Int] = Eru.succeed(0)

  @Setup(Level.Iteration)
  def setup(): Unit = {
    maxRetries = math.max(0, maxRetriesStr.toInt)
    successIndex = math.max(1, successIndexStr.toInt)

    def flaky: Eru[String | Throwable, Int] = {
      var attempts = 0
      Eru.effect {
        attempts += 1
        attempts
      }.flatMap { n =>
        if n < successIndex then Eru.fail("retry") else Eru.succeed(42)
      }
    }

    recursProg = flaky.retry(EruRuntime.Policy.Recurs(maxRetries))
    expoZeroProg = flaky.retry(EruRuntime.Policy.Exponential(Duration.ZERO, maxRetries))
  }

  @Benchmark
  def retryRecurs(h: Blackhole): Unit = {
    h.consume(recursProg.unsafeRunSync())
  }

  @Benchmark
  def retryExponentialZero(h: Blackhole): Unit = {
    h.consume(expoZeroProg.unsafeRunSync())
  }
}
