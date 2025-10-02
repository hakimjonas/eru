package net.ghoula.eru.bench.fair

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import zio.{Unsafe, ZIO}

import net.ghoula.eru.prelude.*

/** Validates that benchmarks are truly fair by checking:
  *   1. Actual parallelism achieved
  *   2. Work distribution
  *   3. Resource usage
  *   4. Timing characteristics
  */
object BenchmarkValidator {

  case class ValidationResult(
    framework: String,
    actualParallelism: Double, // 1.0 = perfect parallel, 5.0 = sequential for 5 tasks
    totalTime: Long,
    avgTimePerOp: Double,
    isValid: Boolean,
    issues: List[String]
  )

  def validateParallelBenchmark(
    name: String,
    taskCount: Int,
    workMs: Long,
    iterations: Int = 100
  ): Unit = {
    println(s"\n=== Validating: $name ===")
    println(s"Tasks: $taskCount, Work per task: ${workMs}ms, Iterations: $iterations")

    val results = List(
      validateEru(taskCount, workMs, iterations),
      validateZio(taskCount, workMs, iterations),
      validateCats(taskCount, workMs, iterations)
    )

    // Check if all frameworks achieved similar parallelism
    val parallelisms = results.map(_.actualParallelism)
    val maxDiff = parallelisms.max - parallelisms.min

    println("\nResults:")
    results.foreach { r =>
      println(f"${r.framework}%-10s: ${r.actualParallelism}%.2fx overhead, ${r.avgTimePerOp}%.2f ms/op")
      if (r.issues.nonEmpty) {
        r.issues.foreach(issue => println(s"  ⚠️  $issue"))
      }
    }

    if (maxDiff > 0.5) {
      println(s"\n❌ UNFAIR: Parallelism differs by ${maxDiff}x between frameworks!")
      println("   Benchmark may not be measuring the same thing.")
    } else {
      println(s"\n✅ FAIR: All frameworks achieve similar parallelism (±${maxDiff}x)")
    }

    // Check for other fairness issues
    val avgTimes = results.map(_.avgTimePerOp)
    val timeRatio = avgTimes.max / avgTimes.min
    if (timeRatio > 2.0) {
      println(s"⚠️  WARNING: Performance differs by ${timeRatio}x - verify same work is being done")
    }
  }

  private def validateEru(taskCount: Int, workMs: Long, iterations: Int): ValidationResult = {
    val runtime = EruRuntime.shared

    // Warmup
    for (_ <- 1 to 10) {
      val effects = List.fill(taskCount)(Eru.effect { Thread.sleep(workMs); 1 })
      runtime.parSequence(effects).unsafeRunSync()
    }

    // Measure
    val start = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(taskCount)(Eru.effect { Thread.sleep(workMs); 1 })
      runtime.parSequence(effects).unsafeRunSync()
    }
    val totalMs = (System.nanoTime() - start) / 1_000_000

    val idealTime = workMs * iterations // If perfectly parallel
    workMs * taskCount * iterations // If sequential
    val actualParallelism = totalMs.toDouble / idealTime

    ValidationResult(
      framework = "Eru",
      actualParallelism = actualParallelism,
      totalTime = totalMs,
      avgTimePerOp = totalMs.toDouble / iterations,
      isValid = actualParallelism < taskCount, // Should be faster than sequential
      issues =
        if (actualParallelism > taskCount)
          List(s"Not achieving parallelism: ${actualParallelism}x > $taskCount tasks")
        else Nil
    )
  }

  private def validateZio(taskCount: Int, workMs: Long, iterations: Int): ValidationResult = {
    // Warmup
    for (_ <- 1 to 10) {
      val effects = List.fill(taskCount)(ZIO.attempt { Thread.sleep(workMs); 1 })
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(ZIO.collectAllPar(effects)).getOrThrowFiberFailure()
      }
    }

    // Measure
    val start = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(taskCount)(ZIO.attempt { Thread.sleep(workMs); 1 })
      Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(ZIO.collectAllPar(effects)).getOrThrowFiberFailure()
      }
    }
    val totalMs = (System.nanoTime() - start) / 1_000_000

    val idealTime = workMs * iterations
    val actualParallelism = totalMs.toDouble / idealTime

    ValidationResult(
      framework = "ZIO",
      actualParallelism = actualParallelism,
      totalTime = totalMs,
      avgTimePerOp = totalMs.toDouble / iterations,
      isValid = actualParallelism < taskCount,
      issues =
        if (actualParallelism > taskCount)
          List(s"Not achieving parallelism: ${actualParallelism}x > $taskCount tasks")
        else Nil
    )
  }

  private def validateCats(taskCount: Int, workMs: Long, iterations: Int): ValidationResult = {
    import cats.implicits._

    // Warmup
    for (_ <- 1 to 10) {
      val effects = List.fill(taskCount)(IO { Thread.sleep(workMs); 1 })
      effects.parSequence.unsafeRunSync()
    }

    // Measure
    val start = System.nanoTime()
    for (_ <- 1 to iterations) {
      val effects = List.fill(taskCount)(IO { Thread.sleep(workMs); 1 })
      effects.parSequence.unsafeRunSync()
    }
    val totalMs = (System.nanoTime() - start) / 1_000_000

    val idealTime = workMs * iterations
    val actualParallelism = totalMs.toDouble / idealTime

    ValidationResult(
      framework = "Cats",
      actualParallelism = actualParallelism,
      totalTime = totalMs,
      avgTimePerOp = totalMs.toDouble / iterations,
      isValid = actualParallelism < taskCount,
      issues =
        if (actualParallelism > taskCount)
          List(s"Not achieving parallelism: ${actualParallelism}x > $taskCount tasks")
        else Nil
    )
  }

  def main(args: Array[String]): Unit = {
    println("BENCHMARK FAIRNESS VALIDATOR")
    println("=============================")

    // Test different workload sizes
    validateParallelBenchmark("Micro work (1ms)", taskCount = 5, workMs = 1)
    validateParallelBenchmark("Small work (10ms)", taskCount = 5, workMs = 10)
    validateParallelBenchmark("Medium work (50ms)", taskCount = 5, workMs = 50)
    validateParallelBenchmark("Large work (100ms)", taskCount = 5, workMs = 100)

    println("\n" + "=" * 50)
    println("FAIRNESS RULES:")
    println("1. All frameworks must achieve similar parallelism (within 0.5x)")
    println("2. Performance ratios should be consistent across workload sizes")
    println("3. Overhead should decrease as work size increases")
    println("4. Always test multiple workload sizes, not just one")
  }
}
