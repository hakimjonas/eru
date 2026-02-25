import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanativecrossproject.ScalaNativeCrossPlugin.autoImport.*

import scala.scalanative.build.*
import scala.sys.process.Process

// ===== Build-wide Settings =====
ThisBuild / organization := "net.ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.8.2"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// ===== Publishing Settings =====
// GitHub Packages only
ThisBuild / publishTo := Some("GitHub Package Registry" at "https://maven.pkg.github.com/hakimjonas/eru")
ThisBuild / publishMavenStyle := true

// GitHub Packages authentication
ThisBuild / credentials ++= sys.env
  .get("GITHUB_TOKEN")
  .map { token =>
    Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      "hakimjonas",
      token
    )
  }
  .toSeq
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
  "-Werror",
  "-Wunused:all",
  "-Wrecurse-with-default",
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
    eruBenchMatrix,
    eruIntegrationTest,
    docs,
    site
  )
  .settings(commonSettings)
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
    // Fair benchmark system commands (use ./run-fair-benchmarks.sh for full system)
    addCommandAlias("benchCore", "eruBenchJVM/Jmh/run -i 5 -wi 3 -f1 -t1 .*CoreOperationsBench.*"),
    addCommandAlias("benchState", "eruBenchJVM/Jmh/run -i 5 -wi 3 -f1 -t1 .*StateManagementBench.*"),
    addCommandAlias("benchConcurrency", "eruBenchJVM/Jmh/run -i 5 -wi 3 -f1 -t1 .*ConcurrencyBench.*"),
    addCommandAlias("benchWithGC", "eruBenchJVM/Jmh/run -i 5 -wi 3 -f1 -t1 -prof gc"),

    // Matrix benchmark system commands
    addCommandAlias("benchMatrix", "eruBenchMatrix/Jmh/run"),
    addCommandAlias("benchConcurrencyMatrix", "eruBenchMatrix/Jmh/run .*ConcurrencyScalingBench.*"),
    addCommandAlias("benchDepthMatrix", "eruBenchMatrix/Jmh/run .*DepthScalingBench.*"),
    addCommandAlias("benchDataMatrix", "eruBenchMatrix/Jmh/run .*DataSizeScalingBench.*"),
    addCommandAlias("benchMatrixWithGC", "eruBenchMatrix/Jmh/run -prof gc"),

    // Build and format commands
    addCommandAlias("prepare", "scalafmtAll; scalafmtSbt; scalafixAll; Test/compile"),
    addCommandAlias(
      "check",
      "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"
    ),

    // Core test commands
    addCommandAlias("testNative", "eruCoreNative/test; eruRuntimeNative/test"),
    addCommandAlias("testIntegration", "eruIntegrationTest/test"),

    // JVM test command
    addCommandAlias("testJVM", "eruCoreJVM/test; eruRuntimeJVM/test"),

    // Isolated test runner (prevents resource contention)
    // Use ./run-all-tests.sh instead of sbt testAll for reliable test execution

    // Documentation commands
    addCommandAlias("docs", "docs/mdoc"),
    addCommandAlias("docsWatch", "docs/mdoc --watch"),
    addCommandAlias("docsSite", "site/makeSite"),
    addCommandAlias("docsPublish", "site/ghpagesPushSite"),
    addCommandAlias("docsApi", "site/unidoc")
  )

// Custom clean task
lazy val cleanAll = taskKey[Unit]("Clean all target directories including all subprojects")

// ===== Core Library (Cross-platform) =====
lazy val eruCore = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("eru-core"))
  .settings(commonSettings)
  .settings(
    name := "eru-core",
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
        .withMode(scala.scalanative.build.Mode.releaseFast)
        .withGC(GC.immix)
    },
    // Make native compilation more visible
    logLevel := Level.Info
  )

lazy val eruCoreJVM = eruCore.jvm
lazy val eruCoreNative = eruCore.native

