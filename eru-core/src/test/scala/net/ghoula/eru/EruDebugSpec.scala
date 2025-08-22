package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

/** Comprehensive testing specification for Eru's debug functionality and observability features.
  *
  * This specification ensures that the debug method correctly integrates with the observer system
  * to provide runtime visibility into effect execution while maintaining correctness and
  * performance characteristics of the underlying computations.
  */
class EruDebugSpec extends FunSuite {

  /** Test observer that captures emitted events for verification. */
  class TestObserver extends EruObserver {
    private var events: List[EruEvent] = Nil

    def onEvent(event: EruEvent): Unit = {
      events = event :: events
    }

    def getEvents: List[EruEvent] = events.reverse

    def reset(): Unit = {
      events = Nil
    }
  }

  test("debug method does not affect computation result for success") {
    val originalEru = Eru.succeed(42)
    val debuggedEru = originalEru.debug("test debug")

    assertEquals(originalEru.unsafeRunSync(), debuggedEru.unsafeRunSync())
  }

  test("debug method does not affect computation result for failure") {
    val originalEru = Eru.fail("test error")
    val debuggedEru = originalEru.debug("test debug")

    interceptMessage[EruException[String]]("test error") {
      originalEru.unsafeRunSync()
    }

    interceptMessage[EruException[String]]("test error") {
      debuggedEru.unsafeRunSync()
    }
  }

  test("debug method does not affect computation result for effects") {
    val originalEru = Eru.effect(100)
    val debuggedEru = originalEru.debug("effect debug")

    assertEquals(originalEru.unsafeRunSync(), debuggedEru.unsafeRunSync())
  }

  test("debug emits Step event with correct label when executed with observer") {
    val observer = new TestObserver()
    val debugLabel = "computation step"

    Eru.succeed(42).debug(debugLabel).unsafeRunSyncWith(observer)

    val events = observer.getEvents
    val stepEvents = events.collect { case step: EruEvent.Step => step }

    assert(stepEvents.nonEmpty, "Should emit at least one Step event")
    val stepEvent = stepEvents.find(_.label == debugLabel)
    assert(stepEvent.isDefined, s"Should emit Step event with label '$debugLabel'")
  }

  test("debug label is evaluated lazily") {
    var labelEvaluated = false

    val debuggedEru = Eru.succeed(42).debug {
      labelEvaluated = true
      "lazy label"
    }

    assert(!labelEvaluated, "Label should not be evaluated when creating debug effect")

    debuggedEru.unsafeRunSync()
    assert(!labelEvaluated, "Label should not be evaluated during unsafeRunSync without observer")
  }

  test("debug label is evaluated only when observer is present") {
    var labelEvaluationCount = 0
    val observer = new TestObserver()

    val debuggedEru = Eru.succeed(42).debug {
      labelEvaluationCount += 1
      "evaluated label"
    }

    debuggedEru.unsafeRunSyncWith(observer)

    assertEquals(labelEvaluationCount, 1, "Label should be evaluated exactly once with observer")
  }

  test("multiple debug labels in chain emit multiple Step events") {
    val observer = new TestObserver()

    Eru
      .succeed(10)
      .debug("step 1")
      .map(_ * 2)
      .debug("step 2")
      .flatMap(x => Eru.succeed(x + 1))
      .debug("step 3")
      .unsafeRunSyncWith(observer)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    val stepLabels = stepEvents.map(_.label)

    assert(stepLabels.contains("step 1"), "Should contain first debug label")
    assert(stepLabels.contains("step 2"), "Should contain second debug label")
    assert(stepLabels.contains("step 3"), "Should contain third debug label")
    assertEquals(stepLabels.count(_ == "step 1"), 1, "Each label should appear exactly once")
    assertEquals(stepLabels.count(_ == "step 2"), 1, "Each label should appear exactly once")
    assertEquals(stepLabels.count(_ == "step 3"), 1, "Each label should appear exactly once")
  }

  test("debug works correctly with error handling chains") {
    val observer = new TestObserver()

    val result = Eru
      .fail("initial error")
      .debug("before recover")
      .recover { case "initial error" => 42 }
      .debug("after recover")
      .unsafeRunSyncWith(observer)

    assertEquals(result, 42)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    val stepLabels = stepEvents.map(_.label)

    assert(stepLabels.contains("before recover"), "Should emit debug before recover")
    assert(stepLabels.contains("after recover"), "Should emit debug after recover")
  }

  test("debug integrates correctly with resource management") {
    val observer = new TestObserver()
    var resourceClosed = false

    val resource = "test resource"
    val result = Eru
      .succeed(resource)
      .debug("acquired resource")
      .bracket(_ => Eru.effect { resourceClosed = true }) { res =>
        Eru.succeed(s"used $res").debug("using resource")
      }
      .unsafeRunSyncWith(observer)

    assertEquals(result, "used test resource")
    assert(resourceClosed, "Resource should be closed")

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    val stepLabels = stepEvents.map(_.label)

    assert(stepLabels.contains("acquired resource"), "Should emit debug for resource acquisition")
    assert(stepLabels.contains("using resource"), "Should emit debug for resource usage")
  }

  test("debug works with complex effect compositions") {
    val observer = new TestObserver()

    val computation = for {
      x <- Eru.succeed(10).debug("initial value")
      y <- Eru.effect(x * 2).debug("doubled value")
      z <- Eru.succeed(y + 5).debug("final computation")
    } yield z

    val result = computation.unsafeRunSyncWith(observer)
    assertEquals(result, 25)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    assertEquals(stepEvents.length, 3, "Should emit exactly 3 debug steps")

    val expectedLabels = List("initial value", "doubled value", "final computation")
    val actualLabels = stepEvents.map(_.label)

    expectedLabels.foreach { expectedLabel =>
      assert(actualLabels.contains(expectedLabel), s"Should contain debug label: $expectedLabel")
    }
  }

  test("debug step events contain valid scope information") {
    val observer = new TestObserver()

    Eru.succeed(42).debug("scoped step").unsafeRunSyncWith(observer)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    assert(stepEvents.nonEmpty, "Should emit Step events")

    stepEvents.foreach { stepEvent =>
      assertEquals(stepEvent.label, "scoped step", "Step event should have correct label")
    }
  }

  test("debug preserves execution order in observer events") {
    val observer = new TestObserver()

    Eru
      .succeed(1)
      .debug("first")
      .flatMap(x => Eru.succeed(x + 1).debug("second"))
      .flatMap(x => Eru.succeed(x + 1).debug("third"))
      .unsafeRunSyncWith(observer)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    val stepLabels = stepEvents.map(_.label)

    // Debug events should appear in execution order
    val firstIndex = stepLabels.indexOf("first")
    val secondIndex = stepLabels.indexOf("second")
    val thirdIndex = stepLabels.indexOf("third")

    assert(firstIndex >= 0 && secondIndex >= 0 && thirdIndex >= 0, "All debug labels should be present")
    assert(firstIndex < secondIndex, "First debug should come before second")
    assert(secondIndex < thirdIndex, "Second debug should come before third")
  }
}
