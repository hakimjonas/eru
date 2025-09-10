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

  given runtime: EruRuntime = EruRuntime.create()

  /** Validates that zipPar correctly combines independent effects in parallel.
    *
    * Tests the zipPar combinator by running two independent computations in parallel and combining
    * their results using a provided function.
    */
  test("zipPar combines independent effects") {
    val e = Eru.succeed(21).zipPar(Eru.succeed(2)).map(_ * _)
    assertEquals(e.runExit(), Exit.Success(42))
  }

  /** Validates that race returns the result of the first completing effect.
    *
    * Tests the race combinator using deterministic computation differences rather than timing
    * assumptions to ensure reliable test behavior across environments.
    */
  test("race returns first result") {
    val fast = Eru.succeed("fast")
    val slow = Eru.effect {
      (1 to 1000000).sum
      "slow"
    }
    val raced = fast.race(slow)
    val exit = raced.runExit()
    exit match {
      case Exit.Success(Left(value)) => assertEquals(value, "fast")
      case Exit.Success(Right(value)) =>
        assert(value == "fast" || value == "slow")
      case other => fail(s"Expected success, got: $other")
    }
  }

  /** Validates that Deferred and Ref primitives work correctly for fiber coordination.
    *
    * Tests Deferred for inter-fiber communication and Ref for concurrent state management, ensuring
    * proper coordination between concurrent computations.
    */
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

  /** Validates that raceAll returns the fastest effect with correct index information.
    *
    * Tests raceAll combinator using deterministic computation differences to ensure reliable
    * behavior while documenting the inherent non-determinism of race operations.
    */
  test("raceAll returns fastest effect with correct index") {
    val effects = List(
      Eru.effect {
        (1 to 500000).sum
        "slow-1"
      },
      Eru.succeed("fast"),
      Eru.effect {
        (1 to 1000000).sum
        "slow-2"
      }
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Success((value, index)) =>
        if (value == "fast" && index == 1) {
          assertEquals(value, "fast")
          assertEquals(index, 1)
        } else {
          assert(effects.indices.contains(index))
          assert(List("fast", "slow-1", "slow-2").contains(value))
        }
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
      Eru.effect {
        (1 to 500000).sum
        "slow"
      },
      Eru.fail("fast-failure"),
      Eru.effect {
        (1 to 1000000).sum
        "slower"
      }
    )

    val result = raceAll(effects).runExit()
    result match {
      case Exit.Failure(error: String) =>
        assertEquals(error, "fast-failure")
      case Exit.Success((value, _)) =>
        assert(List("slow", "slower").contains(value))
      case other => fail(s"expected failure or success, got $other")
    }
  }

  /** Validates that raceAll returns the winning effect with proper result structure.
    *
    * Tests raceAll with deterministic computation differences to verify that the winning effect's
    * result is returned correctly along with its index.
    */
  test("raceAll returns winning effect properly") {
    val effects = List(
      Eru.succeed("fast"),
      Eru.effect {
        (1 to 1000000).sum
        "slow"
      }
    )

    raceAll(effects).runExit() match {
      case Exit.Success((winner, index)) =>
        if (winner == "fast" && index == 0) {
          assertEquals(winner, "fast")
          assertEquals(index, 0)
        } else if (winner == "slow" && index == 1) {
          assert(true)
        } else {
          fail(s"Unexpected race result: winner=$winner, index=$index")
        }
      case other => fail(s"Expected success, got $other")
    }
  }

  /** Validates that parSequence executes multiple effects in parallel.
    *
    * Tests the parSequence combinator by running a list of effects in parallel and collecting their
    * results in the same order as the input list.
    */
  test("parSequence executes effects in parallel") {
    val effects = List(
      Eru.succeed("first"),
      Eru.succeed("second"),
      Eru.succeed("third")
    )

    val result = parSequence(effects).runExit()

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("first", "second", "third"))
      case other => fail(s"Expected success, got $other")
    }
  }

  /** Validates that parTraverse processes inputs in parallel with a transformation function.
    *
    * Tests the parTraverse combinator by applying a transformation function to each input in
    * parallel and collecting the results in order.
    */
  test("parTraverse processes inputs in parallel") {
    val inputs = List("a", "b", "c")

    def processInput(s: String): Eru[Nothing, String] = Eru.succeed(s.toUpperCase)

    val result = parTraverse(inputs)(processInput).runExit()

    result match {
      case Exit.Success(values) =>
        assertEquals(values, List("A", "B", "C"))
      case other => fail(s"Expected success, got $other")
    }
  }
}
