package net.ghoula.eru.test

import munit.FunSuite
import java.time.Duration
import net.ghoula.eru.*

/** Comprehensive test suite for EruTest utilities.
  *
  * Validates that EruTest provides reliable, ergonomic testing utilities for
  * effect-based code with proper error handling and timeout behavior.
  */
final class EruTestSpec extends FunSuite {

  test("EruTest.withTestClock provides working TestClock") {
    EruTest.withTestClock { clock =>
      val initialTime = clock.currentTime
      clock.advance(Duration.ofMinutes(1))
      assertEquals(clock.currentTime, initialTime.plus(Duration.ofMinutes(1)))
    }
  }

  test("EruTest.withTestClock cleans up pending operations") {
    var clock: TestClock = null
    
    EruTest.withTestClock { testClock =>
      clock = testClock
      given runtime: EruRuntime = EruTest.testRuntime(testClock)
      
      // Start a long sleep that won't complete
      runtime.sleep(Duration.ofHours(1)).fork.unsafeRunSync()
      
      assert(testClock.pendingCount > 0)
    }
    
    // After withTestClock, operations should be cleaned up
    assertEquals(clock.pendingCount, 0)
  }

  test("EruTest.assertCompletes succeeds for successful effects") {
    val effect = Eru.succeed(42)
    val result = EruTest.assertCompletes(effect, Duration.ofSeconds(1))
    assertEquals(result, 42)
  }

  test("EruTest.assertCompletes fails for failing effects") {
    val effect = Eru.fail("boom")
    
    intercept[AssertionError] {
      EruTest.assertCompletes(effect, Duration.ofSeconds(1))
    }
  }

  test("EruTest.assertCompletes fails for throwing effects") {
    val effect = Eru.effect(throw new RuntimeException("crash"))
    
    intercept[AssertionError] {
      EruTest.assertCompletes(effect, Duration.ofSeconds(1))
    }
  }

  test("EruTest.assertFails succeeds for failing effects with correct error") {
    val effect = Eru.fail("expected error")
    EruTest.assertFails(effect, "expected error")
  }

  test("EruTest.assertFails fails for successful effects") {
    val effect = Eru.succeed(42)
    
    intercept[AssertionError] {
      EruTest.assertFails(effect, "any error")
    }
  }

  test("EruTest.assertFails fails for effects with wrong error") {
    val effect = Eru.fail("actual error")
    
    intercept[AssertionError] {
      EruTest.assertFails(effect, "expected error")
    }
  }

  test("EruTest.assertFails with class succeeds for throwing effects") {
    val effect = Eru.effect(throw new IllegalArgumentException("invalid"))
    EruTest.assertFails(effect, classOf[IllegalArgumentException])
  }

  test("EruTest.assertFails with class fails for wrong exception type") {
    val effect = Eru.effect(throw new IllegalArgumentException("invalid"))
    
    intercept[AssertionError] {
      EruTest.assertFails(effect, classOf[IllegalStateException])
    }
  }

  test("EruTest.assertSucceedsWith combines completion and value checking") {
    val effect = Eru.succeed("expected")
    EruTest.assertSucceedsWith(effect, "expected")
  }

  test("EruTest.assertSucceedsWith fails for wrong value") {
    val effect = Eru.succeed("actual")
    
    intercept[AssertionError] {
      EruTest.assertSucceedsWith(effect, "expected")
    }
  }

  test("EruTest.testRuntime creates working runtime") {
    val clock = TestClock.create()
    given runtime: EruRuntime = EruTest.testRuntime(clock)
    
    // Test that runtime works with TestClock
    val fiber = runtime.sleep(Duration.ofSeconds(1)).fork.unsafeRunSync()
    assertEquals(clock.pendingCount, 1)
    
    clock.advance(Duration.ofSeconds(1))
    assertEquals(fiber.await.runExit(), Exit.Success(()))
  }

  test("EruTest.testRuntimeWithClock provides clock and runtime") {
    val (clock, runtime) = EruTest.testRuntimeWithClock()
    
    given EruRuntime = runtime
    
    val fiber = runtime.sleep(Duration.ofSeconds(2)).fork.unsafeRunSync()
    assertEquals(clock.pendingCount, 1)
    
    clock.advance(Duration.ofSeconds(2))
    assertEquals(fiber.await.runExit(), Exit.Success(()))
  }

  test("EruTest utilities work with complex timing scenarios") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      // Create a complex timing scenario
      val effect = for {
        _ <- runtime.sleep(Duration.ofSeconds(1))
        result <- Eru.succeed("step1")
        _ <- runtime.sleep(Duration.ofSeconds(2))
        final <- Eru.succeed(result + "-step2")
      } yield final
      
      val fiber = effect.fork.unsafeRunSync()
      
      // Advance time step by step
      clock.advance(Duration.ofSeconds(1))
      assert(clock.pendingCount > 0)
      
      clock.advance(Duration.ofSeconds(2))
      assertEquals(clock.pendingCount, 0)
      
      EruTest.assertSucceedsWith(fiber.await, "step1-step2")
    }
  }

  test("EruTest handles timeout scenarios properly") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val slowEffect = runtime.sleep(Duration.ofSeconds(10)).map(_ => "too slow")
      val timedEffect = slowEffect.timeout(Duration.ofSeconds(3))
      
      val fiber = timedEffect.fork.unsafeRunSync()
      
      // Advance past timeout
      clock.advance(Duration.ofSeconds(5))
      
      // Should fail with timeout exception
      EruTest.assertFails(fiber.await, classOf[java.util.concurrent.TimeoutException])
    }
  }

  test("EruTest handles retry scenarios") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      var attempts = 0
      val retryEffect = Eru.effect {
        attempts += 1
        if (attempts < 4) throw new RuntimeException(s"attempt $attempts")
        else "finally succeeded"
      }.retryN(5)
      
      val fiber = retryEffect.fork.unsafeRunSync()
      
      // Complete all retry delays
      clock.completeAll
      
      EruTest.assertSucceedsWith(fiber.await, "finally succeeded")
      assertEquals(attempts, 4)
    }
  }

  test("EruTest assertion error messages are helpful") {
    val effect = Eru.succeed(42)
    
    val error = intercept[AssertionError] {
      EruTest.assertFails(effect, "boom")
    }
    
    assert(error.getMessage.contains("succeeded unexpectedly"))
    assert(error.getMessage.contains("42"))
  }

  test("EruTest handles effect composition correctly") {
    EruTest.withTestClock { clock =>
      given runtime: EruRuntime = EruTest.testRuntime(clock)
      
      val composed = for {
        a <- Eru.succeed(1)
        b <- Eru.succeed(2) 
        c <- runtime.sleep(Duration.ofMillis(100)).map(_ => 3)
        result <- Eru.succeed(a + b + c)
      } yield result
      
      val fiber = composed.fork.unsafeRunSync()
      clock.advance(Duration.ofMillis(100))
      
      EruTest.assertSucceedsWith(fiber.await, 6)
    }
  }
}