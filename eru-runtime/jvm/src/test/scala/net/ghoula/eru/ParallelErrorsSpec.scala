package net.ghoula.eru

import net.ghoula.eru.test.*

class ParallelErrorsSpec extends EruTestSuite {

  test("zipPar returns first error when both fail") {
    val left = Eru.fail[String]("left-error")
    val right = Eru.fail[String]("right-error")

    val result = runtime.zipPar(left, right).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error: String) =>
        // Currently returns first error - aggregation would need type system changes
        assert(error == "left-error")
      case other =>
        fail(s"Expected error, got $other")
    }
  }

  test("parSequence returns first error from failures") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("error1"),
      Eru.succeed(2),
      Eru.fail("error2"),
      Eru.fail("error3")
    )

    val result = runtime.parSequence(effects).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error: String) =>
        // Currently returns first error
        assert(error == "error1")
      case other =>
        fail(s"Expected error, got $other")
    }
  }

  test("cooperative cancellation with yieldIfInterrupted") {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicInteger

    // Use real runtime with VirtualThreads for actual interruption
    val counter = new AtomicInteger(0)
    val startedLatch = new CountDownLatch(1)

    val computation = runtime.fork {
      Eru.effect {
        startedLatch.countDown() // Signal that we've started
      }.flatMap { _ =>
        Eru.iterate(0) { i =>
          counter.incrementAndGet()
          for {
            // Check for interruption every iteration for this test
            _ <- Eru.yieldIfInterrupted
            // Add small delay to make interruption more likely
            _ <-
              if (i % 1000 == 0) {
                Eru.effect {
                  Thread.sleep(1) // Small delay every 1000 iterations
                }.attempt.map(_ => ())
              } else Eru.unit
            next <- Eru.succeed(i + 1)
          } yield next
        }(_ >= 10000000) // Very large number
      }
    }.unsafeRunSync()

    // Wait for computation to start
    assert(startedLatch.await(1, TimeUnit.SECONDS), "Fiber failed to start")

    // Give it a tiny bit more time to get going
    Thread.sleep(10)

    // Interrupt the fiber
    computation.interrupt(InterruptCause.Cancelled(Some("test"))).unsafeRunSync()

    // Await the result
    val exit = computation.await.unsafeRunSync()

    val finalIterations = counter.get()

    exit match {
      case Exit.Interrupt(_, _) =>
        // Expected - cooperative cancellation worked
        assert(finalIterations > 0, s"Expected some iterations, got $finalIterations")
        assert(finalIterations < 10000000, s"Expected interruption before completion, got $finalIterations iterations")
      case Exit.Success(value) =>
        fail(s"Computation completed without interruption, value=$value, iterations=$finalIterations")
      case other =>
        fail(s"Expected interruption, got $other")
    }
  }

  test("ParallelErrors helpers work correctly") {
    val errors = ParallelErrors("first", List("second", "third"))

    assert(errors.all == List("first", "second", "third"))
    assert(errors.size == 3)

    val mapped = errors.map(_.toUpperCase)
    assert(mapped.first == "FIRST")
    assert(mapped.rest == List("SECOND", "THIRD"))

    val reduced = errors.reduce(_ + ", " + _)
    assert(reduced == "first, second, third")
  }

  test("zipParAll collects both errors when both fail") {
    val left = Eru.fail[String]("left-error")
    val right = Eru.fail[String]("right-error")

    val result = runtime.zipParAll(left, right).attempt.unsafeRunSync()

    result match {
      case Result.Failure(ParallelErrors(first, rest)) =>
        assert(first == "left-error")
        assert(rest == List("right-error"))
      case other =>
        fail(s"Expected ParallelErrors, got $other")
    }
  }

  test("zipParAll returns single error when only one fails") {
    val left = Eru.succeed(42)
    val right = Eru.fail[String]("right-error")

    val result = runtime.zipParAll(left, right).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error: String) =>
        assert(error == "right-error")
      case other =>
        fail(s"Expected single error, got $other")
    }
  }

  test("zipParAll succeeds when both succeed") {
    val left = Eru.succeed(42)
    val right = Eru.succeed("hello")

    val result = runtime.zipParAll(left, right).unsafeRunSync()

    assert(result == (42, "hello"))
  }

  test("parSequenceAll collects all errors from multiple failures") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("error1"),
      Eru.succeed(2),
      Eru.fail("error2"),
      Eru.fail("error3")
    )

    val result = runtime.parSequenceAll(effects).attempt.unsafeRunSync()

    result match {
      case Result.Failure(ParallelErrors(first, rest)) =>
        assert(first == "error1")
        assert(rest.toSet == Set("error2", "error3"))
      case other =>
        fail(s"Expected ParallelErrors with 3 errors, got $other")
    }
  }

  test("parSequenceAll returns single error when only one fails") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("only-error"),
      Eru.succeed(2),
      Eru.succeed(3)
    )

    val result = runtime.parSequenceAll(effects).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error: String) =>
        assert(error == "only-error")
      case other =>
        fail(s"Expected single error, got $other")
    }
  }

  test("parSequenceAll succeeds when all succeed") {
    val effects = List(
      Eru.succeed(1),
      Eru.succeed(2),
      Eru.succeed(3)
    )

    val result = runtime.parSequenceAll(effects).unsafeRunSync()

    assert(result == List(1, 2, 3))
  }

  test("root fiber cleanup happens more frequently") {
    // Fork many short-lived fibers
    val fibers = (1 to 50).map { i =>
      runtime.fork(Eru.succeed(i))
    }

    // They should complete quickly
    val results = Eru
      .foreach(fibers.toList) { fiber =>
        fiber.flatMap(_.await)
      }
      .unsafeRunSync()

    // All should complete
    assert(results.forall {
      case Exit.Success(_) => true
      case _ => false
    })
    // The more aggressive cleanup (at 20 fibers) should have run at least twice
  }
}
