package net.ghoula.eru.test

import munit.FunSuite
import java.time.Duration
import net.ghoula.eru.*

/** Test suite for TestClockObserver integration and timing event correlation. */
final class TestClockObserverSpec extends FunSuite {

  test("TestClockObserver records enhanced fiber events with timing") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val effect = Eru.succeed(42)
      effect.runWith(observer)
      
      // Should have both standard and enhanced events
      assert(events.exists(_.isInstanceOf[EruObserver.EruEvent.FiberStarted]))
      assert(events.exists(_.isInstanceOf[TestClockObserver.Event.FiberStartedWithTime]))
      assert(events.exists(_.isInstanceOf[EruObserver.EruEvent.FiberCompleted]))
      assert(events.exists(_.isInstanceOf[TestClockObserver.Event.FiberCompletedWithTime]))
    }
  }

  test("TestClockObserver adds timing context to debug steps") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val effect = Eru.succeed(1)
        .debug("step1")
        .map(_ + 1)
        .debug("step2")
      
      effect.runWith(observer)
      
      // Should have enhanced step events with timing
      val timedSteps = events.collect { case step: TestClockObserver.Event.StepWithTime => step }
      assertEquals(timedSteps.length, 2)
      assert(timedSteps.exists(_.label == "step1"))
      assert(timedSteps.exists(_.label == "step2"))
    }
  }

  test("TestClockObserver records program lifecycle with timing") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val effect = Eru.succeed("test")
      effect.runWith(observer)
      
      // Should have enhanced program lifecycle events
      assert(events.exists(_.isInstanceOf[TestClockObserver.Event.ProgramStartWithTime]))
      assert(events.exists(_.isInstanceOf[TestClockObserver.Event.ProgramEndWithTime]))
    }
  }

  test("TestClockObserver console output works") {
    EruTest.withTestClock { clock =>
      val observer = TestClockObserver.console(clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      // This test mainly ensures console observer doesn't crash
      val effect = Eru.succeed(42).debug("console test")
      val result = effect.runWith(observer)
      assertEquals(result, 42)
    }
  }

  test("TestClockObserver noop observer ignores events") {
    EruTest.withTestClock { clock =>
      val observer = TestClockObserver.noop(clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      // Should work without issues despite ignoring events
      val effect = Eru.succeed(42).debug("noop test")
      val result = effect.runWith(observer)
      assertEquals(result, 42)
    }
  }

  test("TestClockObserver timing context reflects TestClock state") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val startTime = clock.currentTime
      
      // Advance time before running effect
      clock.advance(Duration.ofMinutes(5))
      
      val effect = Eru.succeed(42)
      effect.runWith(observer)
      
      // Enhanced events should reflect advanced logical time
      val timedEvents = events.collect { case evt: TestClockObserver.Event.FiberStartedWithTime => evt }
      assertEquals(timedEvents.length, 1)
      assertEquals(timedEvents.head.logicalTime, startTime.plus(Duration.ofMinutes(5)))
    }
  }

  test("TestClockObserver preserves original event types") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val effect = Eru.fail("test error")
      effect.attempt.runWith(observer)
      
      // Should still have original fiber completion with failure
      val completionEvents = events.collect { case evt: EruObserver.EruEvent.FiberCompleted => evt }
      assertEquals(completionEvents.length, 1)
      
      completionEvents.head.exit match {
        case Exit.Failure("test error") => // Expected
        case other => fail(s"Expected failure with 'test error', got: $other")
      }
    }
  }

  test("TestClockObserver works with complex effect compositions") {
    EruTest.withTestClock { clock =>
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = TestClockObserver.recording(events, clock)
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val effect = for {
        _ <- Eru.succeed(1).debug("start")
        _ <- runtime.sleep(Duration.ofSeconds(1))
        result <- Eru.succeed(2).debug("end")
      } yield result
      
      val fiber = effect.forkWithObserver(observer).unsafeRunSync()
      clock.advance(Duration.ofSeconds(1))
      
      assertEquals(fiber.await.runExit(), Exit.Success(2))
      
      // Should have events from both main and forked execution
      assert(events.nonEmpty)
      assert(events.exists(_.isInstanceOf[TestClockObserver.Event.StepWithTime]))
    }
  }
}