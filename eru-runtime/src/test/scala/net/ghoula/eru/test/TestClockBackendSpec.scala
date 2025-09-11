package net.ghoula.eru.test

import munit.FunSuite
import java.time.Duration
import net.ghoula.eru.*

/** Comprehensive test suite for TestClockBackend integration.
  *
  * Validates that TestClockBackend properly implements ConcurrencyBackend semantics
  * while providing deterministic timing control through TestClock integration.
  */
final class TestClockBackendSpec extends FunSuite {

  test("TestClockBackend sleep integrates with TestClock") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val initialTime = clock.currentTime
      
      // Start sleep operation
      val fiber = runtime.sleep(Duration.ofSeconds(5)).fork.unsafeRunSync()
      
      // Should be pending
      assertEquals(clock.pendingCount, 1)
      assert(fiber.await.attempt.unsafeRunSync().isFailure) // Not completed yet
      
      // Advance time to complete sleep
      clock.advance(Duration.ofSeconds(5))
      
      // Should be completed
      assertEquals(clock.pendingCount, 0)
      assertEquals(fiber.await.runExit(), Exit.Success(()))
    }
  }

  test("TestClockBackend timeout works with TestClock") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      // Create effect that would take 10 seconds
      val slowEffect = runtime.sleep(Duration.ofSeconds(10)).map(_ => "completed")
      val timedEffect = slowEffect.timeout(Duration.ofSeconds(5))
      
      val fiber = timedEffect.fork.unsafeRunSync()
      
      // Advance past timeout
      clock.advance(Duration.ofSeconds(6))
      
      // Should timeout
      fiber.await.runExit() match {
        case Exit.Failure(_: java.util.concurrent.TimeoutException) => // Expected
        case Exit.Die(_: java.util.concurrent.TimeoutException) => // Also acceptable
        case other => fail(s"Expected timeout, got: $other")
      }
    }
  }

  test("TestClockBackend retry with backoff uses TestClock") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      var attempts = 0
      val retryEffect = Eru.effect {
        attempts += 1
        if (attempts < 3) throw new RuntimeException(s"attempt $attempts")
        else "success"
      }.retryWithBackoff(Duration.ofSeconds(1), 5)
      
      val fiber = retryEffect.fork.unsafeRunSync()
      
      // Should have pending retry delays
      assert(clock.pendingCount > 0)
      
      // Advance time to complete retries
      clock.advance(Duration.ofSeconds(10))
      
      // Should succeed after retries
      assertEquals(fiber.await.runExit(), Exit.Success("success"))
      assertEquals(attempts, 3)
    }
  }

  test("TestClockBackend fork executes synchronously") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      var executed = false
      val effect = Eru.effect { executed = true; "done" }
      
      val fiber = effect.fork.unsafeRunSync()
      
      // Should execute immediately in TestClockBackend
      assert(executed)
      assertEquals(fiber.await.runExit(), Exit.Success("done"))
    }
  }

  test("TestClockBackend race always picks first effect") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val first = Eru.succeed("first")
      val second = Eru.succeed("second")
      
      val raceResult = first.race(second).unsafeRunSync()
      
      // TestClockBackend always picks the first effect
      assertEquals(raceResult, Left("first"))
    }
  }

  test("TestClockBackend observer integration") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
      val observer = new EruObserver {
        def onEvent(event: EruObserver.EruEvent): Unit = events += event
      }
      
      val effect = Eru.succeed(42)
      val fiber = effect.forkWithObserver(observer).unsafeRunSync()
      
      // Should have received fiber lifecycle events
      assert(events.exists(_.isInstanceOf[EruObserver.EruEvent.FiberStarted]))
      assert(events.exists(_.isInstanceOf[EruObserver.EruEvent.FiberCompleted]))
      
      assertEquals(fiber.await.runExit(), Exit.Success(42))
    }
  }

  test("TestClockBackend capabilities are correct") {
    val clock = TestClock.create()
    val backend = TestClockBackend(clock)
    
    val caps = backend.capabilities
    assertEquals(caps.virtualThreads, false)
    assertEquals(caps.structuredScopes, false)
    assertEquals(caps.timersNonBlocking, true)
  }

  test("TestClockBackend zero duration sleep completes immediately") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val result = runtime.sleep(Duration.ZERO).unsafeRunSync()
      assertEquals(result, ())
      assertEquals(clock.pendingCount, 0)
    }
  }

  test("TestClockBackend negative duration sleep completes immediately") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val result = runtime.sleep(Duration.ofSeconds(-1)).unsafeRunSync()
      assertEquals(result, ())
      assertEquals(clock.pendingCount, 0)
    }
  }

  test("TestClockBackend suspend with synchronous callback") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val suspended = Eru.suspend[String, Int] { callback =>
        callback(Right(42))
        Eru.unit
      }
      
      assertEquals(suspended.unsafeRunSync(), 42)
    }
  }

  test("TestClockBackend handles effect failures correctly") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val failingEffect = Eru.fail("boom")
      val fiber = failingEffect.fork.unsafeRunSync()
      
      assertEquals(fiber.await.runExit(), Exit.Failure("boom"))
    }
  }

  test("TestClockBackend handles exceptions correctly") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val throwingEffect = Eru.effect(throw new RuntimeException("crash"))
      val fiber = throwingEffect.fork.unsafeRunSync()
      
      fiber.await.runExit() match {
        case Exit.Die(_: RuntimeException) => // Expected
        case other => fail(s"Expected Die with RuntimeException, got: $other")
      }
    }
  }
}