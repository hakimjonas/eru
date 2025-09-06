import xerial.sbt.Sonatype.autoImport.*
import xerial.sbt.Sonatype.{sonatypeCentralHost, sonatypeSettings}
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scalanativecrossproject.ScalaNativeCrossPlugin.autoImport.*
import scala.scalanative.build.*

// ===== Build-wide Settings =====
ThisBuild / organization := "net.ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.7.2"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// ===== Publishing Settings =====
ThisBuild / sonatypeRepository := sonatypeCentralHost
ThisBuild / sonatypeProfileName := "net.ghoula"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/hakimjonas/eru"))
ThisBuild / developers := List(
  Developer("hakimjonas", "Hakim Jonas Ghoula", "hakim@ghoula.net", url("https://github.com/hakimjonas"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/hakimjonas/eru"), "scm:git@github.com:hakimjonas/eru.git")
)

// ===== Compiler Settings =====
lazy val sharedScalacOptions = Seq(
  "-feature",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-no-indent"
)

lazy val testScalacOptions = Seq(
  "-Wunused:imports" // Less strict for tests
)

// ===== Common Settings =====
lazy val commonSettings = Seq(
  scalacOptions ++= sharedScalacOptions,
  Test / scalacOptions ++= testScalacOptions,
  javacOptions ++= Seq("--release", "21")
)

// ===== Project Definitions =====
lazy val root = (project in file("."))
  .aggregate(
    eruCoreJVM,
    eruCoreNative,
    eruRuntimeJVM,
    eruRuntimeNative,
    eruBenchJVM,
    eruIntegrationTest
  )
  .settings(
    name := "eru-root",
    publish / skip := true,
    // Clean task that properly removes all target directories
    cleanAll := {
      val log = streams.value.log
      log.info("Cleaning all target directories...")

      // First run sbt's clean
      clean.value

      // Then manually clean any remaining target directories
      import java.nio.file.{Files, Path, Paths}
      import java.nio.file.attribute.BasicFileAttributes
      import java.nio.file.FileVisitResult
      import java.nio.file.SimpleFileVisitor
      import scala.util.Try

      val rootPath = Paths.get(baseDirectory.value.getAbsolutePath)

      Try {
        Files.walkFileTree(
          rootPath,
          new SimpleFileVisitor[Path] {
            override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
              if (dir.getFileName.toString == "target") {
                log.info(s"Removing: ${dir.toString}")
                deleteDirectory(dir)
                FileVisitResult.SKIP_SUBTREE
              } else if (dir.getFileName.toString.startsWith(".")) {
                FileVisitResult.SKIP_SUBTREE
              } else {
                FileVisitResult.CONTINUE
              }
            }

            def deleteDirectory(path: Path): Unit = {
              if (Files.exists(path)) {
                Files
                  .walk(path)
                  .sorted(java.util.Comparator.reverseOrder())
                  .forEach(p => Try(Files.delete(p)))
              }
            }
          }
        )
      }.fold(
        err => log.error(s"Failed to clean directories: ${err.getMessage}"),
        _ => log.info("Successfully cleaned all target directories")
      )
    }
  )
  .settings(
    // Performance benchmarking commands
    addCommandAlias("bench", "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 .*"),
    addCommandAlias("benchBaseline", "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 .*BaselineBench.*"),
    addCommandAlias("benchValidation", "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 .*ValidationBench.*"),
    addCommandAlias(
      "benchCore",
      "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 .*EruMapFlatMapBench.* .*EruRuntimeBench.*"
    ),
    addCommandAlias("benchWithGC", "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 -prof gc"),
    addCommandAlias("benchWithStack", "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 -prof stack"),
    addCommandAlias(
      "benchWithPerfasm",
      "eruBenchJVM/Jmh/run -i 10 -wi 5 -f1 -t1 -prof perfasm .*BaselineBench.*"
    ),
    addCommandAlias("benchValidationSuite", "benchBaseline; benchValidation; benchCore"),

    // Build and format commands
    addCommandAlias("prepare", "scalafixAll; scalafmtAll; scalafmtSbt; Test/compile"),
    addCommandAlias(
      "check",
      "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"
    ),
    addCommandAlias("testAll", "test; eruIntegrationTest/test")
  )

// Custom clean task
lazy val cleanAll = taskKey[Unit]("Clean all target directories including all subprojects")

// ===== Core Library (Cross-platform) =====
lazy val eruCore = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("eru-core"))
  .settings(commonSettings)
  .settings(sonatypeSettings *)
  .settings(
    name := "eru-core",
    usePgpKeyHex("9614A0CE1CE76975"),
    useGpgAgent := true,
    mimaPreviousArtifacts := Set.empty,
    tastyMiMaPreviousArtifacts := Set.empty,
    mimaFailOnNoPrevious := false,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0" % Runtime,
      "org.scalameta" %%% "munit" % "1.1.1" % Test,
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test
    )
  )
  .jvmSettings(
    testFrameworks += new TestFramework("munit.Framework")
  )
  .nativeSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= { c =>
      c.withLTO(LTO.full)
        .withMode(Mode.releaseFast)
        .withGC(GC.immix)
    }
  )

lazy val eruCoreJVM = eruCore.jvm
lazy val eruCoreNative = eruCore.native

// ===== Runtime (Cross-platform) =====
lazy val eruRuntime = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("eru-runtime"))
  .settings(commonSettings)
  .settings(sonatypeSettings *)
  .settings(
    name := "eru-runtime",
    usePgpKeyHex("9614A0CE1CE76975"),
    useGpgAgent := true,
    mimaPreviousArtifacts := Set.empty,
    tastyMiMaPreviousArtifacts := Set.empty,
    mimaFailOnNoPrevious := false,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      "org.scalameta" %%% "munit" % "1.1.1" % Test,
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test
    )
  )
  .jvmSettings(
    testFrameworks += new TestFramework("munit.Framework")
  )
  .nativeSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= { c =>
      c.withLTO(LTO.full)
        .withMode(Mode.releaseFast)
        .withGC(GC.immix)
    }
  )
  .dependsOn(eruCore)

lazy val eruRuntimeJVM = eruRuntime.jvm
lazy val eruRuntimeNative = eruRuntime.native

// ===== Benchmarks (JVM only) =====
lazy val eruBenchJVM = (project in file("eru-bench-jvm"))
  .enablePlugins(JmhPlugin)
  .dependsOn(eruCoreJVM, eruRuntimeJVM)
  .settings(commonSettings)
  .settings(
    name := "eru-bench-jvm",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.21",
      "org.typelevel" %% "cats-effect" % "3.6.3"
    ),
    // JMH settings
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    Jmh / classDirectory := (Compile / classDirectory).value,
    Jmh / dependencyClasspath := (Compile / dependencyClasspath).value,
    Jmh / compile := (Jmh / compile).dependsOn(Compile / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated
  )

// ===== Integration Tests (JVM only) =====
lazy val eruIntegrationTest = (project in file("eru-integration-test"))
  .dependsOn(
    eruCoreJVM % "compile->compile;test->test",
    eruRuntimeJVM % "compile->compile;test->test"
  )
  .settings(commonSettings)
  .settings(
    name := "eru-integration-test",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")
  )

// ===== Global Settings =====
Global / onChangedBuildSource := ReloadOnSourceChanges
Global / excludeLintKeys += cleanAll

// Enable sbt-pgp plugin
enablePlugins(SbtPgp)
