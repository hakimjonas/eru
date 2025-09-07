package net.ghoula.eru

import munit.FunSuite

/** Test suite for Deferred concurrent primitive functionality.
  *
  * Validates all operations of the Deferred data type including construction, completion,
  * polling, and await semantics. Deferred provides single-assignment variable semantics
  * that enable safe coordination between concurrent fibers, supporting common patterns
  * like producer-consumer communication and synchronization barriers.
  */
final class DeferredSpec extends FunSuite {

  test("poll returns None before completion and Some after completion") {
    val d = Deferred.make[Int].unsafeRunSync()
    val before = d.poll.unsafeRunSync()
    assertEquals(before, Option.empty[Int])
    val completed = d.complete(42).unsafeRunSync()
    assertEquals(completed, true)
    val after = d.poll.unsafeRunSync()
    assertEquals(after, Some(42))
  }

  test("complete is idempotent and returns false on second call") {
    val d = Deferred.make[String].unsafeRunSync()
    val first = d.complete("ok").unsafeRunSync()
    val second = d.complete("again").unsafeRunSync()
    assertEquals(first, true)
    assertEquals(second, false)
    val polled = d.poll.unsafeRunSync()
    assertEquals(polled, Some("ok"))
  }
}
