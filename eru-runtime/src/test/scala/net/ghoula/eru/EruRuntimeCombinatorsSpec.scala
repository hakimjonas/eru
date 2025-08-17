package net.ghoula.eru

import munit.FunSuite

import java.time.Duration

final class EruRuntimeCombinatorsSpec extends FunSuite {

  test("zipPar success-success returns tuple") {
    val a = EruRuntime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.succeed(1))
    val b = EruRuntime.sleep(Duration.ofMillis(15)).flatMap(_ => Eru.succeed("ok"))
    val res = EruRuntime.zipPar(a, b).unsafeRunSync()
    assertEquals(res, (1, "ok"))
  }

  test("zipPar left failure interrupts right and finalizer runs") {
    var finalized = 0
    val left: Eru[String, Int] = Eru.fail("boom")
    val right: Eru[Nothing, Int] =
      EruRuntime.sleep(Duration.ofMillis(30)).flatMap(_ => Eru.succeed(42)).ensure(Eru.effect { finalized += 1; () })

    intercept[EruException[String]] {
      EruRuntime.zipPar(left, right).unsafeRunSync()
    }
    assertEquals(finalized, 1)
  }

  test("race faster left success wins and cancels right") {
    var finalized = 0
    val left = Eru.succeed(10)
    val right =
      EruRuntime.sleep(Duration.ofMillis(100)).flatMap(_ => Eru.succeed(1)).ensure(Eru.effect { finalized += 1; () })
    val res = EruRuntime.race(left, right).unsafeRunSync()
    assertEquals(res, Left(10))
    // loser finalizer should run (either by completion or interruption)
    assertEquals(finalized, 1)
  }

  test("race faster left failure wins and cancels right, finalizers run") {
    var finalized = 0
    val left: Eru[String, Int] = Eru.fail("boom")
    val right =
      EruRuntime.sleep(Duration.ofMillis(200)).flatMap(_ => Eru.succeed(1)).ensure(Eru.effect { finalized += 1; () })
    intercept[EruException[String]] {
      EruRuntime.race(left, right).unsafeRunSync()
    }
    assertEquals(finalized, 1)
  }

  test("sleep suspends approximately the requested duration") {
    val start = System.nanoTime()
    EruRuntime.sleep(Duration.ofMillis(25)).unsafeRunSync()
    val elapsedMs = (System.nanoTime() - start) / 1000000L
    assert(elapsedMs >= 20L, s"elapsed should be >= 20ms, got ${elapsedMs}ms")
  }

  test("timeout interrupts a long-running effect and does not interrupt a fast effect") {
    import java.util.concurrent.TimeoutException
    val long = EruRuntime.sleep(Duration.ofMillis(200)).flatMap(_ => Eru.succeed(1))
    intercept[TimeoutException] {
      EruRuntime.timeout(Duration.ofMillis(20))(long).unsafeRunSync()
    }
    val fast = Eru.succeed(7)
    val out = EruRuntime.timeout(Duration.ofMillis(100))(fast).unsafeRunSync()
    assertEquals(out, 7)
  }

  test("retry recurs(3) attempts 4 times in total and eventually succeeds") {
    val cnt = Ref.make(0).unsafeRunSync()
    def prog: Eru[String, Int] =
      cnt.modify(n => (n + 1, n)).flatMap { n =>
        if (n < 3) Eru.fail("nope") else Eru.succeed(42)
      }
    val out = EruRuntime.retry(EruRuntime.Policy.Recurs(3))(prog).unsafeRunSync()
    assertEquals(out, 42)
    val calls = cnt.get.unsafeRunSync()
    assertEquals(calls, 4)
  }
}
