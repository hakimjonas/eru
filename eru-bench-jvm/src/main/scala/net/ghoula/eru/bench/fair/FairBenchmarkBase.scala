package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.*
import zio.Unsafe

import java.util.concurrent.TimeUnit

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.prelude.*

/** Base class for all fair benchmark categories.
  *
  * Provides consistent setup and configuration across all benchmark categories. Each category
  * inherits from this to ensure:
  *   - Consistent timing and measurement settings
  *   - Shared runtime setup
  *   - Standard JSON output format
  *   - Fair comparison methodology
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1) // Single fork for consistency
abstract class FairBenchmarkBase {

  // Shared runtime setup for all benchmarks - using shared for optimal performance
  protected val runtime: EruRuntime = EruRuntime.shared
  implicit protected val implicitRuntime: EruRuntime = runtime

  // Standard test values used across benchmarks for consistency
  protected val TEST_VALUE = 42
  protected val TEST_STRING = "benchmark"
  protected val TEST_ITERATIONS = 10
  protected val TEST_ERROR = "test-error"

  /** Helper for running Eru effects */
  protected def runEru[A](effect: Eru[?, A]): A = effect.unsafeRunSync()

  /** Helper for running ZIO effects */
  protected def runZio[A](effect: zio.ZIO[Any, ?, A]): A = {
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
  }

  /** Helper for running Cats Effect IO */
  protected def runIO[A](effect: IO[A]): A = effect.unsafeRunSync()
}
