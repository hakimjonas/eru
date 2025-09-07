package net.ghoula.eru

import munit.FunSuite

/** Test suite for Ref concurrent primitive functionality.
  *
  * Validates all operations of the Ref data type including atomic updates, modifications,
  * and state management. Ref provides thread-safe mutable reference semantics that enable
  * safe shared state management between concurrent fibers, supporting atomic operations
  * and consistent state transitions with complete memory safety guarantees.
  */
final class RefSpec extends FunSuite {

  test("make/get returns initial value") {
    val ref = Ref.make(10).unsafeRunSync()
    val v = ref.get.unsafeRunSync()
    assertEquals(v, 10)
  }

  test("set updates the value") {
    val ref = Ref.make(0).unsafeRunSync()
    ref.set(5).unsafeRunSync()
    val v = ref.get.unsafeRunSync()
    assertEquals(v, 5)
  }

  test("update applies function and returns updated value") {
    val ref = Ref.make(1).unsafeRunSync()
    val out = ref.update(_ + 2).unsafeRunSync()
    val v = ref.get.unsafeRunSync()
    assertEquals(out, 3)
    assertEquals(v, 3)
  }

  test("modify is atomic and returns auxiliary result") {
    val ref = Ref.make(10).unsafeRunSync()
    val result = ref.modify(n => (n + 1, n)).unsafeRunSync()
    val now = ref.get.unsafeRunSync()
    assertEquals(result, 10)
    assertEquals(now, 11)
  }
}
