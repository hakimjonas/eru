package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

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

  test("ensure suppresses finalizer typed error on success") {
    val prog = Eru.succeed(99).ensure(Eru.fail("ferr"))
    val out = prog.unsafeRunSync()
    assertEquals(out, 99)
  }

  test("ensure suppresses finalizer Throwable on success") {
    var side = 0
    val ex = new RuntimeException("fin-err")
    val fin: Eru[Throwable, Unit] = Eru.effect { side += 1; throw ex }
    val prog = Eru.succeed(7).ensure(fin)
    val out = prog.unsafeRunSync()
    assertEquals(out, 7)
    assertEquals(side, 1)
  }

  test("ensure finalizer failure does not change failure outcome") {
    val prog: Eru[String, Int] = Eru.fail("boom").ensure(Eru.fail("ferr"))
    val ex = intercept[EruException[String]] { prog.unsafeRunSync() }
    assertEquals(ex.error, "boom")
  }

  test("ensure identity with unit for success and failure") {
    val ok = Eru.succeed(1)
    val okEnsured = ok.ensure(Eru.unit)
    assertEquals(ok.attempt.unsafeRunSync(), okEnsured.attempt.unsafeRunSync())

    val bad: Eru[String, Int] = Eru.fail("x")
    val badEnsured = bad.ensure(Eru.unit)
    assertEquals(bad.attempt.unsafeRunSync(), badEnsured.attempt.unsafeRunSync())
  }

  test("nested ensures across multiple depths follow FILO ordering") {
    import scala.collection.mutable.ListBuffer
    val order = ListBuffer.empty[Int]
    val depth = 10
    val base: Eru[Nothing, Unit] = Eru.unit
    val prog = (1 to depth).foldLeft(base) { (acc, i) =>
      val fin = Eru.effect { order += i; () }
      acc.ensure(fin)
    }
    prog.unsafeRunSync()
    assertEquals(order.toList, (1 to depth).reverse.toList)
  }

}
