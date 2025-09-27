package net.ghoula.eru.test

import java.time.{Duration, Instant}

import net.ghoula.eru.*

/** Controllable clock abstraction for deterministic time-dependent testing.
  *
  * TestClock provides precise control over time progression in tests, enabling deterministic
  * testing of timeout operations, retry policies, and time-dependent effects without relying on
  * wall clock time or Thread.sleep delays.
  *
  * Key capabilities:
  *   - **Precise time control**: Set absolute time or advance by specific durations
  *   - **Pending operation tracking**: Track effects waiting for time to progress
  *   - **Deterministic execution**: Eliminate timing-based flakiness in tests
  *   - **Full integration**: Works seamlessly with existing Eru timeout/retry operations
  *
  * @example
  *   {{{\n * // Test timeout behavior deterministically EruTest.withTestClock { clock => val effect =
  *   runtime.sleep(Duration.ofSeconds(10)).map(_ => \"completed\") val timedEffect =
  *   effect.timeout(Duration.ofSeconds(5))
  *
  * val fiber = timedEffect.fork.unsafeRunSync()
  *
  * // Advance past timeout threshold clock.advance(Duration.ofSeconds(6))
  *
  * // Should timeout assert(fiber.await.runExit().isFailure) } }}}
  *
  * '''Design Principles:'''
  *   - **Zero Global State**: Each TestClock instance is isolated
  *   - **Precise Control**: Nanosecond-level time precision
  *   - **Pending Tracking**: Complete visibility into waiting operations
  *   - **Eru Integration**: Seamless compatibility with all timing operations
  */
trait TestClock {

  /** Current logical time maintained by this test clock.
    *
    * This represents the \"current time\" from the perspective of effects running within the test
    * clock's context. All timing operations (sleep, timeout, retry delays) use this logical time
    * rather than system time.
    *
    * @return
    *   the current logical instant
    */
  def currentTime: Instant

  /** Sets the logical time to the specified instant.
    *
    * This immediately updates the clock's current time and triggers completion of any sleep or
    * timeout operations that should complete at or before the new time. Operations waiting for
    * future times remain pending.
    *
    * @param instant
    *   the new logical time to set
    * @return
    *   number of pending operations that were completed by this time change
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

  /** Returns the number of operations currently waiting for time to progress.
    *
    * This includes sleep operations, timeout timers, and retry delay timers that are scheduled to
    * complete at future logical times. Useful for verifying test expectations about pending
    * operations.
    *
    * @return
    *   count of pending time-dependent operations
    */
  def pendingCount: Int

  /** Returns information about the next scheduled operation, if any.
    *
    * Provides visibility into when the next time-dependent operation is scheduled to complete.
    * Useful for advancing time to specific operation completion points.
    *
    * @return
    *   Some(instant) of the next scheduled operation, or None if no operations pending
    */
  def nextScheduled: Option[Instant]

  /** Advances time to trigger the next scheduled operation, if any.
    *
    * Convenience method that advances time to the exact instant of the next pending operation. If
    * no operations are pending, time is not advanced.
    *
    * @return
    *   true if time was advanced and operations completed, false if no operations pending
    */
  def triggerNext: Boolean = {
    nextScheduled match {
      case Some(next) =>
        setTime(next)
        true
      case None => false
    }
  }

  /** Advances time to complete all currently pending operations.
    *
    * Finds the latest scheduled operation and advances time to that point, completing all pending
    * time-dependent operations in chronological order.
    *
    * @return
    *   number of operations completed
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

  /** Advances the scheduler to execute all ready fibers.
    *
    * This is the key method for driving TestClock execution forward. It executes all currently
    * ready fibers, then any fibers that become ready as a result, continuing until no more progress
    * can be made.
    *
    * This is similar to Cats Effect TestControl.tickAll() method.
    *
    * @return
    *   number of fibers that were executed
    */
  def tickAll: Int

