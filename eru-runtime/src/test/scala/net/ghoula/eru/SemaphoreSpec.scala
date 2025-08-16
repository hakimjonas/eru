package net.ghoula.eru

import munit.FunSuite

final class SemaphoreSpec extends FunSuite {

  test("make initializes permitsAvailable") {
    val s = Semaphore.make(3).unsafeRunSync()
    val n = s.permitsAvailable.unsafeRunSync()
    assertEquals(n, 3L)
  }

  test("tryAcquire decrements and release increments") {
    val s = Semaphore.make(1).unsafeRunSync()
    val acquired = s.tryAcquire.unsafeRunSync()
    assertEquals(acquired, true)
    val afterAcquire = s.permitsAvailable.unsafeRunSync()
    assertEquals(afterAcquire, 0L)
    s.release.unsafeRunSync()
    val afterRelease = s.permitsAvailable.unsafeRunSync()
    assertEquals(afterRelease, 1L)
  }

  test("tryAcquireN respects availability") {
    val s = Semaphore.make(3).unsafeRunSync()
    val ok = s.tryAcquireN(2).unsafeRunSync()
    assertEquals(ok, true)
    val left = s.permitsAvailable.unsafeRunSync()
    assertEquals(left, 1L)
    val notOk = s.tryAcquireN(2).unsafeRunSync()
    assertEquals(notOk, false)
    val unchanged = s.permitsAvailable.unsafeRunSync()
    assertEquals(unchanged, 1L)
  }

  test("withPermit acquires, runs, and releases on success") {
    val s = Semaphore.make(1).unsafeRunSync()
    val out = s.withPermit(Eru.succeed(42)).unsafeRunSync()
    assertEquals(out, Some(42))
    val permits = s.permitsAvailable.unsafeRunSync()
    assertEquals(permits, 1L)
  }

  test("withPermit returns None if cannot acquire and does not change permits") {
    val s = Semaphore.make(1).unsafeRunSync()
    val first = s.tryAcquire.unsafeRunSync()
    assertEquals(first, true)
    val res = s.withPermit(Eru.succeed(1)).unsafeRunSync()
    assertEquals(res, None)
    val permits = s.permitsAvailable.unsafeRunSync()
    assertEquals(permits, 0L)
    s.release.unsafeRunSync()
  }

  test("withPermits releases permits on failure") {
    val s = Semaphore.make(2).unsafeRunSync()
    intercept[EruException[String]] {
      s.withPermits(2)(Eru.fail("boom")).unsafeRunSync()
    }
    val permits = s.permitsAvailable.unsafeRunSync()
    assertEquals(permits, 2L)
  }
}
