package net.ghoula.eru

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

class CountDownLatchSpec extends EruTestSuite {

  test("countdown latch creation succeeds") {
    val latch = Eru.countDownLatch(3).unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 3)
    assertEquals(latch.isZero.eru.unsafeRunSync(), false)
  }

  test("countdown latch with zero count") {
    val latch = Eru.countDownLatch(0).unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
    assertEquals(latch.isZero.eru.unsafeRunSync(), true)

    // Await should return immediately
    latch.await.eru.unsafeRunSync()
  }

  test("countdown latch creation fails with negative count") {
    intercept[IllegalArgumentException] {
      Eru.countDownLatch(-1).unsafeRunSync()
    }
  }

  test("countdown latch single countdown") {
    val latch = Eru.countDownLatch(1).unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 1)

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
    assertEquals(latch.isZero.eru.unsafeRunSync(), true)
  }

  test("countdown latch multiple countdowns") {
    val latch = Eru.countDownLatch(3).unsafeRunSync()

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 2)

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 1)

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
    assertEquals(latch.isZero.eru.unsafeRunSync(), true)
  }

  test("countdown beyond zero has no effect") {
    val latch = Eru.countDownLatch(1).unsafeRunSync()

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)

    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
  }

  test("await returns immediately when count is already zero") {
    val latch = Eru.countDownLatch(0).unsafeRunSync()
    latch.await.eru.unsafeRunSync() // Should return immediately
  }

  test("countdown latch constructor is available via Eru companion") {
    val latch = Eru.countDownLatch(2).unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 2)
  }

  test("countdown latch operations compose with other Eru effects") {
    val program = for {
      latch <- Eru.countDownLatch(2)
      _ <- latch.countDown.eru
      count1 <- latch.getCount.eru
      _ <- latch.countDown.eru
      count2 <- latch.getCount.eru
      isComplete <- latch.isZero.eru
    } yield (count1, count2, isComplete)

    val (count1, count2, isComplete) = program.unsafeRunSync()
    assertEquals(count1, 1)
    assertEquals(count2, 0)
    assertEquals(isComplete, true)
  }

  test("countdown latch batch countdown operations") {
    val latch = Eru.countDownLatch(5).unsafeRunSync()

    // Count down multiple times
    Eru
      .collectAllDiscard(
        List(
          latch.countDown.eru,
          latch.countDown.eru,
          latch.countDown.eru,
          latch.countDown.eru,
          latch.countDown.eru
        )
      )
      .unsafeRunSync()

    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
    assertEquals(latch.isZero.eru.unsafeRunSync(), true)
  }

  test("countdown latch state is consistent across operations") {
    val latch = Eru.countDownLatch(3).unsafeRunSync()

    // Check initial state
    assertEquals(latch.getCount.eru.unsafeRunSync(), 3)
    assertEquals(latch.isZero.eru.unsafeRunSync(), false)

    // Countdown once
    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 2)
    assertEquals(latch.isZero.eru.unsafeRunSync(), false)

    // Countdown twice more
    latch.countDown.eru.unsafeRunSync()
    latch.countDown.eru.unsafeRunSync()
    assertEquals(latch.getCount.eru.unsafeRunSync(), 0)
    assertEquals(latch.isZero.eru.unsafeRunSync(), true)
  }
}
