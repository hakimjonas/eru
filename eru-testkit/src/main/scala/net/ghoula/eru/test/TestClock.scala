package net.ghoula.eru.test

import java.time.{Duration, Instant}

import net.ghoula.eru.*

/** Controllable clock abstraction for time-dependent testing.
  *
  * TestClock provides control over logical time in tests: set an absolute time or advance by a
  * duration, and schedule callbacks that run when logical time reaches a target instant.
  *
  * Key capabilities:
  *   - **Precise time control**: Set absolute time or advance by specific durations
  *   - **Pending operation tracking**: Track callbacks waiting for time to progress
  *   - **Scheduled completion**: Callbacks fire when logical time reaches their target
  *
  * @example
  *   {{{
  * // Test time-dependent effects without wall-clock waits
  * EruTest.withTestClock { clock =>
  *   given runtime: EruRuntime = EruTest.testRuntime(clock)
  *
  *   val effect = Eru.succeed("completed")
  *   val fiber = effect.fork.unsafeRunSync()
  *
  *   assert(fiber.await.unsafeRunSync() == Exit.Success("completed"))
  * }
  *   }}}
  *
  * '''Design Principles:'''
  *   - **Zero Global State**: Each TestClock instance is isolated
  *   - **Precise Control**: Nanosecond-level time precision
  *   - **Pending Tracking**: Visibility into scheduled callbacks
  */
trait TestClock {

  /** Current logical time maintained by this test clock.
    *
    * This is the "current time" reported by the clock. Scheduled callbacks are triggered relative
    * to this time when it advances.
    *
    * @return
    *   the current logical instant
    */
  def currentTime: Instant

  /** Sets the logical time to the specified instant.
    *
    * This immediately updates the clock's current time and triggers any callbacks scheduled at or
    * before the new time. Callbacks waiting for future times remain pending.
    *
    * @param instant
    *   the new logical time to set
    * @return
    *   number of callbacks triggered by this time change
    */
  def setTime(instant: Instant): Int

  /** Advances logical time by the specified duration.
    *
    * Equivalent to `setTime(currentTime.plus(duration))` but more convenient for relative time
    * progression in tests.
    *
    * @param duration
    *   the duration to advance time by
    * @return
    *   number of pending operations that were completed by this advancement
    */
  def advance(duration: Duration): Int = setTime(currentTime.plus(duration))

  /** Registers a callback to run when logical time reaches `targetTime`.
    *
    * Used by `LogicalTestClock.at` (the test `Wall.at` implementation) to schedule effect execution
    * at a specific instant.
    *
    * @param targetTime
    *   the logical time when the callback should execute
    * @param callback
    *   the callback to execute when time reaches targetTime
    */
  private[test] def schedule(targetTime: Instant, callback: () => Unit): Unit

  /** Like [[schedule]], but returns a cancellation function.
    *
    * Cancelling before the clock reaches `targetTime` prevents the callback from firing; after it
    * fires (or after the clock passes the target), cancellation is a no-op. The default
    * implementation schedules without cancellation support; TestClockImpl overrides it so
    * interrupted sleep fibers can withdraw their callbacks.
    */
  private[test] def scheduleCancellable(targetTime: Instant, callback: () => Unit): () => Unit = {
    schedule(targetTime, callback)
    () => ()
  }

  /** Returns the number of callbacks currently scheduled for future logical times.
    *
    * @return
    *   count of scheduled callbacks
    */
  def pendingCount: Int

  /** Returns information about the next scheduled callback, if any.
    *
    * Provides visibility into when the next scheduled callback will run. Useful for advancing time
    * to specific completion points.
    *
    * @return
    *   Some(instant) of the next scheduled callback, or None if nothing is scheduled
    */
  def nextScheduled: Option[Instant]

  /** Advances time to trigger the next scheduled callback, if any.
    *
    * Convenience method that advances time to the exact instant of the next scheduled callback. If
    * nothing is scheduled, time is not advanced.
    *
    * @return
    *   true if time was advanced and callbacks triggered, false if nothing was scheduled
    */
  def triggerNext: Boolean = {
    nextScheduled match {
      case Some(next) =>
        setTime(next)
        true
      case None => false
    }
  }

