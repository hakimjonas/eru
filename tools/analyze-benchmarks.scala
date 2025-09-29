#!/usr/bin/env scala

import scala.io.Source
import scala.util.{Try, Using}
import java.nio.file.{Files, Paths}

case class BenchmarkResult(
  benchmark: String,
  mode: String,
  score: Double,
  unit: String
)

case class CategoryAnalysis(
  category: String,
  eruVsCats: Map[String, Double],
  eruVsZio: Map[String, Double],
  topPerformers: List[(String, Double)],
  concerns: List[String]
)

object BenchmarkAnalyzer {
  def main(args: Array[String]): Unit = {
    val timestamp = if (args.nonEmpty) args(0) else "2025-09-29_06-49-54"

    println("=" * 80)
    println(s"COMPREHENSIVE BENCHMARK ANALYSIS - $timestamp")
    println("=" * 80)

    val categories = List(
      "core-operations",
      "error-handling",
      "collection-operations",
      "concurrency",
      "coordination",
      "resource-management",
      "stack-safety",
      "state-management"
    )

    val analyses = categories.flatMap { cat =>
      analyzeCategory(cat, timestamp)
    }

    // Overall summary
    println("\n" + "=" * 80)
    println("EXECUTIVE SUMMARY")
    println("=" * 80)

    printOverallPerformance(analyses)
    printKeyFindings(analyses)
    printRecommendations(analyses)
  }

  def analyzeCategory(category: String, timestamp: String): Option[CategoryAnalysis] = {
    val file = s"benchmark-results/$category-$timestamp.json"
    if (!Files.exists(Paths.get(file))) {
      println(s"⚠️  File not found: $file")
      return None
    }

    val results = parseResults(file)
    if (results.isEmpty) return None

    println(s"\n📊 $category".toUpperCase)
    println("-" * 60)

    val eruResults = results.filter(_.benchmark.toLowerCase.contains("eru"))
    val zioResults = results.filter(_.benchmark.toLowerCase.contains("zio"))
    val catsResults = results.filter(_.benchmark.toLowerCase.contains("io"))
      .filterNot(_.benchmark.toLowerCase.contains("zio"))

    val comparisons = eruResults.flatMap { eru =>
      val baseName = eru.benchmark.replaceFirst("^\\w+\\.", "").replaceFirst("^eru", "")

      val zioEquiv = zioResults.find(z =>
        z.benchmark.replaceFirst("^\\w+\\.", "").replaceFirst("^zio", "") == baseName
      )
      val catsEquiv = catsResults.find(c =>
        c.benchmark.replaceFirst("^\\w+\\.", "").replaceFirst("^io", "") == baseName
      )

      val eruVsCats = catsEquiv.map(c => baseName -> (eru.score / c.score))
      val eruVsZio = zioEquiv.map(z => baseName -> (eru.score / z.score))

      // Print comparison
      if (eruVsCats.isDefined || eruVsZio.isDefined) {
        val catsRatio = eruVsCats.map(_._2).getOrElse(0.0)
        val zioRatio = eruVsZio.map(_._2).getOrElse(0.0)

        val benchName = baseName.take(30).padTo(30, ' ')
        val eruScore = f"${eru.score}%8.1f"
        val catsComp = if (catsRatio > 0) f"${catsRatio}%6.1fx" else "    N/A"
        val zioComp = if (zioRatio > 0) f"${zioRatio}%6.1fx" else "    N/A"

        println(f"  $benchName | Eru: $eruScore | vs Cats: $catsComp | vs ZIO: $zioComp")
      }

      Some((eruVsCats, eruVsZio))
    }

    val eruVsCatsMap = comparisons.flatMap(_._1).toMap
    val eruVsZioMap = comparisons.flatMap(_._2).toMap

    // Find top performers
    val topPerformers = (eruVsCatsMap.toList ++ eruVsZioMap.toList)
      .sortBy(-_._2)
      .take(3)

    // Identify concerns
    val concerns = identifyConcerns(eruResults, zioResults, catsResults, eruVsCatsMap, eruVsZioMap)

    Some(CategoryAnalysis(category, eruVsCatsMap, eruVsZioMap, topPerformers, concerns))
  }

  def identifyConcerns(
    eru: List[BenchmarkResult],
    zio: List[BenchmarkResult],
    cats: List[BenchmarkResult],
    eruVsCats: Map[String, Double],
    eruVsZio: Map[String, Double]
  ): List[String] = {
    var concerns = List.empty[String]

    // Check if Eru is slower than competitors
    val slowerThanCats = eruVsCats.filter(_._2 < 1.0)
    val slowerThanZio = eruVsZio.filter(_._2 < 1.0)

    if (slowerThanCats.nonEmpty) {
      concerns = concerns :+ s"Slower than Cats in: ${slowerThanCats.keys.mkString(", ")}"
    }
    if (slowerThanZio.nonEmpty) {
      concerns = concerns :+ s"Slower than ZIO in: ${slowerThanZio.keys.mkString(", ")}"
    }

    // Check for very low throughput operations
    val lowThroughput = eru.filter(_.score < 1.0)
    if (lowThroughput.nonEmpty) {
      concerns = concerns :+ s"Low throughput (<1 ops/ms): ${lowThroughput.map(_.benchmark).mkString(", ")}"
    }

    concerns
  }

