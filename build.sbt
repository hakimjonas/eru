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
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wunused:imports",
  "-no-indent"
)
ThisBuild / javacOptions ++= Seq("--release", "21")

// ===== Project Definitions =====
lazy val root = (project in file("."))
  .aggregate(eruCoreJVM, eruCoreNative)
  .settings(
    name := "eru-root",
    publish / skip := true
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
      "org.scalameta" %%% "munit" % "1.1.1" % Test
    )
  )
  .jvmSettings(
    // --- MDoc Configuration ---
    mdocIn := file("docs-src"),
    mdocOut := file("."),
    addCommandAlias("prepare", "mdoc; scalafixAll; scalafmtAll; scalafmtSbt"),
    addCommandAlias(
      "check",
      "mdoc --check; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"
    )
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
