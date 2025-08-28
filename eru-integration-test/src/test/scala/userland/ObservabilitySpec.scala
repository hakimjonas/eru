package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class ObservabilitySpec extends FunSuite {
  test("runWith observer executes and emits events") {
    val events = scala.collection.mutable.ArrayBuffer.empty[EruObserver.EruEvent]
    val observer = new EruObserver {
      def onEvent(e: EruObserver.EruEvent): Unit = events += e
    }

    val program = Eru.succeed(42).debug("observed")
    val out = program.runWith(observer)

    assertEquals(out, 42)
    assert(events.nonEmpty)
  }
}