  def parseResults(file: String): List[BenchmarkResult] = {
    Try {
      Using(Source.fromFile(file)) { source =>
        val content = source.getLines().mkString("\n")

        // Simple regex parsing for JMH JSON format
        val pattern = """"benchmark"\s*:\s*"([^"]+)".*?"mode"\s*:\s*"([^"]+)".*?"primaryMetric".*?"score"\s*:\s*([\d.]+).*?"scoreUnit"\s*:\s*"([^"]+)"""".r

        pattern.findAllMatchIn(content).map { m =>
          BenchmarkResult(
            benchmark = m.group(1),
            mode = m.group(2),
            score = m.group(3).toDouble,
            unit = m.group(4)
          )
        }.toList
      }.get
    }.getOrElse(List.empty)
  }

  def printOverallPerformance(analyses: List[CategoryAnalysis]): Unit = {
    val allEruVsCats = analyses.flatMap(_.eruVsCats.values)
    val allEruVsZio = analyses.flatMap(_.eruVsZio.values)

    if (allEruVsCats.nonEmpty) {
      val avgVsCats = allEruVsCats.sum / allEruVsCats.length
      val medianVsCats = allEruVsCats.sorted.apply(allEruVsCats.length / 2)
      val maxVsCats = allEruVsCats.max

      println(f"\n📈 ERU vs CATS EFFECT:")
      println(f"   Average: ${avgVsCats}%.1fx faster")
      println(f"   Median:  ${medianVsCats}%.1fx faster")
      println(f"   Maximum: ${maxVsCats}%.1fx faster")
    }

    if (allEruVsZio.nonEmpty) {
      val avgVsZio = allEruVsZio.sum / allEruVsZio.length
      val medianVsZio = allEruVsZio.sorted.apply(allEruVsZio.length / 2)
      val maxVsZio = allEruVsZio.max

      println(f"\n📈 ERU vs ZIO:")
      println(f"   Average: ${avgVsZio}%.1fx faster")
      println(f"   Median:  ${medianVsZio}%.1fx faster")
      println(f"   Maximum: ${maxVsZio}%.1fx faster")
    }
  }

  def printKeyFindings(analyses: List[CategoryAnalysis]): Unit = {
    println("\n🔍 KEY FINDINGS:")

    // Find categories where Eru dominates
    val dominantCategories = analyses.filter { a =>
      a.eruVsCats.values.forall(_ > 1.0) && a.eruVsZio.values.forall(_ > 1.0)
    }

    if (dominantCategories.nonEmpty) {
      println(s"   ✅ Eru dominates in: ${dominantCategories.map(_.category).mkString(", ")}")
    }

    // Find top performance wins
    val topWins = analyses.flatMap(_.topPerformers).sortBy(-_._2).take(5)
    if (topWins.nonEmpty) {
      println("\n   🏆 Top 5 Performance Wins:")
      topWins.foreach { case (bench, ratio) =>
        println(f"      - $bench: ${ratio}%.1fx faster")
      }
    }

    // Aggregate concerns
    val allConcerns = analyses.flatMap(_.concerns).distinct
    if (allConcerns.nonEmpty) {
      println("\n   ⚠️  Areas for Investigation:")
      allConcerns.foreach(c => println(s"      - $c"))
    }
  }

  def printRecommendations(analyses: List[CategoryAnalysis]): Unit = {
    println("\n💡 RECOMMENDATIONS:")

    val lowThroughputOps = analyses.flatMap { a =>
      a.concerns.filter(_.contains("Low throughput"))
    }

    if (lowThroughputOps.nonEmpty) {
      println("   1. Run with GC profiling to identify if GC pressure affects low-throughput operations")
    }

    val slowerThanCompetitors = analyses.exists { a =>
      a.eruVsCats.values.exists(_ < 1.0) || a.eruVsZio.values.exists(_ < 1.0)
    }

    if (slowerThanCompetitors) {
      println("   2. Investigate operations where Eru is slower than competitors")
      println("      - Check if these involve heavy coordination or blocking operations")
      println("      - Verify if benchmark fairness issues remain")
    }

    println("   3. Run with standard JMH settings (3 warmups, 5 measurements) for production metrics")
    println("   4. Consider profiling with async-profiler for hotspot identification")
  }
}

BenchmarkAnalyzer.main(args)