package net.ghoula.eru

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** JMH benchmarks for the Eru core performance characteristics.
  *
  * These benchmarks establish baseline performance metrics for:
  *   - Basic Eru operations and composition
  *   - Error handling and recovery performance
  *   - Sequential computation overhead
  *
  * Run with: sbt "project eruBenchJVM; jmh:run"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class EruRuntimeBench {
  private val runtime = EruRuntime.create()
  implicit val implicitRuntime: EruRuntime = runtime

  /** Measures the overhead of basic Eru operations like flatMap and map.
    *
    * This benchmark captures the performance of:
    *   - Creating Eru instances
    *   - Composing them with flatMap and map
    *   - Executing them synchronously
    */
  @Benchmark
  def basicComposition(): String = {
    val program = for {
      a <- Eru.succeed(42)
      b <- Eru.succeed("hello")
      c <- Eru.succeed(a.toString + "-" + b)
      result <- Eru.succeed(c + "-result")
    } yield result

    program.unsafeRunSync()
  }

  /** Measures error handling and recovery performance.
    *
    * This benchmark tests the performance of:
    *   - Creating failing effects
    *   - Recovering from failures with recover
    *   - Error transformation with mapError
    */
  @Benchmark
  def errorHandling(): String = {
    val program = for {
      result <- Eru
        .fail("initial error")
        .mapError(e => s"mapped: $e")
        .recover {
          case err if err.startsWith("mapped") => "recovered"
          case _ => "fallback"
        }
    } yield result

    program.unsafeRunSync()
  }

  /** Measures sequential zip operation performance.
    *
    * This benchmark tests the performance of:
    *   - Sequential execution with zip
    *   - Tuple creation and extraction
    *   - Sequential computation coordination
    */
  @Benchmark
  def zipSequential(): Int = {
    def doWork(workId: Int): Eru[Nothing, Int] = {
      def loop(acc: Int, remaining: Int): Eru[Nothing, Int] = {
        if (remaining <= 0) Eru.succeed(acc)
        else Eru.succeed(acc + 1).flatMap(newAcc => loop(newAcc, remaining - 1))
      }
      loop(0, 10).map(_ * workId)
    }

    val program =
      doWork(1).zip(doWork(2)).zip(doWork(3).zip(doWork(4))).map { case ((r1, r2), (r3, r4)) => r1 + r2 + r3 + r4 }

    program.unsafeRunSync()
  }

  /** Measures effect creation and execution overhead.
    *
    * This benchmark tests the performance of:
    *   - Creating effect-wrapped computations
    *   - Executing side-effecting code safely
    *   - Exception handling within effects
    */
  @Benchmark
  def effectOverhead(): String = {
    val program = Eru.effect {
      val sb = new StringBuilder()
      for (i <- 1 to 10) {
        sb.append(s"item-$i-")
      }
      sb.toString()
    }

    program.unsafeRunSync()
  }

  /** Measures attempt and Result handling performance.
    *
    * This benchmark tests the performance of:
    *   - Converting effects to Result types
    *   - Pattern matching on Result values
    *   - Error vs success path handling
    */
  @Benchmark
  def attemptHandling(): String = {
    val successProgram = Eru.succeed("success").attempt
    val failureProgram = Eru.fail("failure").attempt

    val combined = for {
      successResult <- successProgram
      failureResult <- failureProgram
    } yield (successResult, failureResult) match {
      case (Result.Success(s), Result.Failure(f)) => s + "-handled-" + f
      case (Result.Failure(_), Result.Success(_)) => "reversed"
      case (Result.Success(_), Result.Success(_)) => "both-success"
      case (Result.Failure(_), Result.Failure(_)) => "both-failure"
    }

    combined.unsafeRunSync()
  }

  /** Measures ensure (finally) performance.
    *
    * This benchmark tests the performance of:
    *   - Finalizer registration and execution
    *   - Resource cleanup guarantees
    *   - Finalizer ordering
    */
  @Benchmark
  def ensureHandling(): String = {
    var finalizerRan = false

    val program = Eru
      .succeed("main-result")
      .ensure(Eru.effect { finalizerRan = true })
      .map(result => if (finalizerRan) result + "-finalized" else result + "-no-finalizer")

    program.unsafeRunSync()
  }

  /** Measures deeply nested flatMap performance.
    *
    * This benchmark tests the performance of:
    *   - Deep effect composition chains
    *   - Stack safety of flatMap implementation
    *   - Continuation building overhead
    */
  @Benchmark
  def deepComposition(): Int = {
    def buildChain(depth: Int, acc: Int): Eru[Nothing, Int] = {
      if (depth <= 0) Eru.succeed(acc)
      else Eru.succeed(acc + 1).flatMap(newAcc => buildChain(depth - 1, newAcc))
    }

    buildChain(100, 0).unsafeRunSync()
  }
}
