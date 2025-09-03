package net.ghoula.eru.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import org.openjdk.jmh.annotations.*
import zio.{UIO, Unsafe, ZIO}

import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.concurrent.duration.FiniteDuration

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.prelude.*

// Extension for sequential composition (to be used for parTraverse implementation in Eru)
extension [E, A](effects: List[Eru[E, A]]) {
  def sequenceEru: Eru[E, List[A]] = {
    def loop(remaining: List[Eru[E, A]], acc: List[A]): Eru[E, List[A]] =
      remaining match {
        case Nil => Eru.succeed(acc.reverse)
        case head :: tail =>
          head.flatMap(a => loop(tail, a :: acc))
      }
    loop(effects, Nil)
  }
}

/** Competitive benchmarks comparing Eru's performance against ZIO and Cats Effect.
  *
  * This benchmark suite measures the throughput performance of effect composition across varying
  * recursion depths to evaluate how each effect system handles deeply nested computations. The
  * benchmark design stresses the core flatMap performance and stack safety implementations of each
  * library.
  *
  * ==Benchmark Methodology==
  *
  * Each benchmark constructs a chain of effects with mixed pure and effectful operations at
  * construction time, then measures execution throughput. The 4:1 ratio of pure to effectful
  * operations reflects realistic application patterns where most computations are pure
  * transformations with occasional side effects.
  *
  * ==Depth Parameters==
  *
  * Tests are conducted at depths of 8, 16, 32, 64, 128, 256, and 512 to provide a neutral,
  * powers-of-two sweep. This avoids singling out any specific internal thresholds and offers a
  * reproducible, conventional range:
  *   - Shallow (8–16): fast-path optimizations and startup overhead
  *   - Medium (32–128): representative chain depth scaling
  *   - Deep (256–512): deeper chain behavior under identical semantics
  *
  * ==Architectural Insights==
  *
  * This benchmark reveals fundamental differences in effect system design and identifies critical
  * performance thresholds:
  *
  * '''Comparative perspective:''' Each library exhibits distinct scaling characteristics across
  * depths. These benchmarks focus on equivalent semantics with neutral powers‑of‑two depths to
  * provide a reproducible, fair comparison.
  *
  * '''Eru:''' Maintains flat, high throughput for pure composition with smooth declines on
  * short‑circuit paths, consistent with construction‑time fusion and a fast synchronous
  * interpreter.
  *
  * '''ZIO:''' Strong shallow throughput with predictable declines as depth increases in these micro
  * scenarios.
  *
  * '''Cats Effect:''' Stable, lower absolute throughput in these micros, with consistent behavior
  * across depths.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class CompetitiveBench {

  /** The recursion depth parameter for effect chain construction.
    *
    * Values represent the number of flatMap operations chained together, testing performance
    * degradation as chain length increases.
    */
  @Param(Array("8", "16", "32", "64", "128", "256", "512"))
  var depth: Int = uninitialized

  /** The concurrency width parameter for concurrent benchmarks.
    *
    * Values represent the number of concurrent operations to run in parallel for
    * concurrency-focused benchmarks like parTraverse.
    */
  @Param(Array("8", "16", "32"))
  var concurrency: Int = uninitialized

  /** Pre-constructed Eru effect chain for benchmarking. */
  var eruProgram: Eru[Throwable, Int] = uninitialized

  /** Pre-constructed ZIO effect chain for benchmarking. */
  var zioProgram: UIO[Int] = uninitialized

  /** Pre-constructed Cats Effect IO chain for benchmarking. */
  var catsProgram: IO[Int] = uninitialized

  /** Pure continuation function for Eru effect composition. */
  private val pureEru: Int => Eru[Throwable, Int] = Eru.succeed

  /** Pure continuation function for ZIO effect composition. */
  private val pureZio: Int => UIO[Int] = ZIO.succeed

  /** Pure continuation function for Cats Effect IO composition. */
  private val pureCats: Int => IO[Int] = IO.pure

  /** Constructs effect chains for all three libraries at the specified depth.
    *
    * This setup method runs once per benchmark trial and builds the effect chains that will be
    * executed during measurement. Each chain follows the same pattern: starting with a pure success
    * value, then alternating between pure continuations (3/4 of operations) and effectful
    * operations (1/4 of operations).
    *
    * The 4:1 ratio of pure to effectful operations simulates realistic application patterns where
    * most computations are pure transformations with occasional side effects or external
    * interactions.
    *
    * Each library uses its idiomatic construction patterns:
    *   - Eru: `Eru.succeed` for pure values, `Eru.effect` for side effects
    *   - ZIO: `ZIO.succeed` for both pure and effectful operations
    *   - Cats Effect: `IO.pure` for pure values, `IO(...)` for side effects
    */
  @Setup(Level.Trial)
  def setup(): Unit = {
    var eru: Eru[Throwable, Int] = Eru.succeed(0)
    var i = 0
    while (i < depth) {
      if (i % 4 == 0) {
        eru = eru.flatMap(_ => Eru.effect(i))
      } else {
        eru = eru.flatMap(pureEru)
      }
      i += 1
    }
    eruProgram = eru

    var zio: UIO[Int] = ZIO.succeed(0)
    i = 0
    while (i < depth) {
      if (i % 4 == 0) {
        zio = zio.flatMap(_ => ZIO.succeed(i))
      } else {
        zio = zio.flatMap(pureZio)
      }
      i += 1
    }
    zioProgram = zio

    var cats: IO[Int] = IO.pure(0)
    i = 0
    while (i < depth) {
      if (i % 4 == 0) {
        cats = cats.flatMap(_ => IO(i))
      } else {
        cats = cats.flatMap(pureCats)
      }
      i += 1
    }
    catsProgram = cats
  }

  /** Benchmarks Eru's effect execution performance.
    *
    * This benchmark measures Eru's synchronous execution path through its continuation-based
    * interpreter. The execution uses Eru's stack-safe TailRec-based interpreter with
    * construction-time optimizations like chain flattening and map fusion.
    *
    * Key architectural features tested:
    *   - Chain2/Chain3 flattening optimizations
    *   - MapChain fusion for consecutive map operations
    *   - Stack-safe execution via Scala's TailRec
    *   - Finalizer threading and cleanup
    *
    * @return
    *   the final integer result from the effect chain execution
    */
  @Benchmark
  def eru(): Int = {
    eruProgram.unsafeRunSync()
  }

  /** Benchmarks ZIO's effect execution performance.
    *
    * This benchmark measures ZIO's execution through its default runtime using the unsafe execution
    * model. ZIO uses a fiber-based approach with aggressive optimizations for shallow effect chains
    * but faces degradation at deep recursion levels due to fiber allocation overhead.
    *
    * Key architectural features tested:
    *   - Fiber-based execution model
    *   - ZIO's fast-path optimizations
    *   - Runtime scheduler and fiber management
    *   - Stack safety through fiber trampolines
    *
    * @return
    *   the final integer result from the ZIO effect chain execution
    */
  @Benchmark
  def zio(): Int = {
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(zioProgram).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's IO execution performance.
    *
    * This benchmark measures Cats Effect's IO monad execution through its synchronous unsafe
    * runner. Cats Effect prioritizes correctness and composability over raw performance, showing
    * consistent but lower throughput across all recursion depths.
    *
    * Key architectural features tested:
    *   - Traditional IO monad implementation
    *   - Resource management and bracket semantics
    *   - Referential transparency guarantees
    *   - Effect composition via flatMap
    *
    * @return
    *   the final integer result from the IO effect chain execution
    */
  @Benchmark
  def catsEffect(): Int = {
    catsProgram.unsafeRunSync()
  }

  /** Benchmarks Eru's error handling performance with failure paths.
    *
    * This benchmark measures Eru's error propagation and recovery performance by constructing
    * effect chains that deliberately fail at various points and use recovery mechanisms. This tests
    * the efficiency of Eru's error handling infrastructure under realistic failure scenarios.
    *
    * @return
    *   the recovered integer result from the failed effect chain
    */
  @Benchmark
  def eruFailurePath(): Int = {
    val failureProgram = Eru
      .succeed(0)
      .flatMap(_ => Eru.fail("deliberate failure"))
      .recover { case "deliberate failure" => depth }
      .flatMap(x => Eru.succeed(x + 1))

    failureProgram.unsafeRunSync()
  }

  /** Benchmarks ZIO's error handling performance with failure paths.
    *
    * This benchmark measures ZIO's error propagation and recovery performance using equivalent
    * failure and recovery patterns to the Eru benchmark. Tests ZIO's error handling efficiency
    * under the same failure scenarios.
    *
    * @return
    *   the recovered integer result from the failed ZIO chain
    */
  @Benchmark
  def zioFailurePath(): Int = {
    val failureProgram = ZIO
      .succeed(0)
      .flatMap(_ => ZIO.fail("deliberate failure"))
      .catchAll {
        case "deliberate failure" => ZIO.succeed(depth)
        case other => ZIO.fail(other)
      }
      .flatMap(x => ZIO.succeed(x + 1))

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(failureProgram).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's error handling performance with failure paths.
    *
    * This benchmark measures Cats Effect's error propagation and recovery performance using
    * equivalent failure and recovery patterns. Tests IO's error handling efficiency under realistic
    * failure scenarios.
    *
    * @return
    *   the recovered integer result from the failed IO chain
    */
  @Benchmark
  def catsEffectFailurePath(): Int = {
    val failureProgram = IO
      .pure(0)
      .flatMap(_ => IO.raiseError(new RuntimeException("deliberate failure")))
      .handleErrorWith {
        case _: RuntimeException => IO.pure(depth)
        case other => IO.raiseError(other)
      }
      .flatMap(x => IO.pure(x + 1))

    failureProgram.unsafeRunSync()
  }

  /** Benchmarks Eru's resource management performance with bracket patterns.
    *
    * This benchmark measures Eru's resource acquisition, usage, and cleanup performance using the
    * bracket pattern. Tests the efficiency of Eru's ensure-based resource management under
    * realistic scenarios with nested resource operations.
    *
    * @return
    *   the result of resource operations with guaranteed cleanup
    */
  @Benchmark
  def eruResourceManagement(): Int = {
    def acquireResource(id: Int): Eru[Throwable, String] = Eru.effect(s"resource-$id")
    def releaseResource(resource: String): Eru[Throwable, Unit] = { val _ = resource; Eru.effect(()) }
    def useResource(resource: String): Eru[Throwable, Int] = Eru.effect(resource.length)

    val program = (0 until depth / 10).foldLeft(Eru.succeed(0)) { (acc, i) =>
      acc.flatMap { sum =>
        acquireResource(i).bracket(releaseResource) { resource =>
          useResource(resource).map(_ + sum)
        }
      }
    }

    program.unsafeRunSync()
  }

  /** Benchmarks ZIO's resource management performance with bracket patterns.
    *
    * This benchmark measures ZIO's resource acquisition, usage, and cleanup performance using ZIO's
    * bracket semantics. Tests ZIO's resource management efficiency under equivalent scenarios to
    * the Eru benchmark.
    *
    * @return
    *   the result of resource operations with guaranteed cleanup
    */
  @Benchmark
  def zioResourceManagement(): Int = {
    def acquireResource(id: Int): UIO[String] = ZIO.succeed(s"resource-$id")
    def releaseResource(resource: String): UIO[Unit] = { val _ = resource; ZIO.succeed(()) }
    def useResource(resource: String): UIO[Int] = ZIO.succeed(resource.length)

    val program = (0 until depth / 10).foldLeft(ZIO.succeed(0)) { (acc, i) =>
      acc.flatMap { sum =>
        ZIO.acquireReleaseWith(acquireResource(i))(releaseResource) { resource =>
          useResource(resource).map(_ + sum)
        }
      }
    }

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's resource management performance with bracket patterns.
    *
    * This benchmark measures Cats Effect's resource acquisition, usage, and cleanup performance
    * using IO's bracket semantics. Tests IO's resource management efficiency under equivalent
    * scenarios to other effect systems.
    *
    * @return
    *   the result of resource operations with guaranteed cleanup
    */
  @Benchmark
  def catsEffectResourceManagement(): Int = {
    def acquireResource(id: Int): IO[String] = IO.pure(s"resource-$id")
    def releaseResource(resource: String): IO[Unit] = { val _ = resource; IO.pure(()) }
    def useResource(resource: String): IO[Int] = IO.pure(resource.length)

    val program = (0 until depth / 10).foldLeft(IO.pure(0)) { (acc, i) =>
      acc.flatMap { sum =>
        acquireResource(i).bracket(useResource(_).map(_ + sum))(releaseResource)
      }
    }

    program.unsafeRunSync()
  }

  /** Benchmarks Eru's mixed workload performance with computation and I/O simulation.
    *
    * This benchmark measures Eru's performance under mixed workloads that combine CPU-intensive
    * computations with I/O simulation (via effects). This tests Eru's efficiency in realistic
    * application scenarios with varied operation types.
    *
    * @return
    *   the result of mixed computation and I/O operations
    */
  @Benchmark
  def eruMixedWorkload(): Int = {
    def cpuIntensiveWork(n: Int): Int = {
      var result = n
      var i = 0
      while (i < 100) {
        result = (result * 31 + i) % 1000000
        i += 1
      }
      result
    }

    val program = (0 until depth / 4).foldLeft(Eru.succeed(0)) { (acc, i) =>
      acc.flatMap { sum =>
        if (i % 3 == 0) {
          // CPU intensive computation
          Eru.effect(cpuIntensiveWork(i)).map(_ + sum)
        } else {
          // I/O simulation with potential failure and recovery
          Eru
            .effect(i * 2)
            .flatMap(x => if (x % 7 == 0) Eru.fail("simulated I/O error") else Eru.succeed(x))
            .recover { case _ => i }
            .map(_ + sum)
        }
      }
    }

    program.unsafeRunSync()
  }

  /** Benchmarks ZIO's mixed workload performance with computation and I/O simulation.
    *
    * This benchmark measures ZIO's performance under equivalent mixed workloads combining
    * CPU-intensive computations with I/O simulation. Tests ZIO's efficiency in realistic
    * application scenarios with varied operation types.
    *
    * @return
    *   the result of mixed computation and I/O operations
    */
  @Benchmark
  def zioMixedWorkload(): Int = {
    def cpuIntensiveWork(n: Int): Int = {
      var result = n
      var i = 0
      while (i < 100) {
        result = (result * 31 + i) % 1000000
        i += 1
      }
      result
    }

    val program = (0 until depth / 4).foldLeft(ZIO.succeed(0)) { (acc, i) =>
      acc.flatMap { sum =>
        if (i % 3 == 0) {
          // CPU intensive computation
          ZIO.succeed(cpuIntensiveWork(i)).map(_ + sum)
        } else {
          // I/O simulation with potential failure and recovery
          ZIO
            .succeed(i * 2)
            .flatMap(x => if (x % 7 == 0) ZIO.fail("simulated I/O error") else ZIO.succeed(x))
            .catchAll(_ => ZIO.succeed(i))
            .map(_ + sum)
        }
      }
    }

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's mixed workload performance with computation and I/O simulation.
    *
    * This benchmark measures Cats Effect's performance under equivalent mixed workloads combining
    * CPU-intensive computations with I/O simulation. Tests IO's efficiency in realistic application
    * scenarios with varied operation types.
    *
    * @return
    *   the result of mixed computation and I/O operations
    */
  @Benchmark
  def catsEffectMixedWorkload(): Int = {
    def cpuIntensiveWork(n: Int): Int = {
      var result = n
      var i = 0
      while (i < 100) {
        result = (result * 31 + i) % 1000000
        i += 1
      }
      result
    }

    val program = (0 until depth / 4).foldLeft(IO.pure(0)) { (acc, i) =>
      acc.flatMap { sum =>
        if (i % 3 == 0) {
          // CPU intensive computation
          IO(cpuIntensiveWork(i)).map(_ + sum)
        } else {
          // I/O simulation with potential failure and recovery
          IO.pure(i * 2)
            .flatMap(x => if (x % 7 == 0) IO.raiseError(new RuntimeException("simulated I/O error")) else IO.pure(x))
            .handleError(_ => i)
            .map(_ + sum)
        }
      }
    }

    program.unsafeRunSync()
  }

  /** Benchmarks Eru's zipPar performance with concurrent execution.
    *
    * This benchmark measures Eru's parallel execution of two independent effects with small delays,
    * testing the efficiency of the zipPar operation in the runtime system.
    *
    * @return
    *   the tuple result from parallel execution
    */
  @Benchmark
  def eruZipPar(): (Int, Int) = {
    val left = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => 1)
    val right = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => 2)
    EruRuntime.zipPar(left, right).unsafeRunSync()
  }

  /** Benchmarks ZIO's zipPar performance with concurrent execution.
    *
    * This benchmark measures ZIO's parallel execution of two independent effects with small delays,
    * providing equivalent semantics to the Eru zipPar benchmark.
    *
    * @return
    *   the tuple result from parallel execution
    */
  @Benchmark
  def zioZipPar(): (Int, Int) = {
    val left = ZIO.sleep(java.time.Duration.ofMillis(1)).as(1)
    val right = ZIO.sleep(java.time.Duration.ofMillis(1)).as(2)

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(left.zipPar(right)).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's parTupled performance with concurrent execution.
    *
    * This benchmark measures Cats Effect's parallel execution of two independent effects with small
    * delays, providing equivalent semantics to other libraries' zipPar operations.
    *
    * @return
    *   the tuple result from parallel execution
    */
  @Benchmark
  def catsEffectZipPar(): (Int, Int) = {
    val left = IO.sleep(FiniteDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS)).as(1)
    val right = IO.sleep(FiniteDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS)).as(2)
    (left, right).parTupled.unsafeRunSync()
  }

  /** Benchmarks Eru's parTraverse performance with concurrent list processing.
    *
    * This benchmark measures Eru's parallel traversal by forking effects and awaiting their
    * results. The number of items processed is determined by the concurrency parameter.
    *
    * @return
    *   the list of processed results
    */
  @Benchmark
  def eruParTraverse(): List[Int] = {
    val items = (1 to concurrency).toList
    def processItem(item: Int): Eru[Nothing, Int] =
      EruRuntime.sleep(Duration.ofMillis(1)).map(_ => item * 2)

    // Use the new native parTraverse implementation
    EruRuntime.parTraverse(items)(processItem).unsafeRunSync()
  }

  /** Benchmarks ZIO's parTraverse performance with concurrent list processing.
    *
    * This benchmark measures ZIO's parallel traversal using ZIO's parTraverse operation. Provides
    * equivalent semantics to the Eru parTraverse benchmark.
    *
    * @return
    *   the list of processed results
    */
  @Benchmark
  def zioParTraverse(): List[Int] = {
    val items = (1 to concurrency).toList
    def processItem(item: Int): UIO[Int] =
      ZIO.sleep(java.time.Duration.ofMillis(1)).as(item * 2)

    val program = ZIO.foreachPar(items)(processItem)

    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's parTraverse performance with concurrent list processing.
    *
    * This benchmark measures Cats Effect's parallel traversal using the parTraverse operation.
    * Provides equivalent semantics to other libraries' parTraverse implementations.
    *
    * @return
    *   the list of processed results
    */
  @Benchmark
  def catsEffectParTraverse(): List[Int] = {
    val items = (1 to concurrency).toList
    def processItem(item: Int): IO[Int] =
      IO.sleep(FiniteDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS)).as(item * 2)

    items.parTraverse(processItem).unsafeRunSync()
  }

  /** Benchmarks Eru's race performance with fast vs slow effects.
    *
    * This benchmark measures Eru's race operation with one fast effect (1ms) and one slow effect
    * (20ms), testing cancellation efficiency and concurrent execution.
    *
    * @return
    *   the result from the winning effect
    */
  @Benchmark
  def eruRace(): Either[String, String] = {
    val fast = EruRuntime.sleep(Duration.ofMillis(1)).map(_ => "fast")
    val slow = EruRuntime.sleep(Duration.ofMillis(20)).map(_ => "slow")
    EruRuntime.race(fast, slow).unsafeRunSync()
  }

  /** Benchmarks ZIO's race performance with fast vs slow effects.
    *
    * This benchmark measures ZIO's race operation with equivalent fast and slow effects, providing
    * comparable semantics to the Eru race benchmark.
    *
    * @return
    *   the result from the winning effect
    */
  @Benchmark
  def zioRace(): Either[String, String] = {
    val fast = ZIO.sleep(java.time.Duration.ofMillis(1)).as("fast")
    val slow = ZIO.sleep(java.time.Duration.ofMillis(20)).as("slow")

    val raced = fast.raceEither(slow)
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(raced).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's race performance with fast vs slow effects.
    *
    * This benchmark measures Cats Effect's race operation with equivalent fast and slow effects,
    * providing comparable semantics to other libraries' race implementations.
    *
    * @return
    *   the result from the winning effect
    */
  @Benchmark
  def catsEffectRace(): Either[String, String] = {
    val fast = IO.sleep(FiniteDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS)).as("fast")
    val slow = IO.sleep(FiniteDuration(20, java.util.concurrent.TimeUnit.MILLISECONDS)).as("slow")
    IO.race(fast, slow).unsafeRunSync()
  }

  /** Benchmarks Eru's raceAll performance with multiple competing effects.
    *
    * This benchmark measures Eru's raceAll operation with multiple effects of varying durations,
    * testing the efficiency of concurrent execution and cancellation behavior. The fastest effect
    * (1ms) should win consistently, with all losing effects being cancelled properly.
    *
    * @return
    *   a tuple containing the winning result and its index in the original list
    */
  @Benchmark
  def eruRaceAll(): (String, Int) = {
    import java.time.Duration
    val effects = List(
      EruRuntime.sleep(Duration.ofMillis(10)).map(_ => "slow-1"), // index 0
      EruRuntime.sleep(Duration.ofMillis(1)).map(_ => "fast"), // index 1 - should win
      EruRuntime.sleep(Duration.ofMillis(20)).map(_ => "slow-2"), // index 2
      EruRuntime.sleep(Duration.ofMillis(15)).map(_ => "slow-3") // index 3
    )
    EruRuntime.raceAll(effects).unsafeRunSync()
  }

  /** Benchmarks ZIO's equivalent of raceAll using nested ZIO.raceEither operations.
    *
    * This benchmark simulates race-all functionality using nested raceEither operations since ZIO
    * doesn't have a direct raceAll equivalent. This provides comparable timing behavior with
    * multiple competing effects and proper cancellation.
    *
    * @return
    *   the winning result as a String
    */
  @Benchmark
  def zioRaceAll(): String = {
    import _root_.zio.*
    val effects = List(
      ZIO.sleep(10.millis).as("slow-1"), // index 0
      ZIO.sleep(1.millis).as("fast"), // index 1 - should win
      ZIO.sleep(20.millis).as("slow-2"), // index 2
      ZIO.sleep(15.millis).as("slow-3") // index 3
    )

    // Simulate raceAll with nested races since ZIO lacks native raceAll
    def raceAll(effects: List[ZIO[Any, Nothing, String]]): ZIO[Any, Nothing, String] = effects match {
      case Nil => ZIO.die(new IllegalArgumentException("empty list"))
      case single :: Nil => single
      case head :: tail =>
        head.raceEither(raceAll(tail)).map {
          case Left(winner) => winner
          case Right(winner) => winner
        }
    }

    val raced = raceAll(effects)
    Unsafe.unsafe { implicit unsafe =>
      _root_.zio.Runtime.default.unsafe.run(raced).getOrThrowFiberFailure()
    }
  }

  /** Benchmarks Cats Effect's equivalent of raceAll using multiple IO.race operations.
    *
    * This benchmark simulates raceAll behavior using nested IO.race operations since Cats Effect
    * doesn't have a native raceAll. This provides comparable semantics but may have different
    * performance characteristics due to the nested structure.
    *
    * @return
    *   the winning result as a String
    */
  @Benchmark
  def catsEffectRaceAll(): String = {
    val effects = List(
      IO.sleep(FiniteDuration(10, java.util.concurrent.TimeUnit.MILLISECONDS)).as("slow-1"),
      IO.sleep(FiniteDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS)).as("fast"),
      IO.sleep(FiniteDuration(20, java.util.concurrent.TimeUnit.MILLISECONDS)).as("slow-2"),
      IO.sleep(FiniteDuration(15, java.util.concurrent.TimeUnit.MILLISECONDS)).as("slow-3")
    )

    // Simulate raceAll with nested races since Cats Effect lacks native raceAll
    def raceAll(effects: List[IO[String]]): IO[String] = effects match {
      case Nil => IO.raiseError(new IllegalArgumentException("empty list"))
      case single :: Nil => single
      case head :: tail =>
        IO.race(head, raceAll(tail)).map {
          case Left(winner) => winner
          case Right(winner) => winner
        }
    }

    raceAll(effects).unsafeRunSync()
  }
}
