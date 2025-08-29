package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class ObservabilitySpec extends FunSuite {
  test("observer sees ProgramStart, Step, and ProgramEnd(Success)") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    val value = Eru.succeed(123).debug("step-1").unsafeRunSyncWith(obs)

    assertEquals(value, 123)
    val xs = events.toList
    xs match {
      case EruEvent
            .ProgramStart(scopeId) :: EruEvent.Step(scopeId2, label) :: EruEvent.ProgramEnd(scopeId3, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        assertEquals(scopeId3, scopeId)
        assertEquals(label, "step-1")
        assertEquals(outcome, EruObserver.Outcome.Success)
      case other => fail(s"unexpected events: $other")
    }
  }

  test("observer sees ProgramStart and ProgramEnd(TypedFailure)") {
    val events = scala.collection.mutable.ListBuffer.empty[EruEvent]
    val obs = new EruObserver { def onEvent(e: EruEvent): Unit = events += e }

    intercept[EruException[String]] {
      Eru.fail("boom").unsafeRunSyncWith(obs)
    }

    val xs = events.toList
    xs match {
      case EruEvent.ProgramStart(scopeId) :: EruEvent.ProgramEnd(scopeId2, outcome) :: Nil =>
        assertEquals(scopeId2, scopeId)
        outcome match {
          case EruObserver.Outcome.TypedFailure(err) => assertEquals(err, "boom")
          case other => fail(s"unexpected outcome: $other")
        }
      case other => fail(s"unexpected events: $other")
    }
  }
}
