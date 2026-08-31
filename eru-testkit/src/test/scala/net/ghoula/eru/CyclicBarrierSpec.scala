package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

class CyclicBarrierSpec extends EruTestSuite {

  test("cyclic barrier creation succeeds") {
    val barrier = Eru.cyclicBarrier(3).unsafeRunSync()
    assertEquals(barrier.parties.eru.unsafeRunSync(), 3)
    assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.eru.unsafeRunSync(), false)
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

    barrier.await.eru.unsafeRunSync()

    barrier.await.eru.unsafeRunSync()
    barrier.await.eru.unsafeRunSync()
  }

  test("cyclic barrier properties are consistent") {
    val barrier = Eru.cyclicBarrier(5).unsafeRunSync()

    assertEquals(barrier.parties.eru.unsafeRunSync(), 5)
    assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.eru.unsafeRunSync(), false)
  }

  test("cyclic barrier constructor is available via Eru companion") {
    val barrier = Eru.cyclicBarrier(2).unsafeRunSync()
    assertEquals(barrier.parties.eru.unsafeRunSync(), 2)
  }

  test("cyclic barrier operations compose with other Eru effects") {
    val program = for {
      barrier <- Eru.cyclicBarrier(1)
      parties <- barrier.parties.eru
      waiting <- barrier.waiting.eru
      _ <- barrier.await.eru
      stillWaiting <- barrier.waiting.eru
    } yield (parties, waiting, stillWaiting)

    val (parties, waiting, stillWaiting) = program.unsafeRunSync()
    assertEquals(parties, 1)
    assertEquals(waiting, 0)
    assertEquals(stillWaiting, 0)
  }

  test("cyclic barrier state remains consistent") {
    val barrier = Eru.cyclicBarrier(3).unsafeRunSync()

    assertEquals(barrier.parties.eru.unsafeRunSync(), 3)
    assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
    assertEquals(barrier.isBroken.eru.unsafeRunSync(), false)

    assertEquals(barrier.parties.eru.unsafeRunSync(), 3)
    assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
  }

  test("cyclic barrier multiple single-party uses") {
    val barrier = Eru.cyclicBarrier(1).unsafeRunSync()

    for (_ <- 1 to 5) {
      barrier.await.eru.unsafeRunSync()
      assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
    }
  }

  test("cyclic barrier batch operations with single party") {
    val barrier = Eru.cyclicBarrier(1).unsafeRunSync()

    Eru
      .collectAllDiscard(
        List(
          barrier.await.eru,
          barrier.await.eru,
          barrier.await.eru
        )
      )
      .unsafeRunSync()

    assertEquals(barrier.waiting.eru.unsafeRunSync(), 0)
  }
}
