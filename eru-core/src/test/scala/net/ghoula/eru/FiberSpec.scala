package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

class FiberSpec extends FunSuite {

  private final class TestFiber[E, A](val id: FiberId, exit: Exit[E, A]) extends Fiber[E, A] {
    var interrupted: Option[InterruptCause] = None
    def await: Eru[Nothing, Exit[E, A]] = Eru.succeed(exit)
    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
      interrupted = Some(cause)
      Eru.unit
    }
  }

  test("await returns Exit.Success for successful fiber") {
    val fid = FiberId.fresh()
    val fib = new TestFiber[Nothing, Int](fid, Exit.Success(7))
    val out = fib.await.unsafeRunSync()
    out match {
      case Exit.Success(v) => assertEquals(v, 7)
      case other => fail(s"expected Success, got $other")
    }
    assertEquals(fib.id, fid)
  }

  test("await returns Exit.Failure with typed error") {
    val fib = new TestFiber[String, Nothing](FiberId.fresh(), Exit.Failure("boom"))
    fib.await.unsafeRunSync() match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case other => fail(s"expected Failure, got $other")
    }
  }

  test("interrupt records cause and returns unit") {
    val fib = new TestFiber[Nothing, Int](FiberId.fresh(), Exit.Success(1))
    fib.interrupt(InterruptCause.Cancelled()).unsafeRunSync()
    assertEquals(fib.interrupted, Some(InterruptCause.Cancelled()))
  }
}
