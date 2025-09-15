#!/usr/bin/env scala

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._
import scala.util.{Try, Success, Failure}
import java.lang.reflect.Method
import scala.reflect.runtime.universe._

object EruApiHelper {

  def main(args: Array[String]): Unit = {
    args.headOption match {
      case Some("--list-methods") => listMethods()
      case Some("--validate") if args.length > 1 => validateSnippet(args(1))
      case Some("--imports") if args.length > 1 => showImports(args(1))
      case Some("--example") if args.length > 1 => generateExample(args(1))
      case _ => showHelp()
    }
  }

  def showHelp(): Unit = {
    println("""
Usage: scala tools/eru-api-helper.scala [command] [args]

Commands:
  --list-methods              List all public methods in Eru
  --validate <code>           Validate code snippet against Eru API
  --imports <method>          Show required imports for method
  --example <pattern>         Generate working example for pattern
  --help                      Show this help

Examples:
  scala tools/eru-api-helper.scala --list-methods
  scala tools/eru-api-helper.scala --validate "parTraverse(list)(f)"
  scala tools/eru-api-helper.scala --imports parTraverse
  scala tools/eru-api-helper.scala --example parallel-processing
""")
  }

  def listMethods(): Unit = {
    println("=== ERU CORE METHODS ===")

    // For now, let's extract methods from the source file directly
    val eruSourcePath = "eru-core/src/main/scala/net/ghoula/eru/Eru.scala"
    val eruSource = scala.io.Source.fromFile(eruSourcePath).getLines().toList

    val methods = eruSource
      .filter(line => line.trim.startsWith("def ") && !line.contains("private"))
      .map(_.trim)
      .distinct
      .sorted

    methods.foreach { method =>
      val methodName = method.split("\\s+")(1).split("\\[|\\(")(0)
      val isCore = !requiresRuntime(methodName)
      val module = if (isCore) "CORE" else "RUNTIME"
      println(f"  [$module] $methodName")
    }

    println("\n=== RUNTIME EXTENSIONS ===")
    val runtimeExtPath = "eru-runtime/shared/src/main/scala/net/ghoula/eru/RuntimeExtensions.scala"
    if (new File(runtimeExtPath).exists()) {
      val runtimeSource = scala.io.Source.fromFile(runtimeExtPath).getLines().toList
      val runtimeMethods = runtimeSource
        .filter(line => line.trim.startsWith("def ") && !line.contains("private"))
        .map(_.trim)
        .distinct
        .sorted

      runtimeMethods.foreach { method =>
        val methodName = method.split("\\s+")(1).split("\\[|\\(")(0)
        println(s"  [RUNTIME] $methodName (requires EruRuntime)")
      }
    }
  }

  def validateSnippet(code: String): Unit = {
    println(s"Validating: $code")
    println("Note: Using simple syntax validation - full API validation requires sbt compilation context")

    // Basic syntax validation - check for common issues
    val issues = scala.collection.mutable.ListBuffer[String]()

    // Check for deprecated methods
    if (code.contains("catchAll")) {
      issues += "  • Replace 'catchAll' with 'recoverWith'"
    }

    if (code.contains("Eru.loop")) {
      issues += "  • 'Eru.loop' doesn't exist - use 'Eru.iterate' or 'Eru.foldLeft'"
    }

    if (code.contains("*>")) {
      issues += "  • Operator '*>' not available - use for-comprehensions or flatMap"
    }

    // Check for anti-patterns
    if (code.contains("mapError(_.getMessage)")) {
      issues += "  • Avoid 'mapError(_.getMessage)' - use typed errors instead"
    }

    if (code.contains("throw new")) {
      issues += "  • Avoid 'throw new' - use Eru.fail() for functional error handling"
    }

    if (code.contains("var ") && !code.contains("// intentional")) {
      issues += "  • Avoid 'var' - use immutable values and Eru's state management"
    }

    if (code.contains("synchronized")) {
      issues += "  • Avoid 'synchronized' - use Eru's concurrency primitives (Semaphore, Ref, etc.)"
    }

    // Check for runtime methods - no longer an issue since prelude includes default runtime
    val runtimeMethods = List("fork", "race", "zipPar", "parTraverse", "timeout", "sleep")
    val hasRuntimeMethod = runtimeMethods.exists(code.contains)
    val hasPreludeImport = code.contains("import net.ghoula.eru.prelude")

    if (hasRuntimeMethod && !hasPreludeImport) {
      issues += "  • Runtime methods detected but missing 'import net.ghoula.eru.prelude.*'"
    }

    if (issues.isEmpty) {
      println("✅ Code passes basic validation!")
      println("  (Note: Full compilation validation would require project context)")
    } else {
      println("⚠️  Potential issues found:")
      issues.foreach(println)
    }
  }