  /** Advances time to complete all currently scheduled callbacks.
    *
    * Finds the latest scheduled instant and advances time to that point, triggering all scheduled
    * callbacks in chronological order.
    *
    * @return
    *   the number of callbacks that were scheduled at the time of the call
    */
  def completeAll: Int = {
    val pending = pendingCount
    if (pending > 0) {
      val allScheduled = getAllScheduled
      if (allScheduled.nonEmpty) {
        setTime(allScheduled.max)
      }
    }
    pending
  }

  /** Internal method to get all scheduled operation times.
    *
    * Used by completeAll to find the maximum scheduled time. Implementations should provide this
    * for complete functionality.
    */
  protected def getAllScheduled: List[Instant]
}

/** Factory for creating TestClock instances with proper integration. */
object TestClock {

  /** Creates a new TestClock starting at the current system time.
    *
    * @return
    *   a new TestClock instance initialized to the current system time
    */
  def create(): TestClock = TestClockImpl(Instant.now())

  /** Creates a new TestClock starting at the specified time.
    *
    * @param startTime
    *   the initial logical time for the test clock
    * @return
    *   a new TestClock instance initialized to the specified time
    */
  def create(startTime: Instant): TestClock = TestClockImpl(startTime)

}

/** Implementation of TestClock with logical time control and scheduled callbacks.
  *
  * This implementation maintains:
  *   - Current logical time with nanosecond precision
  *   - A map of scheduled callbacks sorted by completion time
  *   - Thread-safe access for concurrent test scenarios
  */
private[test] final class TestClockImpl(startTime: Instant) extends TestClock {

  import java.util.concurrent.ConcurrentSkipListMap
  import java.util.concurrent.atomic.AtomicReference
  import scala.jdk.CollectionConverters.*

  private val scheduledOps = new ConcurrentSkipListMap[Instant, java.util.concurrent.CopyOnWriteArrayList[() => Unit]]()
  private val _currentTimeRef = new AtomicReference[Instant](startTime)

  def currentTime: Instant = _currentTimeRef.get()

  def setTime(instant: Instant): Int = {
    _currentTimeRef.set(instant)
    var completed = 0

    val toComplete = scheduledOps.headMap(instant, true).asScala.toList

    for ((scheduledTime, _) <- toComplete) {
      Option(scheduledOps.remove(scheduledTime)) match {
        case Some(callbackList) =>
          callbackList.asScala.foreach { callback =>
            try {
              callback()
              completed += 1
            } catch {
              case _: Throwable =>
                completed += 1
            }
          }
        case None =>
      }
    }

    completed
  }

  def pendingCount: Int = {
    scheduledOps.values().asScala.map(_.size()).sum
  }

  def nextScheduled: Option[Instant] = {
    if (scheduledOps.isEmpty) None
    else Some(scheduledOps.firstKey())
  }

  protected def getAllScheduled: List[Instant] = {
    scheduledOps.keySet().asScala.toList
  }

  /** Schedules a callback to be executed when time reaches the specified instant.
    *
    * This is the integration point used by TestClockBackend to schedule sleep and timeout
    * operations for completion at specific logical times.
    *
    * @param targetTime
    *   the logical time when the callback should execute
    * @param callback
    *   the callback to execute when time reaches targetTime
    */
  private[test] def schedule(targetTime: Instant, callback: () => Unit): Unit = {
    val _ = scheduleCancellable(targetTime, callback)
  }

  override private[test] def scheduleCancellable(targetTime: Instant, callback: () => Unit): () => Unit = {
    val callbacks =
      scheduledOps.computeIfAbsent(targetTime, _ => new java.util.concurrent.CopyOnWriteArrayList[() => Unit]())
    callbacks.add(callback)
    () => { val _ = callbacks.remove(callback) }
  }

}
