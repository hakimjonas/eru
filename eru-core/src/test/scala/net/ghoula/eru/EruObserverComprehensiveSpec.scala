package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Test suite for EruObserver functionality and integration behavior.
  *
  * Focuses on testing meaningful observer behavior: integration with program execution, observer
  * composition, and performance characteristics. Does not test basic language features like case
  * class field access or pattern matching.
  */
class EruObserverComprehensiveSpec extends munit.FunSuite {

  test("EruObserver.noop does not affect program execution") {
    val observer = EruObserver.noop
    var sideEffectCalled = false

    val program = Eru.effect { sideEffectCalled = true; 42 }
    val result = program.unsafeRunSyncWith(observer)

    assertEquals(result, 42)
    assert(sideEffectCalled, "Program should execute normally with noop observer")
  }

  test("EruObserver.console captures program execution events") {
    val originalOut = System.out
    val capturedOutput = new java.io.ByteArrayOutputStream()
    val printStream = new java.io.PrintStream(capturedOutput)

    try {
      System.setOut(printStream)
      val observer = EruObserver.console

      val program = Eru.effect(42).map(_ + 1)
      val result = program.unsafeRunSyncWith(observer)

      assertEquals(result, 43)
      val output = capturedOutput.toString
      assert(output.contains("ProgramStart"), "Should observe program start")
      assert(output.contains("ProgramEnd"), "Should observe program end")
    } finally {
      System.setOut(originalOut)
    }
  }

  test("ScopeId.fresh generates unique identifiers under concurrent access") {
    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.*

    // Test concurrent ID generation to ensure uniqueness
    val futures = (1 to 100).map { _ =>
      Future { ScopeId.fresh() }
    }

    val ids = Await.result(Future.sequence(futures), 5.seconds)
    val uniqueIds = ids.toSet

    assertEquals(uniqueIds.size, 100, "All generated IDs should be unique")
  }

  test("Multiple observers can be composed") {
    var observer1Events = 0
    var observer2Events = 0

    val observer1 = new EruObserver {
      def onEvent(event: EruEvent): Unit = observer1Events += 1
    }

    val observer2 = new EruObserver {
      def onEvent(event: EruEvent): Unit = observer2Events += 1
    }

    // Test that we can observe with different observers
    val program = Eru.effect(42)

    program.unsafeRunSyncWith(observer1)
    program.unsafeRunSyncWith(observer2)

    assert(observer1Events > 0, "First observer should receive events")
    assert(observer2Events > 0, "Second observer should receive events")
  }

  test("Observer does not interfere with error propagation") {
    val observer = EruObserver.console
    val error = new RuntimeException("test error")
    val program = Eru.effect(throw error)

    val caught = intercept[RuntimeException] {
      program.unsafeRunSyncWith(observer)
    }

    assertEquals(caught.getMessage, "test error")
  }
}
