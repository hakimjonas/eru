package net.ghoula.eru.test

import java.time.{Duration, Instant}

import net.ghoula.eru.*

/** Enhanced observer that integrates TestClock events with Eru observability.
  *
  * TestClockObserver provides complete traceability for time-dependent operations in test scenarios
  * by combining EruObserver events with TestClock timing events. This enables comprehensive
  * debugging and validation of timing-dependent effects.
  *
  * Key features:
  *   - **Timing Events**: Records when operations are scheduled and completed via TestClock
  *   - **Effect Integration**: Correlates timing events with effect lifecycle events
  *   - **Deterministic Tracing**: Provides precise timing information for test analysis
  *   - **Resource Tracking**: Monitors resource usage and cleanup in timed operations
  *
  * @param underlying
  *   the underlying EruObserver to delegate standard events to
  * @param testClock
  *   the TestClock to monitor for timing events
  * @param eventHandler
  *   optional handler for TestClock-specific events
  *
  * @example
  *   {{{
  * val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
  * val observer = TestClockObserver.recording(events, clock)
  *
  * given runtime: EruRuntime = EruRuntime.withBackend(TestClockBackend(clock))
  *
  * val effect = runtime.sleep(Duration.ofSeconds(5)).map(_ => "done")
  * effect.forkWithObserver(observer).unsafeRunSync()
  *
  * // Advance time and analyze timing events
  * clock.advance(Duration.ofSeconds(5))
  *
  * // Events include both standard Eru events and timing information
  * assert(events.nonEmpty)
  *   }}}
  */
final class TestClockObserver(
  underlying: EruObserver,
  testClock: TestClock,
  eventHandler: Option[TestClockObserver.Event => Unit] = None
) extends EruObserver {

  import TestClockObserver.Event

  /** Current logical time when events are processed.
    *
    * This allows correlation of effect events with precise logical timing information from the
    * TestClock.
    */
  private def currentLogicalTime: Instant = testClock.currentTime

  /** Delegates standard Eru events to the underlying observer while adding timing context. */
  def onEvent(event: EruObserver.EruEvent): Unit = {
    // Always pass through the original event
    underlying.onEvent(event)

    // Emit timing-enhanced versions if eventHandler is provided
    eventHandler.foreach { handler =>
      event match {
        case fibStart: EruObserver.EruEvent.FiberStarted =>
          handler(Event.FiberStartedWithTime(fibStart.fiberId, currentLogicalTime))

        case fibComplete: EruObserver.EruEvent.FiberCompleted =>
          handler(Event.FiberCompletedWithTime(fibComplete.fiberId, fibComplete.exit, currentLogicalTime))

        case step: EruObserver.EruEvent.Step =>
          handler(Event.StepWithTime(step.label, currentLogicalTime, Some(step.scopeId.toString)))

        case _: EruObserver.EruEvent.ProgramStart =>
          handler(Event.ProgramStartWithTime(currentLogicalTime))

        case progEnd: EruObserver.EruEvent.ProgramEnd =>
          handler(Event.ProgramEndWithTime(progEnd, currentLogicalTime))

        case _ =>
        // Other events don't have timing-enhanced versions
      }
    }
  }

  /** Notifies about a time-dependent operation being scheduled.
    *
    * Called internally when sleep, timeout, or retry operations are scheduled via TestClock.
    * Provides visibility into pending timing operations.
    */
  private[test] def onTimeOperationScheduled(
    operationType: String,
    scheduledTime: Instant,
    duration: Duration,
    context: Option[String] = None
  ): Unit = {
    eventHandler.foreach { handler =>
      handler(
        Event.TimeOperationScheduled(
          operationType = operationType,
          currentTime = currentLogicalTime,
          scheduledTime = scheduledTime,
          duration = duration,
          context = context
        )
      )
    }
  }

  /** Notifies about a time-dependent operation completing.
    *
    * Called internally when TestClock advances past scheduled operation times. Provides
    * confirmation that timing operations completed as expected.
    */
  private[test] def onTimeOperationCompleted(
    operationType: String,
    scheduledTime: Instant,
    completedTime: Instant,
    context: Option[String] = None
  ): Unit = {
    eventHandler.foreach { handler =>
      handler(
        Event.TimeOperationCompleted(
          operationType = operationType,
          scheduledTime = scheduledTime,
          completedTime = completedTime,
          actualDuration = Duration.between(scheduledTime, completedTime),
          context = context
        )
      )
    }
  }

  /** Notifies about TestClock time advancement.
    *
    * Called when TestClock.setTime() or TestClock.advance() is invoked, providing visibility into
    * test-driven time progression.
    */
  private[test] def onTimeAdvanced(
    fromTime: Instant,
    toTime: Instant,
    operationsCompleted: Int
  ): Unit = {
    eventHandler.foreach { handler =>
      handler(
        Event.TimeAdvanced(
          fromTime = fromTime,
          toTime = toTime,
          duration = Duration.between(fromTime, toTime),
          operationsCompleted = operationsCompleted
        )
      )
    }
  }
}

