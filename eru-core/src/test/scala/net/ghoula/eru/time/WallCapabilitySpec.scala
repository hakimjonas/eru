package net.ghoula.eru.time

import java.time.Instant

import net.ghoula.eru.Eru

/** `Wall` capability contract against a minimal in-memory implementation. */
class WallCapabilitySpec extends munit.FunSuite {

  private final class FixedWall(initial: Instant) extends Wall {
    @volatile private var current: Instant = initial
    def wallNow: Eru[Nothing, Instant] = Eru.effectTotal(current)
    def at[E, A](instant: Instant)(effect: => Eru[E, A]): Eru[Nothing, Unit] =
      Eru.effectTotal { current = instant; val _ = effect; () }
  }

  test("wallNow returns the configured Instant") {
    val target = Instant.parse("2026-05-13T12:00:00Z")
    val w: Wall = new FixedWall(target)
    assertEquals(w.wallNow.unsafeRunSync(), target)
  }

  test("wallNow is stable across successive reads without side effects") {
    val w: Wall = new FixedWall(Instant.parse("2026-05-13T12:00:00Z"))
    val a = w.wallNow.unsafeRunSync()
    val b = w.wallNow.unsafeRunSync()
    assertEquals(a, b)
  }
}
