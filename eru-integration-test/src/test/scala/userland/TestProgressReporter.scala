package userland

import munit.FunSuite

/** Base trait for tests with progress reporting.
  *
  * Provides timing and progress feedback for integration tests to help identify slow tests and
  * provide visibility into test execution.
  */
trait TestProgressReporter extends FunSuite {

  private val testStartTimes = scala.collection.mutable.Map[String, Long]()

  override def beforeEach(context: BeforeEach): Unit = {
    val testName = context.test.name
    testStartTimes(testName) = System.nanoTime()
    println(s"  ▶ ${testName.take(80)}...")
    super.beforeEach(context)
  }

  override def afterEach(context: AfterEach): Unit = {
    val testName = context.test.name
    val durationNanos = System.nanoTime() - testStartTimes.getOrElse(testName, System.nanoTime())
    val durationMs = durationNanos / 1_000_000
    val icon = if (durationMs < 10) "⚡" else if (durationMs < 50) "✓" else "⚠"
    println(f"  $icon $durationMs%3dms")
    super.afterEach(context)
  }

  override def beforeAll(): Unit = {
    println(s"\n━━━ ${getClass.getSimpleName} ━━━")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
    super.afterAll()
  }
}
