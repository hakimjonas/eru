package net.ghoula.eru

import munit.FunSuite

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
