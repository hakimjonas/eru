package net.ghoula.eru

import net.ghoula.eru.internal.HashedTimerWheel
import net.ghoula.eru.prelude.*

/** Correctness invariant: each EruRuntime's scheduling substrate is addressable only via that
  * runtime's fork / race / handleSuspend entry points, and bare `Eru.at(...).unsafeRunSync()` on an
  * unrelated thread falls back to `EruRuntime.shared`'s wheel — not to whichever runtime was
  * constructed most recently.
  *
  * Pins Bug 2 from the Phase 3.3 R1 correctness audit. Pre-fix, `TimerService.current` was a
  * process-global `AtomicReference` and every `RuntimeBackendAdapter` constructor overwrote it, so
  * assertions 1 and 2 below failed (whichever of r1 and r2 was constructed second stole the global
  * pointer). Post-fix, the thread-local push at fork / race entry points guarantees each forked
  * fiber sees its own runtime's wheel; the write-once default-provider preserves the bare
  * `Eru.at.unsafeRunSync()` behavior for single-runtime apps.
  *
  * This test was RED on the Phase 3.3 R1 baseline at assertions 1 and 2 (assertion 3 is new and has
  * no HEAD analogue — it's the post-fix anchor invariant). All three assertions must pass after the
  * substrate fix.
  */
final class RuntimeTimerIsolationSpec extends munit.FunSuite {

  private def wheelOf(runtime: EruRuntime): HashedTimerWheel =
    runtime.timerForTests match {
      case Some(w: HashedTimerWheel) => w
      case other => fail(s"Expected HashedTimerWheel from runtime.timerForTests, got: $other")
    }

  test("fork on r1 routes Eru.at through r1's wheel (not r2's wheel, regardless of construction order)") {
    val r1 = EruRuntime.create()
    val r2 = EruRuntime.create()
    try {
      val w1 = wheelOf(r1)
      val w2 = wheelOf(r2)

      val before1 = w1.scheduleCountForTests
      val before2 = w2.scheduleCountForTests

      val farFuture = System.currentTimeMillis() + 3_600_000L
      val fiber = r1.fork(Eru.at(farFuture)(Eru.unit)).unsafeRunSync()
      fiber.await.unsafeRunSync()

      val after1 = w1.scheduleCountForTests
      val after2 = w2.scheduleCountForTests

      assertEquals(
        after1 - before1,
        1L,
        s"r1's wheel must receive the schedule call from r1.fork(Eru.at(...)) (before=$before1 after=$after1)"
      )
      assertEquals(
        after2 - before2,
        0L,
        s"r2's wheel MUST NOT receive a schedule call made via r1.fork (before=$before2 after=$after2)"
      )
    } finally {
      r1.cleanup()
      r2.cleanup()
    }
  }

  test("fork on r2 routes Eru.at through r2's wheel (symmetric, proves bidirectional isolation)") {
    val r1 = EruRuntime.create()
    val r2 = EruRuntime.create()
    try {
      val w1 = wheelOf(r1)
      val w2 = wheelOf(r2)

      val before1 = w1.scheduleCountForTests
      val before2 = w2.scheduleCountForTests

      val farFuture = System.currentTimeMillis() + 3_600_000L
      val fiber = r2.fork(Eru.at(farFuture)(Eru.unit)).unsafeRunSync()
      fiber.await.unsafeRunSync()

      val after1 = w1.scheduleCountForTests
      val after2 = w2.scheduleCountForTests

      assertEquals(
        after2 - before2,
        1L,
        s"r2's wheel must receive the schedule call from r2.fork(Eru.at(...)) (before=$before2 after=$after2)"
      )
      assertEquals(
        after1 - before1,
        0L,
        s"r1's wheel MUST NOT receive a schedule call made via r2.fork (before=$before1 after=$after1)"
      )
    } finally {
      r1.cleanup()
      r2.cleanup()
    }
  }

  test("bare Eru.at(...).unsafeRunSync() with no runtime plumbing falls back to EruRuntime.shared's wheel") {
    val sharedWheel = wheelOf(EruRuntime.shared)
    val isolated = EruRuntime.create()
    try {
      val isolatedWheel = wheelOf(isolated)

      val sharedBefore = sharedWheel.scheduleCountForTests
      val isolatedBefore = isolatedWheel.scheduleCountForTests

      val farFuture = System.currentTimeMillis() + 3_600_000L
      Eru.at(farFuture)(Eru.unit).unsafeRunSync()

      val sharedAfter = sharedWheel.scheduleCountForTests
      val isolatedAfter = isolatedWheel.scheduleCountForTests

      assertEquals(
        sharedAfter - sharedBefore,
        1L,
        s"EruRuntime.shared's wheel must receive the schedule call from bare Eru.at.unsafeRunSync (before=$sharedBefore after=$sharedAfter)"
      )
      assertEquals(
        isolatedAfter - isolatedBefore,
        0L,
        s"Isolated runtime's wheel MUST NOT receive the schedule call — EruRuntime.create() must not clobber the default-provider (before=$isolatedBefore after=$isolatedAfter)"
      )
    } finally {
      isolated.cleanup()
    }
  }
}
