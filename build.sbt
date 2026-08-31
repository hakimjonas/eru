/* ===== Build-wide Settings ===== */
ThisBuild / organization := "net.ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

/* ===== Publishing Settings =====
 *
 * Maven Central (Central Portal) is the single publication target. Releases are
 * staged locally and uploaded with `sonaRelease` (sbt 2.x built-in Central
 * Portal support); artifacts are signed by sbt-pgp (`publishSigned`). Credentials
 * are picked up automatically from SONATYPE_USERNAME / SONATYPE_PASSWORD.
 */
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / licenses := Seq("GPL-3.0-or-later" -> uri("https://www.gnu.org/licenses/gpl-3.0.txt"))
ThisBuild / homepage := Some(uri("https://github.com/hakimjonas/eru"))
ThisBuild / description := "Eru: a pragmatic and ergonomic effect system for Scala 3 on Java virtual threads."
ThisBuild / developers := List(
  Developer("hakimjonas", "Hakim Jonas Ghoula", "hakim@ghoula.net", uri("https://github.com/hakimjonas"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(uri("https://github.com/hakimjonas/eru"), "scm:git@github.com:hakimjonas/eru.git")
)

/* ===== Compiler Settings ===== */
lazy val sharedScalacOptions = Seq(
  "-feature",
  "-Werror",
  "-Wunused:all",
  "-Wrecurse-with-default",
  "-no-indent"
)

/* Less strict for tests. */
lazy val testScalacOptions = Seq(
  "-Wunused:imports"
)

/* ===== Common Settings ===== */
lazy val commonSettings = Seq(
  scalacOptions ++= sharedScalacOptions,
  Test / scalacOptions ++= testScalacOptions,
  javacOptions ++= Seq("--release", "25")
)

/* ===== Project Definitions ===== */
lazy val root = (project in file("."))
  .aggregate(
    eruCore,
    eruRuntime,
    eruTestkit,
    eruIntegrationTest,
    docs,
    examples
  )
  .settings(commonSettings)
  .settings(
    name := "eru-root",
    publish / skip := true,
    /* Clean task that properly removes all target directories. */
    cleanAll := {
      val log = streams.value.log
      log.info("Cleaning all target directories...")

      clean.value

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
    /* Build and format commands. */
    addCommandAlias("prepare", "scalafmtAll; scalafmtSbt; scalafixAll; Test/compile"),
    addCommandAlias(
      "check",
      "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck"
    ),

    /* Core test commands. */
    addCommandAlias("testIntegration", "eruIntegrationTest/Test/testFull"),

    /* JVM test command. */
    addCommandAlias("testAll", "eruCore/Test/testFull; eruTestkit/Test/testFull"),

    /* Documentation commands. */
    addCommandAlias("docs", "docs/mdoc"),
    addCommandAlias("docsWatch", "docs/mdoc --watch"),
    addCommandAlias("checkExamples", "examples/compile")
  )

/* Custom clean task. */
lazy val cleanAll = taskKey[Unit]("Clean all target directories including all subprojects")

/* ===== Core Library (JVM) ===== */
lazy val eruCore = (project in file("eru-core"))
  .settings(commonSettings)
  .settings(
    name := "eru-core",
    libraryDependencies ++= Seq(
      Dependencies.munit % Test,
      Dependencies.munitScalacheck % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

/* ===== Runtime (JVM) ===== */
lazy val eruRuntime = (project in file("eru-runtime"))
  .settings(commonSettings)
  .settings(
    name := "eru-runtime"
  )
  .dependsOn(eruCore)

/* ===== Test Kit (deterministic time, shipped for downstream testing) =====
 *
 * Also hosts the runtime's own test suite: the suites exercise TestClock and
 * the runtime's concurrency primitives, which requires this module to depend
 * on the runtime while the suites need the kit, so tests live here.
 */
lazy val eruTestkit = (project in file("eru-testkit"))
  .settings(commonSettings)
  .settings(
    name := "eru-testkit",
    /* munit is a compile dependency: EruTestSuite (the munit binding) ships in
     * this artifact, and downstream test code gets the framework transitively.
     */
    libraryDependencies ++= Seq(
      Dependencies.munit,
      Dependencies.munitScalacheck % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b"),
    Test / testForkedParallel := false,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-XX:+UseShenandoahGC",
      "-Xss4m",
      "-Xms2G",
      "-Xmx2G",
      "-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"
    )
  )
  .dependsOn(eruRuntime)

/* ===== Integration Tests (JVM only) ===== */
lazy val eruIntegrationTest = (project in file("eru-integration-test"))
  .dependsOn(
    eruCore % "compile->compile;test->test",
    eruRuntime % "compile->compile;test->test",
    eruTestkit % "test->compile"
  )
  .settings(commonSettings)
  .settings(
    name := "eru-integration-test",
    publish / skip := true,
    libraryDependencies ++= Seq(
      Dependencies.munit % Test
    ),
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")
  )

/* ===== Documentation Validation (mdoc) ===== */
lazy val docs = project
  .in(file("eru-docs"))
  .enablePlugins(MdocPlugin)
  .dependsOn(eruCore, eruRuntime)
  .settings(
    name := "eru-docs",
    publish / skip := true,
    /* mdoc depends on Undertow for its --watch browser preview, which this build never uses
     * (docs are verified with plain `sbt docs/mdoc`). Excluding it drops two flagged CVEs from
     * the resolved dependency graph; re-add if watch-mode preview is ever wanted.
     */
    excludeDependencies += ExclusionRule("io.undertow", "*"),
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

      /* Files that should be copied to root. */
      val rootFiles = Seq(
        "README.md",
        "CONTRIBUTING.md",
        "MANIFESTO.md",
        "QUICKSTART.md",
        "API.md",
        "RESOURCES.md",
        "OBSERVER.md"
      )

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

/* ===== Examples (compile-checked against the public API) ===== */
lazy val examples = (project in file("examples"))
  .dependsOn(eruCore, eruRuntime)
  .settings(
    name := "eru-examples",
    publish / skip := true,
    /* Examples are teaching material: enforce correctness (-Werror) but not
     * the unused-symbol lints that would flag deliberate API-showcase code.
     * Scalafix is skipped here (scalafixConfig := None) since its
     * RemoveUnused/OrganizeImports rules require the unused-symbol flags.
     */
    Compile / scalacOptions := Seq(
      "-encoding",
      "UTF-8",
      "-feature",
      "-deprecation",
      "-Werror",
      "-no-indent"
    ),
    scalafix / skip := true
  )

/* ===== Global Settings ===== */
Global / onChangedBuildSource := ReloadOnSourceChanges
