package net.ghoula.eru.time

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.Eru

/** `Monotonic` capability contract against a minimal in-memory implementation.
  *
  * The reference implementation is a strictly-increasing counter. It does not interact with the
  * wall clock or spawn anything; every effect is `Eru.effectTotal` over `AtomicLong` reads and
  * writes.
  */
class MonotonicCapabilitySpec extends munit.FunSuite {

  private final class CountingMonotonic extends Monotonic {
    private val nanos = new AtomicLong(0L)
    def monotonicNow: Eru[Nothing, MonotonicInstant] =
      Eru.effectTotal(MonotonicInstant.ofNanos(nanos.incrementAndGet()))
    def sleep(duration: Duration): Eru[Nothing, Unit] =
      Eru.effectTotal { val _ = nanos.addAndGet(duration.toNanos) }
  }

  test("monotonicNow yields strictly increasing readings across 5 calls") {
    val m: Monotonic = new CountingMonotonic
    val a = m.monotonicNow.unsafeRunSync()
    val b = m.monotonicNow.unsafeRunSync()
    val c = m.monotonicNow.unsafeRunSync()
    val d = m.monotonicNow.unsafeRunSync()
    val e = m.monotonicNow.unsafeRunSync()
    assert(a.toNanos < b.toNanos)
    assert(b.toNanos < c.toNanos)
    assert(c.toNanos < d.toNanos)
    assert(d.toNanos < e.toNanos)
  }

  test("sleep(d) advances clock by at least d nanoseconds") {
    val m: Monotonic = new CountingMonotonic
    val prog = for {
      t0 <- m.monotonicNow
      _ <- m.sleep(Duration.ofNanos(1_000_000L))
      t1 <- m.monotonicNow
    } yield t0.until(t1)
    val elapsed = prog.unsafeRunSync()
    assert(elapsed.toNanos >= 1_000_000L, s"elapsed=${elapsed.toNanos} < 1_000_000")
  }

  test("sleep(0) still preserves monotonicity") {
    val m: Monotonic = new CountingMonotonic
    val prog = for {
      t0 <- m.monotonicNow
      _ <- m.sleep(Duration.ZERO)
      t1 <- m.monotonicNow
    } yield (t0, t1)
    val (t0, t1) = prog.unsafeRunSync()
    assert(t0.toNanos < t1.toNanos)
  }
}
