package net.ghoula.eru.test

import java.time.{Duration, Instant}

import net.ghoula.eru.time.{Logical, Monotonic, Wall}

/** Production-real Logical adapter laws.
  *
  * Mirrors the M1 `LogicalCapabilitySpec`'s law assertions but against `LogicalTestClock` — the
  * production-real adapter that wraps a TestClock. The point: real TestClock-backed code honors the
  * same Logical contract as the in-memory M1 reference impl.
  */
class LogicalTestClockSpec extends munit.FunSuite {

  private def fresh: LogicalTestClock =
    LogicalTestClock.create(Instant.parse("2026-01-01T00:00:00Z"))

  test("advance(d) moves monotonic by exactly d nanoseconds") {
    val l = fresh
    val prog = for {
      t0 <- l.monotonicNow
      _ <- l.advance(Duration.ofNanos(42_000L))
      t1 <- l.monotonicNow
    } yield t0.until(t1)
    assertEquals(prog.unsafeRunSync().toNanos, 42_000L)
  }

  test("advance(d) moves wall forward by exactly d") {
    val l = fresh
    val prog = for {
      w0 <- l.wallNow
      _ <- l.advance(Duration.ofSeconds(5))
      w1 <- l.wallNow
    } yield Duration.between(w0, w1)
    assertEquals(prog.unsafeRunSync(), Duration.ofSeconds(5))
  }

  test("warp(offset) moves wall without moving monotonic") {
    val l = fresh
    val prog = for {
      t0 <- l.monotonicNow
      w0 <- l.wallNow
      _ <- l.warp(Duration.ofSeconds(10))
      t1 <- l.monotonicNow
      w1 <- l.wallNow
    } yield (t0, t1, w0, w1)
    val (t0, t1, w0, w1) = prog.unsafeRunSync()
    assertEquals(t0.toNanos, t1.toNanos, "monotonic must not move under warp")
    assertEquals(Duration.between(w0, w1), Duration.ofSeconds(10))
  }

  test("setWall jumps wall; monotonic unchanged") {
    val target = Instant.parse("2030-01-01T00:00:00Z")
    val l = fresh
    val prog = for {
      t0 <- l.monotonicNow
      _ <- l.setWall(target)
      w <- l.wallNow
      t1 <- l.monotonicNow
    } yield (t0, t1, w)
    val (t0, t1, w) = prog.unsafeRunSync()
    assertEquals(w, target)
    assertEquals(t0.toNanos, t1.toNanos)
  }

  test("LogicalTestClock as `given Logical` satisfies `using Monotonic`") {
    given Logical = fresh
    val m: Monotonic = summon[Monotonic]
    assert(Option(m).isDefined)
    val _ = m.monotonicNow.unsafeRunSync()
    ()
  }

  test("LogicalTestClock as `given Logical` satisfies `using Wall`") {
    given Logical = fresh
    val w: Wall = summon[Wall]
    assert(Option(w).isDefined)
    val _ = w.wallNow.unsafeRunSync()
    ()
  }

  test("underlying TestClock remains accessible") {
    val l = fresh
    assert(Option(l.underlying).isDefined)
    assertEquals(l.underlying.currentTime, Instant.parse("2026-01-01T00:00:00Z"))
  }
}
