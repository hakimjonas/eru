package net.ghoula.eru

import munit.FunSuite

class EruResourceSpec extends FunSuite {

  test("ensure runs finalizer on success") {
    var finalized = 0
    val prog = Eru.succeed(42).ensure(Eru.effect { finalized += 1; () })
    val out = prog.unsafeRunSync()
    assertEquals(out, 42)
    assertEquals(finalized, 1)
  }

  test("ensure runs finalizer on typed failure") {
    var finalized = 0
    val prog: Eru[String, Int] = Eru.fail("boom").ensure(Eru.effect { finalized += 1; () })
    val ex = intercept[EruException[String]] { prog.unsafeRunSync() }
    assertEquals(ex.error, "boom")
    assertEquals(finalized, 1)
  }

  test("ensure runs finalizer on Throwable failure from effect and rethrows at edge") {
    var finalized = 0
    val err = new RuntimeException("x")
    val prog: Eru[Throwable, Int] = Eru.effect[Int](throw err).ensure(Eru.effect { finalized += 1 })
    intercept[RuntimeException] { prog.unsafeRunSync() }
    assertEquals(finalized, 1)
  }

  test("ensure finalizers run in FILO order when nested") {
    val order = scala.collection.mutable.ListBuffer.empty[String]
    val f1 = Eru.effect { order += "f1"; () }
    val f2 = Eru.effect { order += "f2"; () }
    val prog = Eru.succeed(1).ensure(f1).ensure(f2)
    assertEquals(prog.unsafeRunSync(), 1)
    assertEquals(order.toList, List("f2", "f1"))
  }

  test("bracket releases exactly once on success and failure") {
    var acquired = 0
    var released = 0

    val acquire: Eru[Throwable, Int] = Eru.effect { acquired += 1; 7 }
    val release: Int => Eru[Throwable, Unit] = _ => Eru.effect { released += 1; () }

    val success = acquire.bracket(release) { a => Eru.succeed(a * 2) }
    assertEquals(success.unsafeRunSync(), 14)
    assertEquals(acquired, 1)
    assertEquals(released, 1)

    val failure = acquire.bracket(release) { _ => Eru.fail("nope") }
    val ex = intercept[EruException[String]] { failure.unsafeRunSync() }
    assertEquals(ex.error, "nope")
    assertEquals(acquired, 2)
    assertEquals(released, 2)
  }

}
