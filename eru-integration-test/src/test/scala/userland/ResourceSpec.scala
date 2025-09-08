package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Integration test suite for resource management in complex real-world scenarios.
  *
  * Validates resource safety operations including bracket, ensure, and finalizers in realistic
  * usage patterns that combine resource management with concurrency, error handling, and other
  * effect operations. These tests ensure that resource safety guarantees hold under complex
  * compositions that reflect production application requirements.
  */
final class ResourceSpec extends FunSuite {
  test("bracket ensures cleanup on failure and success") {
    var cleaned = 0
    val acquire = Eru.succeed("res")
    val release = (_: String) => Eru.effect { cleaned += 1; () }

    val failUse = (_: String) => Eru.fail("boom")
    val prog1 = acquire.bracket(release)(failUse)
    val exit1 = prog1.runExit()
    exit1 match {
      case _: Exit.Failure[?, ?] => ()
      case other => fail(s"expected failure, got $other")
    }
    assertEquals(cleaned, 1)

    val okUse = (_: String) => Eru.succeed(42)
    val prog2 = acquire.bracket(release)(okUse)
    val exit2 = prog2.runExit()
    assertEquals(exit2, Exit.Success(42))
    assertEquals(cleaned, 2)
  }

  test("ensure always runs cleanup") {
    var ran = false
    val prog = Eru.fail("x").ensure(Eru.effect { ran = true; () })
    val _ = prog.runExit()
    assert(ran)
  }
}
