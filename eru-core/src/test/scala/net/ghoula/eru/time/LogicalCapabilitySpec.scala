package net.ghoula.eru.time

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.Eru

/** `Logical` capability contract: one test-time given satisfies both `Monotonic` and `Wall`,
  * `advance` moves both in lockstep, `warp` moves wall alone, `setWall` jumps wall.
  *
  * The reference implementation `InMemoryLogical` exposes the laws cleanly: monotonic reads DO NOT
  * auto-advance (that's JvmMonotonic's concern, not Logical's — test time is caller- advanced).
  * Strict monotonicity of the `Monotonic` view under `Logical` is delivered by `advance`, not by
  * `monotonicNow` bumping on each read.
  */
class LogicalCapabilitySpec extends munit.FunSuite {

  private final class InMemoryLogical(startMonoNanos: Long, startWall: Instant) extends Logical {
    private val monoNanos = new AtomicLong(startMonoNanos)
    @volatile private var wall: Instant = startWall

    def monotonicNow: Eru[Nothing, MonotonicInstant] =
      Eru.effectTotal(MonotonicInstant.ofNanos(monoNanos.get()))

    def wallNow: Eru[Nothing, Instant] =
      Eru.effectTotal(wall)

    def sleep(duration: Duration): Eru[Nothing, Unit] =
      advance(duration)

    def at[E, A](instant: Instant)(effect: => Eru[E, A]): Eru[Nothing, Unit] =
      Eru.effectTotal { wall = instant; val _ = effect; () }

    def advance(duration: Duration): Eru[Nothing, Unit] =
      Eru.effectTotal {
        val _ = monoNanos.addAndGet(duration.toNanos)
        wall = wall.plus(duration)
      }

    def setWall(instant: Instant): Eru[Nothing, Unit] =
      Eru.effectTotal { wall = instant }

    def warp(offset: Duration): Eru[Nothing, Unit] =
      Eru.effectTotal { wall = wall.plus(offset) }
  }

  private def fresh: InMemoryLogical =
    new InMemoryLogical(startMonoNanos = 0L, startWall = Instant.parse("2026-01-01T00:00:00Z"))

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

  test("Logical satisfies Monotonic via subtyping") {
    val l: Logical = fresh
    val m: Monotonic = l
    val _ = m.monotonicNow.unsafeRunSync()
    ()
  }

  test("Logical satisfies Wall via subtyping") {
    val l: Logical = fresh
    val w: Wall = l
    val _ = w.wallNow.unsafeRunSync()
    ()
  }
}
