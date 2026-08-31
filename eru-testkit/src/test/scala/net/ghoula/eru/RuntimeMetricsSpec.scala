package net.ghoula.eru

/** Tests for the opt-in RuntimeMetrics counter collection.
  *
  * Covers every record method, the derived snapshot values, and reset. The runtime does not write
  * into RuntimeMetrics automatically; these tests exercise the API that user middleware or
  * observers call.
  */
class RuntimeMetricsSpec extends munit.FunSuite {

  test("effect metrics: start, success, failure, and total duration") {
    val m = new RuntimeMetrics
    m.recordEffectStart()
    m.recordEffectComplete(success = true, durationNanos = 100)
    m.recordEffectStart()
    m.recordEffectComplete(success = false, durationNanos = 30)

    val s = m.snapshot()
    assertEquals(s.effectsStarted, 2L)
    assertEquals(s.effectsCompleted, 2L)
    assertEquals(s.effectsFailed, 1L)
    assertEquals(s.totalExecutionTimeNanos, 130L)
  }

  test("fiber metrics: created, completed, interrupted, active, and peak") {
    val m = new RuntimeMetrics
    m.recordFiberCreate()
    m.recordFiberCreate()
    m.recordFiberComplete(interrupted = true)

    val s = m.snapshot()
    assertEquals(s.fibersCreated, 2L)
    assertEquals(s.fibersCompleted, 1L)
    assertEquals(s.fibersInterrupted, 1L)
    assertEquals(s.activeFibers, 1L)
    assertEquals(s.peakActiveFibers, 2L)
  }

  test("suspension and concurrency primitive counters") {
    val m = new RuntimeMetrics
    m.recordSuspension()
    m.recordSuspension()
    m.recordSuspensionComplete()
    m.recordQueueOp()
    m.recordRefOp()
    m.recordPromiseOp()
    m.recordSemaphoreOp()

    val s = m.snapshot()
    assertEquals(s.suspensionsCreated, 2L)
    assertEquals(s.suspensionsCompleted, 1L)
    assertEquals(s.queueOps, 1L)
    assertEquals(s.refOps, 1L)
    assertEquals(s.promiseOps, 1L)
    assertEquals(s.semaphoreOps, 1L)
  }

  test("error counts aggregate by error type") {
    val m = new RuntimeMetrics
    m.recordError("Timeout")
    m.recordError("Timeout")
    m.recordError("InvalidInput")

    val s = m.snapshot()
    assertEquals(s.errorCounts("Timeout"), 2L)
    assertEquals(s.errorCounts("InvalidInput"), 1L)
  }

  test("derived rates: successRate, averageExecutionTimeMs, fiberCompletionRate") {
    val m = new RuntimeMetrics
    m.recordEffectStart()
    m.recordEffectComplete(success = true, durationNanos = 500_000)
    m.recordEffectStart()
    m.recordEffectComplete(success = false, durationNanos = 1_500_000)
    m.recordFiberCreate()
    m.recordFiberCreate()
    m.recordFiberComplete(interrupted = false)

    val s = m.snapshot()
    assertEquals(s.successRate, 50.0)
    assertEquals(s.averageExecutionTimeMs, 1.0)
    assertEquals(s.fiberCompletionRate, 50.0)
  }

  test("derived rates report total success and zero average when nothing completed") {
    val m = new RuntimeMetrics
    val s = m.snapshot()
    assertEquals(s.successRate, 100.0)
    assertEquals(s.averageExecutionTimeMs, 0.0)
    assertEquals(s.fiberCompletionRate, 100.0)
  }

  test("reset zeroes all counters") {
    val m = new RuntimeMetrics
    m.recordEffectStart()
    m.recordEffectComplete(success = true, durationNanos = 1)
    m.recordFiberCreate()
    m.recordError("Boom")
    m.recordQueueOp()

    m.reset()
    val s = m.snapshot()
    assertEquals(s.effectsStarted, 0L)
    assertEquals(s.effectsCompleted, 0L)
    assertEquals(s.totalExecutionTimeNanos, 0L)
    assertEquals(s.fibersCreated, 0L)
    assertEquals(s.queueOps, 0L)
    assertEquals(s.errorCounts, Map.empty[String, Long])
  }

}
