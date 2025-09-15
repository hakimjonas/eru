package net.ghoula.eru

import net.ghoula.eru.prelude.*

class CyclicBarrierSpec extends TestWithSharedRuntime {

  test("cyclic barrier creation succeeds") {
    val barrier = Eru.cyclicBarrier(3).unsafeRunSync()
    assertEquals(barrier.getParties.unsafeRunSync(), 3)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.unsafeRunSync(), false)
  }

  test("cyclic barrier creation fails with non-positive parties") {
    intercept[IllegalArgumentException] {
      Eru.cyclicBarrier(0).unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      Eru.cyclicBarrier(-1).unsafeRunSync()
    }
  }

  test("cyclic barrier with single party returns immediately") {
    val barrier = Eru.cyclicBarrier(1).unsafeRunSync()

    // Should return immediately since only one party is needed
    barrier.await.unsafeRunSync()

    // Can be used multiple times (cyclic)
    barrier.await.unsafeRunSync()
    barrier.await.unsafeRunSync()
  }

  test("cyclic barrier properties are consistent") {
    val barrier = Eru.cyclicBarrier(5).unsafeRunSync()

    assertEquals(barrier.getParties.unsafeRunSync(), 5)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.unsafeRunSync(), false)
  }

  test("cyclic barrier constructor is available via Eru companion") {
    val barrier = Eru.cyclicBarrier(2).unsafeRunSync()
    assertEquals(barrier.getParties.unsafeRunSync(), 2)
  }

  test("cyclic barrier operations compose with other Eru effects") {
    val program = for {
      barrier <- Eru.cyclicBarrier(1)
      parties <- barrier.getParties
      waiting <- barrier.getNumberWaiting
      _ <- barrier.await // Should return immediately for single party
      stillWaiting <- barrier.getNumberWaiting
    } yield (parties, waiting, stillWaiting)

    val (parties, waiting, stillWaiting) = program.unsafeRunSync()
    assertEquals(parties, 1)
    assertEquals(waiting, 0)
    assertEquals(stillWaiting, 0)
  }

  test("cyclic barrier state remains consistent") {
    val barrier = Eru.cyclicBarrier(3).unsafeRunSync()

    // Initial state
    assertEquals(barrier.getParties.unsafeRunSync(), 3)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.unsafeRunSync(), false)

    // State should remain consistent after queries
    assertEquals(barrier.getParties.unsafeRunSync(), 3)
    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
  }

  test("cyclic barrier multiple single-party uses") {
    val barrier = Eru.cyclicBarrier(1).unsafeRunSync()

    // Use barrier multiple times in sequence
    for (_ <- 1 to 5) {
      barrier.await.unsafeRunSync()
      assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
    }
  }

  test("cyclic barrier batch operations with single party") {
    val barrier = Eru.cyclicBarrier(1).unsafeRunSync()

    // Multiple awaits should all succeed immediately
    Eru
      .collectAllDiscard(
        List(
          barrier.await,
          barrier.await,
          barrier.await
        )
      )
      .unsafeRunSync()

    assertEquals(barrier.getNumberWaiting.unsafeRunSync(), 0)
  }
}
