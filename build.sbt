import xerial.sbt.Sonatype.autoImport.*
import xerial.sbt.Sonatype.{sonatypeCentralHost, sonatypeSettings}
enablePlugins(SbtPgp)
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scalanativecrossproject.ScalaNativeCrossPlugin.autoImport.*
import scala.scalanative.build.*
import _root_.mdoc.MdocPlugin

// ===== Build‑wide Settings =====
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
ThisBuild / scalacOptions ++= Seq(
  "-feature",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wunused:imports",
  "-no-indent"
)
// Deduplicate scalacOptions across scopes to avoid repeated-flag warnings
ThisBuild / scalacOptions := (ThisBuild / scalacOptions).value.distinct
Compile / scalacOptions := (Compile / scalacOptions).value.distinct
Test / scalacOptions := (Test / scalacOptions).value.distinct

ThisBuild / javacOptions ++= Seq("--release", "21")

// ===== Project Definitions =====
lazy val root = (project in file("."))
  .aggregate(eruCoreJVM, eruCoreNative, eruRuntimeJVM, eruRuntimeNative)
  .settings(
    name := "eru-root",
    publish / skip := true,
    // Performance benchmarking aliases
    addCommandAlias("bench", "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1"),
    addCommandAlias("benchBaseline", "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 .*BaselineBench.*"),
    addCommandAlias("benchValidation", "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 .*ValidationBench.*"),
    addCommandAlias(
      "benchCore",
      "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 .*EruMapFlatMapBench.* .*EruRuntimeBench.*"
    ),
    addCommandAlias("benchWithGC", "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 -prof gc"),
    addCommandAlias("benchWithStack", "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 -prof stack"),
    addCommandAlias(
      "benchWithPerfasm",
      "project eruBenchJVM; jmh:run -i 10 -wi 5 -f1 -t1 -prof perfasm .*BaselineBench.*"
    ),
    addCommandAlias("benchValidationSuite", "benchBaseline; benchValidation; benchCore"),

    // Build and documentation aliases
    addCommandAlias("prepare", "scalafixAll; scalafmtAll; scalafmtSbt; compile; test; eruCoreJVM/mdoc"),
    addCommandAlias(
      "check",
      "eruCoreJVM/mdoc --check; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"
    )
  )

lazy val eruCore = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("eru-core"))
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
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0" % "runtime",
      "org.scalameta" %%% "munit" % "1.1.1" % Test,
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test
    )
  )
  .jvmSettings(
    // --- MDoc Configuration ---
    mdocIn := file("docs-src"),
    mdocOut := file(".")
  )
  .jvmConfigure(_.enablePlugins(MdocPlugin))
  .nativeSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= { c =>
      c.withLTO(LTO.thin).withMode(Mode.releaseFast).withGC(GC.immix)
    }
  )

// ===== Convenience Aliases =====
lazy val eruCoreJVM = eruCore.jvm
lazy val eruCoreNative = eruCore.native

// ===== Runtime (JVM & Native) =====
lazy val eruRuntime = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("eru-runtime"))
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
      "org.scalameta" %%% "munit" % "1.1.1" % Test
    )
  )
  .nativeSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= { c =>
      c.withLTO(LTO.thin).withMode(Mode.releaseFast).withGC(GC.immix)
    }
  )
  .dependsOn(eruCore)

lazy val eruRuntimeJVM = eruRuntime.jvm
lazy val eruRuntimeNative = eruRuntime.native

// ===== Benchmarks (JVM only) =====
lazy val eruBenchJVM = (project in file("eru-bench-jvm"))
  .dependsOn(eruCoreJVM, eruRuntimeJVM)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "eru-bench-jvm",
    publish / skip := true
  )
