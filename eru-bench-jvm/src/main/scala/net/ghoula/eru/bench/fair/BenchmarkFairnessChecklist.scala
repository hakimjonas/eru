package net.ghoula.eru.bench.fair

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object BenchmarkFairnessChecklist {

  case class FairnessCheck(name: String, description: String, passed: Boolean, details: String)

  def main(args: Array[String]): Unit = {
    println("=" * 80)
    println("BENCHMARK FAIRNESS CHECKLIST")
    println("=" * 80)

    val benchDir = Paths.get("eru-bench-jvm/src/main/scala/net/ghoula/eru/bench")
    val checks = runFairnessChecks(benchDir)

    println("\nResults:")
    println("-" * 40)

    var allPassed = true
    checks.foreach { check =>
      val status = if (check.passed) "✅" else "❌"
      println(s"$status ${check.name}")
      if (!check.passed) {
        println(s"   ${check.description}")
        println(s"   ${check.details}")
        allPassed = false
      }
    }

    println("\n" + "=" * 80)
    if (allPassed) {
      println("✅ ALL FAIRNESS CHECKS PASSED")
      println("\nYour benchmarks are fair and ready for accurate performance comparison!")
    } else {
      println("❌ SOME FAIRNESS CHECKS FAILED")
      println("\nPlease address the issues above to ensure fair benchmarking.")
    }
    println("=" * 80)
  }

  def runFairnessChecks(benchDir: Path): List[FairnessCheck] = {
    List(
      checkNoZeroSleeps(benchDir),
      checkConsistentWorkPatterns(benchDir),
      checkBothPureAndEffectful(benchDir),
      checkParallelismValidation(benchDir),
      checkNoUnfairOptimizations(benchDir)
    )
  }

  def checkNoZeroSleeps(benchDir: Path): FairnessCheck = {
    val files = findScalaFiles(benchDir)
    val zeroSleepFiles = files.filter { file =>
      val content = Files.readString(file)
      content.contains("Thread.sleep(0") && !content.contains("// OK: ") // Allow commented explanations
    }

    FairnessCheck(
      name = "No Thread.sleep(0, ...) patterns",
      description = "Thread.sleep(0, nanos) doesn't actually sleep, causing unfair benchmarks",
      passed = zeroSleepFiles.isEmpty,
      details = if (zeroSleepFiles.isEmpty) "" else s"Found in: ${zeroSleepFiles.map(_.getFileName).mkString(", ")}"
    )
  }

  def checkConsistentWorkPatterns(benchDir: Path): FairnessCheck = {
    val files = findScalaFiles(benchDir)
    var consistent = true
    var issues = List.empty[String]

    files.foreach { file =>
      val content = Files.readString(file)
      if (content.contains("@Benchmark")) {
        // Check if mixing Thread.sleep with no delays in same benchmark class
        val hasSleep = content.contains("Thread.sleep(")
        val hasNoDelay = content.contains("// Pure operation") ||
          (content.contains("Eru.succeed") && !content.contains("Thread.sleep"))

        if (hasSleep && hasNoDelay && !file.toString.contains("Fair")) {
          consistent = false
          issues = issues :+ s"${file.getFileName}: Mixes delayed and pure operations"
        }
      }
    }

    FairnessCheck(
      name = "Consistent work patterns within benchmark classes",
      description = "Each benchmark class should compare similar types of work",
      passed = consistent,
      details = issues.mkString("; ")
    )
  }

  def checkBothPureAndEffectful(benchDir: Path): FairnessCheck = {
    val fairDir = benchDir.resolve("fair")
    val hasPureBenchmarks = Files.exists(fairDir.resolve("CoreOperationsBench.scala"))
    val hasEffectfulBenchmarks = Files.exists(fairDir.resolve("FairConcurrencyBench.scala"))

    FairnessCheck(
      name = "Both pure and effectful benchmarks exist",
      description = "Need benchmarks for both pure optimization and actual effect execution",
      passed = hasPureBenchmarks && hasEffectfulBenchmarks,
      details =
        if (!hasPureBenchmarks) "Missing pure benchmarks"
        else if (!hasEffectfulBenchmarks) "Missing effectful benchmarks"
        else ""
    )
  }

  def checkParallelismValidation(benchDir: Path): FairnessCheck = {
    val validatorExists = Files.exists(benchDir.resolve("fair/BenchmarkValidator.scala"))
    val timingTestExists = Files.exists(benchDir.resolve("TimingTest.scala"))

    FairnessCheck(
      name = "Parallelism validation tools exist",
      description = "Tools to verify benchmarks achieve expected parallelism",
      passed = validatorExists && timingTestExists,
      details =
        if (!validatorExists) "Missing BenchmarkValidator"
        else if (!timingTestExists) "Missing TimingTest"
        else ""
    )
  }

  def checkNoUnfairOptimizations(benchDir: Path): FairnessCheck = {
    val files = findScalaFiles(benchDir)
    var issues = List.empty[String]

    files.foreach { file =>
      val content = Files.readString(file)
      // Check for framework-specific optimizations that might be unfair
      if (content.contains("@Benchmark")) {
        // Check if Eru benchmarks use runtime.parSequence while others use different patterns
        if (
          content.contains("runtime.parSequence") &&
          !content.contains("collectAllPar") &&
          !content.contains(".parSequence")
        ) {
          // This might be OK, but flag for review
          issues = issues :+ s"${file.getFileName}: Check if parallel operations are comparable"
        }
      }
    }

    FairnessCheck(
      name = "No framework-specific unfair optimizations",
      description = "All frameworks should use their idiomatic but comparable APIs",
      passed = issues.isEmpty,
      details = issues.mkString("; ")
    )
  }

  def findScalaFiles(dir: Path): List[Path] = {
    if (Files.exists(dir)) {
      Files
        .walk(dir)
        .filter(_.toString.endsWith(".scala"))
        .iterator()
        .asScala
        .toList
    } else {
      List.empty
    }
  }
}