  def showImports(methodName: String): Unit = {
    val imports = getRequiredImports(methodName)
    println(s"Required imports for '$methodName':")
    imports.foreach(imp => println(s"  $imp"))

    println(s"✨ That's it! The prelude provides everything including a default runtime.")
  }

  def generateExample(pattern: String): Unit = {
    pattern match {
      case "parallel-processing" =>
        println("""
import net.ghoula.eru.prelude.*

def parallelExample(): Eru[String, List[Int]] = {
  val numbers = (1 to 10).toList
  parTraverse(numbers)(n => Eru.succeed(n * 2))
}

val result = parallelExample().unsafeRunSync()
println(s"Results: $result")
""")

      case "basic-composition" =>
        println("""
import net.ghoula.eru.prelude.*

def basicExample(): Eru[String, String] = {
  for {
    x <- Eru.succeed(21)
    y <- Eru.succeed(21)
    result <- Eru.succeed(s"Answer: ${x + y}")
  } yield result
}

val result = basicExample().unsafeRunSync()
println(result)
""")

      case _ =>
        println(s"No example available for pattern: $pattern")
        println("Available patterns: parallel-processing, basic-composition")
    }
  }

  private def requiresRuntime(methodName: String): Boolean = {
    val runtimeMethods = Set(
      "parTraverse", "parSequence", "race", "zipPar", "foreachParN",
      "timeout", "sleep", "fork", "await"
    )
    runtimeMethods.contains(methodName)
  }

  private def getRequiredImports(methodName: String): List[String] = {
    // The prelude includes everything needed, including the default runtime
    List("import net.ghoula.eru.prelude.*")
  }

  private def suggestFixes(code: String): Unit = {
    println("\nPossible fixes:")

    if (code.contains("parTraverse") && !code.contains("prelude")) {
      println("  • Add: import net.ghoula.eru.prelude.*")
      println("  • The prelude provides everything including a default runtime")
    }

    if (code.contains("Eru.loop")) {
      println("  • Replace Eru.loop with Eru.iterate or Eru.foldLeft")
    }

    if (code.contains("catchAll")) {
      println("  • Replace catchAll with recoverWith")
    }
  }

  private def getClasspath(): String = {
    // Try to get classpath from sbt
    try {
      val output = Process(Seq("sbt", "-batch", "-error", "show eruRuntimeJVM/fullClasspath")).!!
      // Extract paths from sbt output (lines with "* Attributed(path)")
      val paths = output.split("\n")
        .filter(_.contains("* Attributed("))
        .map(line => line.substring(line.indexOf("(") + 1, line.lastIndexOf(")")))
        .mkString(":")

      if (paths.nonEmpty) paths else throw new Exception("No classpath found in sbt output")
    } catch {
      case e: Exception =>
        println(s"Failed to get sbt classpath: ${e.getMessage}")
        // Fallback: try to construct classpath from target directories
        val coreClasses = "eru-core/.jvm/target/scala-3.7.2/classes"
        val runtimeClasses = "eru-runtime/jvm/target/scala-3.7.2/classes"
        val currentCp = System.getProperty("java.class.path")
        s"$coreClasses:$runtimeClasses:$currentCp"
    }
  }
}

EruApiHelper.main(args)