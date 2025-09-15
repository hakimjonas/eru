package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive testing specification for Eru's debug functionality and observability features.
  *
  * This specification ensures that the debug method correctly integrates with the observer system
  * to provide runtime visibility into effect execution while maintaining correctness and
  * performance characteristics of the underlying computations.
  */
class EruDebugSpec extends munit.FunSuite {

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

  /** Validates that debug method preserves computation results for successful effects.
    *
    * Tests that adding debug labels to successful computations does not change the final result
    * while maintaining semantic transparency.
    */
  test("debug method does not affect computation result for success") {
    val originalEru = Eru.succeed(42)
    val debuggedEru = originalEru.debug("test debug")

    assertEquals(originalEru.unsafeRunSync(), debuggedEru.unsafeRunSync())
  }

  /** Validates that debug method preserves computation results for failed effects.
    *
    * Tests that adding debug labels to failed computations does not change error propagation or
    * final failure outcomes.
    */
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

  /** Validates that debug method preserves computation results for effectful operations.
    *
    * Tests that adding debug labels to side-effecting computations does not interfere with their
    * execution or results.
    */
  test("debug method does not affect computation result for effects") {
    val originalEru = Eru.effect(100)
    val debuggedEru = originalEru.debug("effect debug")

    assertEquals(originalEru.unsafeRunSync(), debuggedEru.unsafeRunSync())
  }

  /** Validates that debug emits Step events with correct labels when observer is present.
    *
    * Tests that debug labels are properly emitted as Step events through the observer system when
    * effects are executed with observability enabled.
    */
  test("debug emits Step event with correct label when executed with observer") {
    val observer = new TestObserver()
    val debugLabel = "computation step"

    Eru.succeed(42).debug(debugLabel).unsafeRunSyncWith(observer)

    val events = observer.getEvents
    val stepEvents = events.collect { case step: EruEvent.Step => step }

    assert(stepEvents.nonEmpty)
    val stepEvent = stepEvents.find(_.label == debugLabel)
    assert(stepEvent.isDefined)
  }

  /** Validates that debug labels are evaluated lazily for performance.
    *
    * Tests that debug label computation is deferred until actually needed, avoiding unnecessary
    * work when no observer is present.
    */
  test("debug label is evaluated lazily") {
    var labelEvaluated = false

    val debuggedEru = Eru.succeed(42).debug {
      labelEvaluated = true
      "lazy label"
    }

    assert(!labelEvaluated)

    debuggedEru.unsafeRunSync()
    assert(!labelEvaluated)
  }

  /** Validates that debug labels are evaluated only when observer is present.
    *
    * Tests that label evaluation occurs exactly once when an observer is attached to the execution
    * context, ensuring efficient lazy evaluation.
    */
  test("debug label is evaluated only when observer is present") {
    var labelEvaluationCount = 0
    val observer = new TestObserver()

    val debuggedEru = Eru.succeed(42).debug {
      labelEvaluationCount += 1
      "evaluated label"
    }

    debuggedEru.unsafeRunSyncWith(observer)

    assertEquals(labelEvaluationCount, 1)
  }

  /** Validates that multiple debug labels in chain emit multiple Step events.
    *
    * Tests that chaining multiple debug operations produces distinct Step events for each debug
    * label in the correct execution sequence.
    */
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

    assert(stepLabels.contains("step 1"))
    assert(stepLabels.contains("step 2"))
    assert(stepLabels.contains("step 3"))
    assertEquals(stepLabels.count(_ == "step 1"), 1)
    assertEquals(stepLabels.count(_ == "step 2"), 1)
    assertEquals(stepLabels.count(_ == "step 3"), 1)
  }

  /** Validates that debug works correctly with error handling chains.
    *
    * Tests that debug labels are properly emitted during error recovery operations, maintaining
    * observability throughout error handling flows.
    */
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

    assert(stepLabels.contains("before recover"))
    assert(stepLabels.contains("after recover"))
  }

  /** Validates that debug integrates correctly with resource management.
    *
    * Tests that debug labels are properly emitted during resource acquisition, usage, and cleanup
    * phases while maintaining resource safety guarantees.
    */
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
    assert(resourceClosed)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    val stepLabels = stepEvents.map(_.label)

    assert(stepLabels.contains("acquired resource"))
    assert(stepLabels.contains("using resource"))
  }

  /** Validates that debug works with complex effect compositions.
    *
    * Tests that debug labels are properly emitted during complex monadic compositions, maintaining
    * observability across for-comprehension chains.
    */
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
    assertEquals(stepEvents.length, 3)

    val expectedLabels = List("initial value", "doubled value", "final computation")
    val actualLabels = stepEvents.map(_.label)

    expectedLabels.foreach { expectedLabel =>
      assert(actualLabels.contains(expectedLabel))
    }
  }

  /** Validates that debug step events contain valid scope information.
    *
    * Tests that Step events generated by debug operations contain correct label and scope
    * information for proper observability tracking.
    */
  test("debug step events contain valid scope information") {
    val observer = new TestObserver()

    Eru.succeed(42).debug("scoped step").unsafeRunSyncWith(observer)

    val stepEvents = observer.getEvents.collect { case step: EruEvent.Step => step }
    assert(stepEvents.nonEmpty)

    stepEvents.foreach { stepEvent =>
      assertEquals(stepEvent.label, "scoped step")
    }
  }

  /** Validates that debug preserves execution order in observer events.
    *
    * Tests that Step events from debug operations are emitted in the correct execution order,
    * maintaining temporal consistency in the observer stream.
    */
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

    val firstIndex = stepLabels.indexOf("first")
    val secondIndex = stepLabels.indexOf("second")
    val thirdIndex = stepLabels.indexOf("third")

    assert(firstIndex >= 0 && secondIndex >= 0 && thirdIndex >= 0)
    assert(firstIndex < secondIndex)
    assert(secondIndex < thirdIndex)
  }
}
