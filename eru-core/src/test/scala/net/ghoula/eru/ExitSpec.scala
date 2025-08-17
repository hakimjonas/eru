package net.ghoula.eru

import munit.FunSuite

class ExitSpec extends FunSuite {

  test("Exit.Success holds the value") {
    val ex: Exit[Nothing, Int] = Exit.Success(42)
    ex match {
      case Exit.Success(v) => assertEquals(v, 42)
      case _ => fail("expected Success")
    }
  }

  test("Exit.Failure holds the error") {
    val ex: Exit[String, Nothing] = Exit.Failure("boom")
    ex match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case _ => fail("expected Failure")
    }
  }

  test("Exit.Die holds the throwable") {
    val t = new RuntimeException("x")
    val ex: Exit[Nothing, Nothing] = Exit.Die(t)
    ex match {
      case Exit.Die(tt) => assertEquals(tt, t)
      case _ => fail("expected Die")
    }
  }

  test("Exit.Interrupt holds fiber id and cause") {
    val fid = FiberId.fresh()
    val ex: Exit[Nothing, Nothing] = Exit.Interrupt(fid, InterruptCause.Cancelled())
    ex match {
      case Exit.Interrupt(id, cause) =>
        assertEquals(id, fid)
        assertEquals(cause, InterruptCause.Cancelled())
      case _ => fail("expected Interrupt")
    }
  }
}
