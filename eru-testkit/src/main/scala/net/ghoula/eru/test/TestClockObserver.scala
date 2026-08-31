package net.ghoula.eru.test

import java.time.Instant

import net.ghoula.eru.*

/** Enhanced observer that adds TestClock timing context to Eru observability.
  *
  * TestClockObserver delegates standard Eru events to an underlying observer and, when an
  * `eventHandler` is configured, forwards timing-annotated versions of fiber, step, and program
  * events carrying the TestClock's current logical time.
  *
  * @param underlying
  *   the underlying EruObserver to delegate standard events to
  * @param testClock
  *   the TestClock whose logical time annotates events
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
  * // Advance time
  * clock.advance(Duration.ofSeconds(5))
  *
  * // events contains the standard Eru fiber events
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
    underlying.onEvent(event)

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
      }
    }
  }

}

object TestClockObserver {

  /** Enhanced events that include TestClock timing information and compose with EruObserver events.
    */
  enum Event {

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
    * Convenient for testing scenarios where you want to capture effect events.
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
