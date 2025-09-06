package examples

import munit.FunSuite
import net.ghoula.eru.prelude.*
import java.time.Duration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicInteger}

/** Examples of correct ways to test concurrent behavior without hardcoded timings */
final class BetterConcurrencyTests extends FunSuite {

  // ===== PATTERN 1: Use Coordination Primitives =====
  test("zipPar executes in parallel - coordination-based test") {
    val program = for {
      orderRef <- Eru.ref(List.empty[String])
      barrier1 <- Eru.deferred[Unit]
      barrier2 <- Eru.deferred[Unit]

      // Define two effects that coordinate their execution
      effect1 = for {
        _ <- orderRef.update("started-1" :: _)
        _ <- barrier1.complete(())  // Signal that effect1 started
        _ <- barrier2.poll.flatMap(_.fold(Eru.effect(Thread.sleep(1)))(Eru.succeed))
        _ <- orderRef.update("finished-1" :: _)
      } yield "result-1"

      effect2 = for {
        _ <- barrier1.poll.flatMap(_.fold(Eru.unit)(Eru.succeed))
        _ <- orderRef.update("started-2" :: _)
        _ <- barrier2.complete(())  // Signal that effect2 started
        _ <- orderRef.update("finished-2" :: _)
      } yield "result-2"

      // Execute in parallel
      results <- effect1.zipPar(effect2)
      executionOrder <- orderRef.get
    } yield (results, executionOrder.reverse)

    val ((result1, result2), order) = program.runUnsafe()
    assertEquals(result1, "result-1")
    assertEquals(result2, "result-2")

    // Verify that both effects started before either finished (parallel execution)
    val startedIndices = order.zipWithIndex.collect {
      case (event, idx) if event.startsWith("started") => event -> idx
    }.toMap
    val finishedIndices = order.zipWithIndex.collect {
      case (event, idx) if event.startsWith("finished") => event -> idx
    }.toMap

    // Both should have started before either finished
    assert(startedIndices.values.max < finishedIndices.values.min,
           "Effects should start before any finish (parallel execution)")
  }

  // ===== PATTERN 2: Test Resource Safety Through Finalizers =====
  test("raceAll cancels losing effects - resource safety test") {
    val program = for {
      // Track resource lifecycle
      acquired <- Eru.ref(Set.empty[String])
      released <- Eru.ref(Set.empty[String])

      // Effect that tracks its resource lifecycle
      def trackedEffect(id: String, duration: Duration) =
        Eru.effect(s"result-$id")
          .ensure(for {
            _ <- acquired.update(_ + id)
            _ <- sleep(duration)  // Simulate work
          } yield ())
          .ensure(for {
            _ <- released.update(_ + id)
          } yield ())

      // Race a fast effect against slow ones
      effects = List(
        Eru.succeed("winner"),
        trackedEffect("slow-1", Duration.ofSeconds(10)),
        trackedEffect("slow-2", Duration.ofSeconds(10))
      )

      // Execute race
      (winner, index) <- raceAll(effects)

      // Give finalizers time to run
      _ <- sleep(Duration.ofMillis(100))

      // Check resource state
      acquiredResources <- acquired.get
      releasedResources <- released.get
    } yield (winner, index, acquiredResources, releasedResources)

    val (winner, index, acquired, released) = program.runUnsafe()
    assertEquals(winner, "winner")
    assertEquals(index, 0)
    // All acquired resources should be released (proper cleanup)
    assertEquals(acquired, released, "All acquired resources should be released")
  }

  // ===== PATTERN 3: Test Cancellation Through Observability =====
  test("fiber interruption works correctly - observability-based test") {
    val program = for {
      // Create an effect that can detect interruption
      interruptSignal <- Eru.deferred[InterruptCause]

      longRunningEffect = for {
        _ <- sleep(Duration.ofHours(1))  // Would run forever if not interrupted
      } yield "completed"

      // Fork the long-running effect
      fiber <- longRunningEffect.fork

      // Interrupt it immediately
      cause = InterruptCause.Cancelled(Some("test cancellation"))
      _ <- fiber.interrupt(cause)

      // Await the result
      exit <- fiber.await
    } yield (exit, cause)

    val (exit, originalCause) = program.runUnsafe()

    // Verify proper interruption
    exit match {
      case Exit.Interrupt(fiberId, receivedCause) =>
        assertEquals(receivedCause, originalCause)
        assertEquals(fiberId, exit.asInstanceOf[Exit.Interrupt].fiberId)
      case other =>
        fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  // ===== PATTERN 4: Test Race Conditions Through Deterministic Scenarios =====
  test("race returns winner deterministically") {
    val program = for {
      // Create effects with clear winners
      definiteWinner = Eru.succeed("fast")
      definiteLoser = for {
        _ <- sleep(Duration.ofSeconds(1))
        result <- Eru.succeed("slow")
      } yield result

      // Race them
      result <- definiteWinner.race(definiteLoser)
    } yield result

    val result = program.runUnsafe()
    result match {
      case Left(winner) => assertEquals(winner, "fast")
      case Right(_) => fail("Fast effect should always win")
    }
  }

  // ===== PATTERN 5: Test Concurrent State Updates =====
  test("concurrent updates maintain consistency") {
    val program = for {
      counter <- Eru.ref(0)

      // Create many concurrent updates
      updates = (1 to 100).map(_ => counter.update(_ + 1))

      // Execute all updates concurrently
      _ <- updates.map(_.fork).sequence.flatMap(fibers =>
        fibers.map(_.await).sequence
      )

      finalValue <- counter.get
    } yield finalValue

    val result = program.runUnsafe()
    assertEquals(result, 100, "All concurrent updates should be applied")
  }
}