  /** Advances the scheduler by one step.
    *
    * Executes one batch of ready fibers and returns. This provides more fine-grained control over
    * execution than tickAll.
    *
    * @return
    *   number of fibers that were executed in this tick
    */
  def tick: Int

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

/** Implementation of TestClock with precise time control, fiber scheduling, and coordination
  * support.
  *
  * This implementation maintains:
  *   - Current logical time with nanosecond precision
  *   - Queue of scheduled operations sorted by completion time
  *   - Fiber scheduler for coordination primitives
  *   - Thread-safe operation for concurrent test scenarios
  *   - Integration hooks for ConcurrencyBackend sleep/timeout operations
  *
  * The fiber scheduler enables proper coordination primitive support by tracking suspended fibers
  * and resuming them when conditions are met, similar to Cats Effect TestControl.
  */
private[test] final class TestClockImpl(startTime: Instant) extends TestClock {

  import java.util.concurrent.ConcurrentSkipListMap
  import java.util.concurrent.atomic.AtomicReference
  import scala.jdk.CollectionConverters.*

  private val scheduledOps = new ConcurrentSkipListMap[Instant, java.util.concurrent.CopyOnWriteArrayList[() => Unit]]()
  private val _currentTimeRef = new AtomicReference[Instant](startTime)

  private val readyFibers = new java.util.concurrent.ConcurrentLinkedQueue[() => Unit]()
  private val suspendedFibers = new java.util.concurrent.ConcurrentHashMap[String, () => Unit]()

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

  def tickAll: Int = {
    var totalExecuted = 0
    var lastExecuted = 0

    while ({
      lastExecuted = tick
      totalExecuted += lastExecuted
      lastExecuted > 0
    }) {}

    totalExecuted
  }

  def tick: Int = {
    var executed = 0
    var continue = true

    while (continue) {
      Option(readyFibers.poll()) match {
        case Some(nextFiber) =>
          try {
            nextFiber()
            executed += 1
          } catch {
            case _: Throwable =>
              executed += 1
          }
        case None =>
          continue = false
      }
    }

    executed
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
    val callbacks =
      scheduledOps.computeIfAbsent(targetTime, _ => new java.util.concurrent.CopyOnWriteArrayList[() => Unit]())
    callbacks.add(callback)
  }

  /** Schedules a fiber for execution in the TestClock controlled environment.
    *
    * Instead of executing immediately, the fiber is scheduled to run when TestClock time advances.
    * This enables deterministic testing where fiber execution is controlled by explicit time
    * advancement rather than immediate execution.
    *
    * @param fiber
    *   the fiber to complete when execution occurs
    * @param effect
    *   the effect to execute
    * @param observer
    *   optional observer for fiber lifecycle events
    */
  def scheduleFiberExecution[E, A](
    fiber: UnifiedFiber[E, A],
    effect: Eru[E, A],
    observer: Option[EruObserver]
  ): Unit = {
    val executionTime = currentTime.plusNanos(1)

    schedule(
      executionTime,
      () => {
        val exitOption =
          try {
            Some(Result.toExit(effect.attempt.unsafeRunSync()))
          } catch {
            case e: RuntimeException if e.getMessage.contains("TestClock suspend operation") =>
              None
            case t: Throwable =>
              Some(Exit.Die(t))
          }

        exitOption.foreach { exit =>
          UnifiedFiber.complete(fiber, exit)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(fiber.id, exit)))
        }
      }
    )
  }

  /** Enqueues a fiber for immediate execution.
    *
    * This adds a fiber to the ready queue to be executed on the next tick().
    *
    * @param fiberAction
    *   the fiber execution action
    */
  private[test] def enqueueFiber(fiberAction: () => Unit): Unit = {
    readyFibers.offer(fiberAction)
  }

  /** Suspends a fiber with a unique identifier.
    *
    * The fiber will remain suspended until explicitly resumed via resumeFiber().
    *
    * @param fiberId
    *   unique identifier for the suspended fiber
    * @param resumeAction
    *   the action to execute when the fiber is resumed
    */
  private[test] def suspendFiber(fiberId: String, resumeAction: () => Unit): Unit = {
    suspendedFibers.put(fiberId, resumeAction)
  }

  /** Resumes a suspended fiber by its identifier.
    *
    * If the fiber exists, it's moved from suspended to ready state.
    *
    * @param fiberId
    *   the identifier of the fiber to resume
    * @return
    *   true if the fiber was found and resumed, false otherwise
    */
  private[test] def resumeFiber(fiberId: String): Boolean = {
    Option(suspendedFibers.remove(fiberId)) match {
      case Some(resumeAction) =>
        readyFibers.offer(resumeAction)
        true
      case None =>
        false
    }
  }

  /** Gets the count of suspended fibers.
    *
    * @return
    *   number of currently suspended fibers
    */
  private[test] def suspendedCount: Int = suspendedFibers.size()

  /** Blocks until TestClock time reaches or exceeds the target time.
    *
    * This provides a synchronization mechanism for TestClockBackend sleep operations to wait for
    * logical time advancement without using wall-clock time.
    *
    * @param targetTime
    *   the logical time to wait for
    */
  private[test] def waitUntil(targetTime: Instant): Unit = {
    val latch = new java.util.concurrent.CountDownLatch(1)

    if (!currentTime.isBefore(targetTime)) {
      ()
    } else {

      schedule(targetTime, () => latch.countDown())
      try {
        latch.await()
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
      }
    }
  }
}
