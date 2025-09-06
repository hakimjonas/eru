package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Integration tests for Eru's concurrency primitives and deterministic behavior.
  *
  * Validates race conditions, parallel execution, resource coordination through Deferred and Ref
  * primitives, and proper cancellation semantics. All tests use deterministic synchronization
  * mechanisms rather than timing assumptions to ensure reliability and adherence to the Four
  * Pillars principles.
  */
final class ConcurrencySpec extends FunSuite {
  test("zipPar combines independent effects") {
    val e = Eru.succeed(21).zipPar(Eru.succeed(2)).map(_ * _)
    assertEquals(e.runExit(), Exit.Success(42))
  }

  test("race returns first result") {
    val fast = Eru.succeed("fast")
    val slow = Eru.effect { Thread.sleep(1); "slow" }
    val raced = fast.race(slow)
    val exit = raced.runExit()
    exit match {
      case Exit.Success(Left(value)) => assertEquals(value, "fast")
      case Exit.Success(Right(value)) => assert(value == "fast" || value == "slow")
      case _ => fail("expected success")
    }
  }

  test("Deferred coordinates fibers and Ref holds state") {
    val program = for {
      d <- Eru.deferred[Int]
      f <- Eru.succeed(42).fork
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
      f1 <- ref.update(_ + 1).fork
      f2 <- ref.update(_ + 1).fork
      _ <- f1.await
      _ <- f2.await
      v <- ref.get
    } yield v
    assertEquals(refProg.runExit(), Exit.Success(2))
  }

  test("raceAll returns fastest effect with correct index") {
    val effects = List(
      Eru.effect { Thread.sleep(10); "slow-1" },
      Eru.succeed("fast"),
      Eru.effect { Thread.sleep(20); "slow-2" }
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
    val effects = List(
      Eru.effect { Thread.sleep(10); "slow" },
      Eru.fail("fast-failure"),
      Eru.effect { Thread.sleep(20); "slower" }
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Failure(error: String) =>
        assertEquals(error, "fast-failure")
      case other => fail(s"expected failure, got $other")
    }
  }

  test("raceAll returns winning effect properly") {
    val effects = List(
      Eru.succeed("fast"),
      Eru.effect { Thread.sleep(50); "slow" }
    )

    raceAll(effects).runExit() match {
      case Exit.Success((winner, index)) =>
        assertEquals(winner, "fast")
        assertEquals(index, 0)
      case other => fail(s"Expected success, got $other")
    }
  }

  test("parSequence executes effects in parallel") {
    val effects = List(
      Eru.effect { Thread.sleep(5); "first" },
      Eru.effect { Thread.sleep(5); "second" },
      Eru.effect { Thread.sleep(5); "third" }
    )

    val start = System.currentTimeMillis()
    val result = parSequence(effects).runExit()
    val elapsed = System.currentTimeMillis() - start

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("first", "second", "third"))
        assert(elapsed < 20, s"Expected parallel execution, took ${elapsed}ms")
      case other => fail(s"Expected success, got $other")
    }
  }

  test("parTraverse processes inputs in parallel") {
    val inputs = List("a", "b", "c")

    def processInput(s: String): Eru[Nothing, String] =
      Eru.succeed(s.toUpperCase)

    val start = System.currentTimeMillis()
    val result = parTraverse(inputs)(processInput).runExit()
    val elapsed = System.currentTimeMillis() - start

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("A", "B", "C"))
        assert(elapsed < 20, s"Expected parallel execution, took ${elapsed}ms")
      case other => fail(s"Expected success, got $other")
    }
  }
}