// ===== Runtime (Cross-platform) =====
lazy val eruRuntime = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("eru-runtime"))
  .settings(commonSettings)
  .settings(
    name := "eru-runtime",
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      "org.scalameta" %%% "munit" % "1.1.1" % Test,
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test
    )
  )
  .jvmSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b"),
    Test / testForkedParallel := false,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-XX:+UseZGC", // Enable ZGC (optimal for Project Loom)
      "-Xms2G", // Initial heap size
      "-Xmx2G", // Max heap size
      "-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"
    )
  )
  .nativeSettings(
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= { c =>
      c.withLTO(LTO.full)
        .withMode(Mode.releaseFast)
        .withGC(GC.immix)
    },
    logLevel := Level.Info
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
      "dev.zio" %% "zio" % "2.1.22",
      "org.typelevel" %% "cats-effect" % "3.6.3"
    ),
    // JMH settings
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    Jmh / classDirectory := (Compile / classDirectory).value,
    Jmh / dependencyClasspath := (Compile / dependencyClasspath).value,
    Jmh / compile := (Jmh / compile).dependsOn(Compile / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated
  )

// ===== Matrix Benchmarks (JVM only) =====
lazy val eruBenchMatrix = (project in file("eru-bench-matrix"))
  .enablePlugins(JmhPlugin)
  .dependsOn(eruCoreJVM, eruRuntimeJVM)
  .settings(commonSettings)
  .settings(
    name := "eru-bench-matrix",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.22",
      "org.typelevel" %% "cats-effect" % "3.6.3"
    ),
    // JMH settings for matrix benchmarks
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
      "org.scalameta" %% "munit" % "1.2.1" % Test
    ),
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")
  )

// ===== Documentation Validation (mdoc) =====
lazy val docs = project
  .in(file("eru-docs"))
  .enablePlugins(MdocPlugin)
  .dependsOn(eruCoreJVM, eruRuntimeJVM)
  .settings(
    name := "eru-docs",
    publish / skip := true,
    mdocIn := file("docs-src"),
    mdocOut := (ThisBuild / baseDirectory).value / "eru-docs" / "target" / "mdoc",
    mdocVariables := Map(
      "VERSION" -> version.value,
      "SCALA_VERSION" -> scalaVersion.value
    ),
    mdoc := {
      val result: Unit = mdoc.evaluated
      val baseDir = (ThisBuild / baseDirectory).value
      val mdocOutputDir = mdocOut.value

      // Files that should be copied to root
      val rootFiles = Seq("README.md", "CONTRIBUTING.md")

      rootFiles.foreach { fileName =>
        val source = mdocOutputDir / fileName
        val target = baseDir / fileName
        if (source.exists()) {
          IO.copyFile(source, target)
          streams.value.log.info(s"Copied $fileName to project root")
        }
      }

      result
    }
  )

// ===== Site Generation & GitHub Pages =====
lazy val site = project
  .in(file("eru-site"))
  .enablePlugins(SiteScaladocPlugin, GhpagesPlugin, ScalaUnidocPlugin)
  .dependsOn(eruCoreJVM, eruRuntimeJVM)
  .settings(
    name := "eru-site",
    publish / skip := true,

    // Unidoc settings for cross-platform ScalaDoc
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(eruCoreJVM, eruRuntimeJVM),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq(
      "-groups",
      "-doc-title",
      "Eru",
      "-doc-version",
      version.value,
      "-sourcepath",
      (ThisBuild / baseDirectory).value.getAbsolutePath,
      "-doc-source-url",
      s"https://github.com/hakimjonas/eru/tree/v${version.value}€{FILE_PATH}.scala"
    ),

    // Site structure
    SiteScaladoc / siteSubdirName := s"api/${version.value}",
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, SiteScaladoc / siteSubdirName),

    // GitHub Pages settings
    git.remoteRepo := "git@github.com:hakimjonas/eru.git",
    ghpagesNoJekyll := true,
    ghpagesBranch := "gh-pages",

    // Custom domain
    ghpagesRepository := file("/tmp/gh-pages-eru"),
    ghpagesPushSite := {
      val repo = ghpagesRepository.value
      val log = streams.value.log

      // Ensure repo exists
      if (!repo.exists) {
        log.info(s"Cloning gh-pages to $repo")
        Process(Seq("git", "clone", "-b", "gh-pages", git.remoteRepo.value, repo.getAbsolutePath)).!
      }

      // Create CNAME file for custom domain
      val cnameFile = repo / "CNAME"
      IO.write(cnameFile, "eru.ghoula.net")

      // Run default push
      ghpagesPushSite.value
    },

    // Site mappings for versioned docs
    makeSite / mappings ++= Seq(
      file("docs-src/MANIFESTO.md") -> "vision.md",
      file("README.md") -> "index.md"
    )
  )

// ===== Global Settings =====
Global / onChangedBuildSource := ReloadOnSourceChanges
Global / excludeLintKeys += cleanAll
