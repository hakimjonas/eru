package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class ConcurrencySpec extends FunSuite {
  test("zipPar combines independent effects") {
    val e = Eru.succeed(21).zipPar(Eru.succeed(2)).map(_ * _)
    assertEquals(e.runExit(), Exit.Success(42))
  }

  test("race returns first result") {
    val slow = Eru.blocking(Thread.sleep(50)).map(_ => "slow")
    val fast = Eru.succeed("fast")
    val raced = fast.race(slow)
    val exit = raced.runExit()
    exit match {
      case Exit.Success(Left(value)) => assertEquals(value, "fast")
      case Exit.Success(Right(value)) => assertEquals(value, "fast")
      case _ => fail("expected success")
    }
  }

  test("Deferred coordinates fibers and Ref holds state") {
    val program = for {
      d <- Eru.deferred[Int]
      f <- Eru.succeed(42).fork
      _ <- Eru.blocking(Thread.sleep(10))
      _ <- d.complete(99)
      v <- d.poll.map(_.getOrElse(-1))
      x <- f.await.flatMap {
        case Exit.Success(a) => Eru.succeed(a)
        case _ => Eru.succeed(-1)
      }
    } yield (v, x)
    val ex = program.runExit()
    ex match {
      case Exit.Success((v, x)) =>
        assertEquals(v, 99)
        assertEquals(x, 42)
      case other => fail(s"expected success, got $other")
    }

    val refProg = for {
      ref <- Eru.ref(0)
      _ <- ref.update(_ + 1).fork
      _ <- ref.update(_ + 1).fork
      _ <- Eru.blocking(Thread.sleep(10))
      v <- ref.get
    } yield v
    assertEquals(refProg.runExit(), Exit.Success(2))
  }

  test("raceAll returns fastest effect with correct index") {
    import java.time.Duration
    val effects = List(
      sleep(Duration.ofMillis(50)).map(_ => "slow-1"), // index 0
      sleep(Duration.ofMillis(10)).map(_ => "fast"), // index 1 - should win
      sleep(Duration.ofMillis(100)).map(_ => "slow-2") // index 2
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Success((value, index)) =>
        assertEquals(value, "fast")
        assertEquals(index, 1)
      case other => fail(s"expected success, got $other")
    }
  }

  test("raceAll handles single effect") {
    val single = List(Eru.succeed("only"))
    val result = raceAll(single).runExit()
    result match {
      case Exit.Success((value, index)) =>
        assertEquals(value, "only")
        assertEquals(index, 0)
      case other => fail(s"expected success, got $other")
    }
  }

  test("raceAll fails on empty list") {
    val empty: List[Eru[Nothing, String]] = List.empty
    val result = raceAll(empty).runExit()
    result match {
      case Exit.Die(ex: IllegalArgumentException) =>
        assert(ex.getMessage.contains("empty list"))
      case other => fail(s"expected IllegalArgumentException, got $other")
    }
  }

  test("raceAll propagates winner's failure") {
    import java.time.Duration
    val effects = List(
      sleep(Duration.ofMillis(50)).map(_ => "slow"),
      Eru.fail("fast-failure"), // This should win
      sleep(Duration.ofMillis(100)).map(_ => "slower")
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Failure(error: String) =>
        assertEquals(error, "fast-failure")
      case other => fail(s"expected failure, got $other")
    }
  }

  test("raceAll cancels losing effects") {
    import java.time.Duration
    import java.util.concurrent.atomic.AtomicBoolean

    val cancelled = new AtomicBoolean(false)
    val effects = List(
      Eru.succeed("fast"), // This wins immediately
      sleep(Duration.ofSeconds(10))
        .ensure(Eru.effect(cancelled.set(true))) // This should be cancelled
        .map(_ => "slow")
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Success((value, index)) =>
        assertEquals(value, "fast")
        assertEquals(index, 0)
        // Poll for cancellation with timeout instead of arbitrary sleep
        val startTime = System.nanoTime()
        val timeoutNanos = 5_000_000_000L // 5 seconds
        while (!cancelled.get() && (System.nanoTime() - startTime) < timeoutNanos) {
          Thread.sleep(1) // Short polling interval
        }
        assert(cancelled.get(), "losing effect should have been cancelled")
      case other => fail(s"expected success, got $other")
    }
  }

  test("parSequence executes effects in parallel") {
    import java.time.Duration
    val effects = List(
      sleep(Duration.ofMillis(20)).map(_ => "first"),
      sleep(Duration.ofMillis(10)).map(_ => "second"),
      sleep(Duration.ofMillis(30)).map(_ => "third")
    )

    val start = System.currentTimeMillis()
    val result = parSequence(effects).runExit()
    val elapsed = System.currentTimeMillis() - start

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("first", "second", "third"))
        // Should take ~30ms (max) not 60ms (sequential)
        assert(elapsed < 50, s"expected parallel execution, took ${elapsed}ms")
      case other => fail(s"expected success, got $other")
    }
  }

  test("parTraverse processes inputs in parallel") {
    import java.time.Duration
    val inputs = List("a", "b", "c")

    def processInput(s: String): Eru[Nothing, String] =
      sleep(Duration.ofMillis(10)).map(_ => s.toUpperCase)

    val start = System.currentTimeMillis()
    val result = parTraverse(inputs)(processInput).runExit()
    val elapsed = System.currentTimeMillis() - start

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("A", "B", "C"))
        // Should take ~10ms (parallel) not 30ms (sequential)
        assert(elapsed < 25, s"expected parallel execution, took ${elapsed}ms")
      case other => fail(s"expected success, got $other")
    }
  }
}
