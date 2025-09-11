package net.ghoula.eru.test

import munit.FunSuite
import java.time.{Duration, Instant}
import net.ghoula.eru.*

/** Comprehensive test suite for TestClock functionality.
  *
  * Validates that TestClock provides precise time control and correct integration
  * with Eru's timing operations. These tests ensure deterministic behavior and
  * proper resource management in the testing infrastructure.
  */
final class TestClockSpec extends FunSuite {

  test("TestClock creation sets initial time correctly") {
    val startTime = Instant.parse("2025-01-01T12:00:00Z")
    val clock = TestClock.create(startTime)
    assertEquals(clock.currentTime, startTime)
  }

  test("TestClock advances time correctly") {
    val clock = TestClock.create()
    val initialTime = clock.currentTime
    
    val advanced = clock.advance(Duration.ofSeconds(30))
    assertEquals(advanced, 0) // No operations to complete
    assertEquals(clock.currentTime, initialTime.plus(Duration.ofSeconds(30)))
  }

  test("TestClock setTime updates current time") {
    val clock = TestClock.create()
    val newTime = Instant.parse("2025-06-15T08:30:00Z")
    
    clock.setTime(newTime)
    assertEquals(clock.currentTime, newTime)
  }

  test("TestClock tracks pending operations correctly") {
    val clock = TestClock.create()
    assertEquals(clock.pendingCount, 0)
    assertEquals(clock.nextScheduled, None)
  }

  test("TestClock scheduling and completion integration") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val currentTime = clock.currentTime
    
    var completed = false
    val targetTime = currentTime.plus(Duration.ofSeconds(5))
    
    // Schedule a callback
    clock.schedule(targetTime, () => completed = true)
    
    assertEquals(clock.pendingCount, 1)
    assertEquals(clock.nextScheduled, Some(targetTime))
    assertNot(completed)
    
    // Advance to before target time - should not complete
    clock.advance(Duration.ofSeconds(3))
    assertNot(completed)
    assertEquals(clock.pendingCount, 1)
    
    // Advance past target time - should complete
    clock.advance(Duration.ofSeconds(5))
    assert(completed)
    assertEquals(clock.pendingCount, 0)
  }

  test("TestClock triggerNext advances to next operation") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val currentTime = clock.currentTime
    
    var completed = false
    val targetTime = currentTime.plus(Duration.ofMinutes(2))
    clock.schedule(targetTime, () => completed = true)
    
    val triggered = clock.triggerNext
    assert(triggered)
    assert(completed)
    assertEquals(clock.currentTime, targetTime)
  }

  test("TestClock triggerNext returns false when no operations pending") {
    val clock = TestClock.create()
    val triggered = clock.triggerNext
    assertNot(triggered)
  }

  test("TestClock completeAll finishes all pending operations") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val currentTime = clock.currentTime
    
    var count = 0
    
    // Schedule multiple operations at different times
    clock.schedule(currentTime.plus(Duration.ofSeconds(10)), () => count += 1)
    clock.schedule(currentTime.plus(Duration.ofSeconds(20)), () => count += 1) 
    clock.schedule(currentTime.plus(Duration.ofSeconds(5)), () => count += 1)
    
    assertEquals(clock.pendingCount, 3)
    
    val completed = clock.completeAll
    assertEquals(completed, 3)
    assertEquals(count, 3)
    assertEquals(clock.pendingCount, 0)
    
    // Should advance to the latest scheduled time
    assertEquals(clock.currentTime, currentTime.plus(Duration.ofSeconds(20)))
  }

  test("TestClock handles multiple operations at same time") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val targetTime = clock.currentTime.plus(Duration.ofSeconds(1))
    
    var count = 0
    
    // Schedule multiple callbacks at the same time
    clock.schedule(targetTime, () => count += 1)
    clock.schedule(targetTime, () => count += 1)
    clock.schedule(targetTime, () => count += 1)
    
    assertEquals(clock.pendingCount, 3)
    
    val completed = clock.setTime(targetTime)
    assertEquals(completed, 3)
    assertEquals(count, 3)
  }

  test("TestClock operations are thread-safe") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val currentTime = clock.currentTime
    
    import scala.concurrent.Future
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.DurationInt
    import scala.concurrent.Await
    
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Schedule operations from multiple threads
    val futures = (1 to 100).map { i =>
      Future {
        val targetTime = currentTime.plus(Duration.ofMillis(i))
        clock.schedule(targetTime, () => count.incrementAndGet())
      }
    }
    
    Await.ready(Future.sequence(futures), 5.seconds)
    
    assertEquals(clock.pendingCount, 100)
    
    // Complete all operations
    clock.completeAll
    assertEquals(count.get(), 100)
  }

  test("TestClock callback exceptions don't prevent other callbacks") {
    val clock = TestClock.create().asInstanceOf[TestClockImpl]
    val targetTime = clock.currentTime.plus(Duration.ofSeconds(1))
    
    var goodCount = 0
    
    // Mix good and failing callbacks
    clock.schedule(targetTime, () => goodCount += 1)
    clock.schedule(targetTime, () => throw new RuntimeException("test exception"))
    clock.schedule(targetTime, () => goodCount += 1)
    
    // Should complete all callbacks despite exception
    val completed = clock.setTime(targetTime)
    assertEquals(completed, 3)
    assertEquals(goodCount, 2)
  }
}