object TestClockObserver {

  /** Enhanced events that include TestClock timing information and compose with EruObserver events.
    */
  enum Event {

    /** Wrapper for enhanced Eru events with timing context. */
    case EnhancedEruEvent(event: EruObserver.EruEvent, logicalTime: Instant)

    /** Fiber started with logical time context. */
    case FiberStartedWithTime(fiberId: FiberId, logicalTime: Instant)

    /** Fiber completed with logical time context. */
    case FiberCompletedWithTime(fiberId: FiberId, exit: Exit[Any, Any], logicalTime: Instant)

    /** Debug step with logical time context. */
    case StepWithTime(label: String, logicalTime: Instant, scope: Option[String])

    /** Program start with logical time context. */
    case ProgramStartWithTime(logicalTime: Instant)

    /** Program end with logical time context. */
    case ProgramEndWithTime(originalEvent: EruObserver.EruEvent.ProgramEnd, logicalTime: Instant)

    /** Time-dependent operation scheduled via TestClock. */
    case TimeOperationScheduled(
      operationType: String,
      currentTime: Instant,
      scheduledTime: Instant,
      duration: Duration,
      context: Option[String]
    )

    /** Time-dependent operation completed via TestClock advancement. */
    case TimeOperationCompleted(
      operationType: String,
      scheduledTime: Instant,
      completedTime: Instant,
      actualDuration: Duration,
      context: Option[String]
    )

    /** TestClock time advanced, potentially completing operations. */
    case TimeAdvanced(
      fromTime: Instant,
      toTime: Instant,
      duration: Duration,
      operationsCompleted: Int
    )
  }

  /** Creates a TestClockObserver that delegates to the provided observer.
    *
    * @param underlying
    *   the EruObserver to delegate events to
    * @param testClock
    *   the TestClock to monitor for timing events
    * @return
    *   a new TestClockObserver instance
    */
  def apply(underlying: EruObserver, testClock: TestClock): TestClockObserver =
    new TestClockObserver(underlying, testClock, None)

  /** Creates a TestClockObserver that records events to a mutable collection.
    *
    * Convenient for testing scenarios where you want to capture and analyze all timing and effect
    * events.
    *
    * @param events
    *   mutable collection to record events to
    * @param testClock
    *   the TestClock to monitor
    * @return
    *   a new recording TestClockObserver
    */
  def recording(
    events: scala.collection.mutable.Buffer[EruObserver.EruEvent],
    testClock: TestClock
  ): TestClockObserver = {
    val recordingObserver = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = events += event
    }
    new TestClockObserver(recordingObserver, testClock, None)
  }

  /** Creates a TestClockObserver with console logging.
    *
    * Useful for debugging test scenarios by printing timing and effect events to the console with
    * logical timestamps.
    *
    * @param testClock
    *   the TestClock to monitor
    * @return
    *   a new console-logging TestClockObserver
    */
  def console(testClock: TestClock): TestClockObserver = {
    val consoleObserver = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = {
        val timestamp = testClock.currentTime
        println(s"[$timestamp] $event")
      }
    }
    new TestClockObserver(consoleObserver, testClock, None)
  }

  /** Creates a no-op TestClockObserver that ignores all events.
    *
    * @param testClock
    *   the TestClock to monitor (events will be ignored)
    * @return
    *   a new no-op TestClockObserver
    */
  def noop(testClock: TestClock): TestClockObserver = {
    val noopObserver = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = ()
    }
    new TestClockObserver(noopObserver, testClock, None)
  }
}
