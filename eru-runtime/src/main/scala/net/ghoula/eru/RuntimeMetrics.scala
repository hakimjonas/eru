package net.ghoula.eru

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** Runtime metrics for transparent observability.
  *
  * A lock-free counter-collection covering effect execution, fiber lifecycle, suspensions, and
  * typed errors. Metrics are opt-in: create an instance and record into it from your own middleware
  * or observer — the runtime does not record into it automatically. Snapshots expose derived rates
  * (success rate, average execution time, fiber completion rate).
  */
final class RuntimeMetrics {

  private val effectsStarted = new AtomicLong(0)
  private val effectsCompleted = new AtomicLong(0)
  private val effectsFailed = new AtomicLong(0)

  private val fibersCreated = new AtomicLong(0)
  private val fibersCompleted = new AtomicLong(0)
  private val fibersInterrupted = new AtomicLong(0)
  private val activeFibers = new AtomicLong(0)

  private val suspensionsCreated = new AtomicLong(0)
  private val suspensionsCompleted = new AtomicLong(0)

  private val queueOps = new AtomicLong(0)
  private val refOps = new AtomicLong(0)
  private val promiseOps = new AtomicLong(0)
  private val semaphoreOps = new AtomicLong(0)

  private val totalExecutionTimeNanos = new AtomicLong(0)
  private val peakActiveFibers = new AtomicLong(0)

  private val errorCounts = TrieMap.empty[String, AtomicLong]

  /** Record effect started. */
  def recordEffectStart(): Unit = effectsStarted.incrementAndGet()

  /** Record effect completed. */
  def recordEffectComplete(success: Boolean, durationNanos: Long): Unit = {
    effectsCompleted.incrementAndGet()
    if (!success) effectsFailed.incrementAndGet()
    totalExecutionTimeNanos.addAndGet(durationNanos)
  }

  /** Record fiber created. */
  def recordFiberCreate(): Unit = {
    fibersCreated.incrementAndGet()
    val current = activeFibers.incrementAndGet()

    var peak = peakActiveFibers.get()
    while (current > peak && !peakActiveFibers.compareAndSet(peak, current)) {
      peak = peakActiveFibers.get()
    }
  }

  /** Record fiber completed. */
  def recordFiberComplete(interrupted: Boolean): Unit = {
    fibersCompleted.incrementAndGet()
    if (interrupted) fibersInterrupted.incrementAndGet()
    activeFibers.decrementAndGet()
  }

  /** Record suspension. */
  def recordSuspension(): Unit = suspensionsCreated.incrementAndGet()

  /** Record suspension completed. */
  def recordSuspensionComplete(): Unit = suspensionsCompleted.incrementAndGet()

  /** Record queue operation. */
  def recordQueueOp(): Unit = queueOps.incrementAndGet()

  /** Record ref operation. */
  def recordRefOp(): Unit = refOps.incrementAndGet()

  /** Record promise operation. */
  def recordPromiseOp(): Unit = promiseOps.incrementAndGet()

  /** Record semaphore operation. */
  def recordSemaphoreOp(): Unit = semaphoreOps.incrementAndGet()

  /** Record error by type. */
  def recordError(errorType: String): Unit = {
    errorCounts.getOrElseUpdate(errorType, new AtomicLong(0)).incrementAndGet()
  }

  /** Get current metrics snapshot. */
  def snapshot(): MetricsSnapshot = MetricsSnapshot(
    effectsStarted = effectsStarted.get(),
    effectsCompleted = effectsCompleted.get(),
    effectsFailed = effectsFailed.get(),
    fibersCreated = fibersCreated.get(),
    fibersCompleted = fibersCompleted.get(),
    fibersInterrupted = fibersInterrupted.get(),
    activeFibers = activeFibers.get(),
    peakActiveFibers = peakActiveFibers.get(),
    suspensionsCreated = suspensionsCreated.get(),
    suspensionsCompleted = suspensionsCompleted.get(),
    queueOps = queueOps.get(),
    refOps = refOps.get(),
    promiseOps = promiseOps.get(),
    semaphoreOps = semaphoreOps.get(),
    totalExecutionTimeNanos = totalExecutionTimeNanos.get(),
    errorCounts = errorCounts.map { case (k, v) => k -> v.get() }.toMap
  )

  /** Reset all metrics. */
  def reset(): Unit = {
    effectsStarted.set(0)
    effectsCompleted.set(0)
    effectsFailed.set(0)
    fibersCreated.set(0)
    fibersCompleted.set(0)
    fibersInterrupted.set(0)
    activeFibers.set(0)
    peakActiveFibers.set(0)
    suspensionsCreated.set(0)
    suspensionsCompleted.set(0)
    queueOps.set(0)
    refOps.set(0)
    promiseOps.set(0)
    semaphoreOps.set(0)
    totalExecutionTimeNanos.set(0)
    errorCounts.clear()
  }
}

/** Immutable snapshot of runtime metrics. */
case class MetricsSnapshot(
  effectsStarted: Long,
  effectsCompleted: Long,
  effectsFailed: Long,
  fibersCreated: Long,
  fibersCompleted: Long,
  fibersInterrupted: Long,
  activeFibers: Long,
  peakActiveFibers: Long,
  suspensionsCreated: Long,
  suspensionsCompleted: Long,
  queueOps: Long,
  refOps: Long,
  promiseOps: Long,
  semaphoreOps: Long,
  totalExecutionTimeNanos: Long,
  errorCounts: Map[String, Long]
) {

  /** Success rate as a percentage. */
  def successRate: Double = {
    if (effectsCompleted == 0) 100.0
    else ((effectsCompleted - effectsFailed).toDouble / effectsCompleted) * 100
  }

  /** Average execution time in milliseconds. */
  def averageExecutionTimeMs: Double = {
    if (effectsCompleted == 0) 0.0
    else totalExecutionTimeNanos.toDouble / effectsCompleted / 1_000_000
  }

  /** Fiber completion rate. */
  def fiberCompletionRate: Double = {
    if (fibersCreated == 0) 100.0
    else (fibersCompleted.toDouble / fibersCreated) * 100
  }

  /** Pretty print metrics. */
  def prettyPrint: String = {
    s"""
      |=== Eru Runtime Metrics ===
      |
      |Effects:
      |  Started:    $effectsStarted
      |  Completed:  $effectsCompleted
      |  Failed:     $effectsFailed
      |  Success:    ${f"$successRate%.2f"}%
      |  Avg Time:   ${f"$averageExecutionTimeMs%.2f"} ms
      |
      |Fibers:
      |  Created:     $fibersCreated
      |  Completed:   $fibersCompleted
      |  Interrupted: $fibersInterrupted
      |  Active:      $activeFibers
      |  Peak Active: $peakActiveFibers
      |  Completion:  ${f"$fiberCompletionRate%.2f"}%
      |
      |Suspensions:
      |  Created:   $suspensionsCreated
      |  Completed: $suspensionsCompleted
      |
      |Operations:
      |  Queue:     $queueOps
      |  Ref:       $refOps
      |  Promise:   $promiseOps
      |  Semaphore: $semaphoreOps
      |
      |Errors:
      |${errorCounts.map { case (k, v) => s"  $k: $v" }.mkString("\n")}
      |==========================
    """.stripMargin
  }
}
