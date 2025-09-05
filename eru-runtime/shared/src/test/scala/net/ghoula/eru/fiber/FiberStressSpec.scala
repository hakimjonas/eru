package net.ghoula.eru.fiber

import munit.FunSuite
import java.time.Duration
import scala.collection.mutable

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Stress tests to ensure fiber runtime stability under high load.
  *
  * Tests the runtime stability with thousands of concurrent fibers and deep nesting
  * to verify no stack overflow or memory issues occur in the unified fiber runtime.
  */
class FiberStressSpec extends FunSuite {

  test("high-volume fiber creation and completion (1000 fibers)".flaky) {
    val fiberCount = 1000
    val fibers = (1 to fiberCount).map { i =>
      EruRuntime.fork(Eru.succeed(i))
    }.toList
    
    val allFibers = EruRuntime.parSequence(fibers).unsafeRunSync()
    val results = EruRuntime.parSequence(allFibers.map(_.await)).unsafeRunSync()
    
    assertEquals(results.length, fiberCount)
    
    val successResults = results.collect { case Exit.Success(value) => value }
    assertEquals(successResults.length, fiberCount)
    assertEquals(successResults.sum, (1 to fiberCount).sum)
  }

  test("deep fiber nesting does not cause stack overflow") {
    val depth = 100
    
    def createNestedFibers(remaining: Int): Eru[Nothing, Int] = {
      if (remaining <= 0) Eru.succeed(0)
      else for {
        childFiber <- EruRuntime.fork(createNestedFibers(remaining - 1))
        childExit <- childFiber.await
        childResult <- Eru.fromExit(childExit).attempt.flatMap {
          case Result.Success(value) => Eru.succeed(value)
          case Result.Failure(_) => Eru.succeed(0) // Handle error case
        }
      } yield childResult + 1
    }
    
    val result = createNestedFibers(depth).unsafeRunSync()
    assertEquals(result, depth)
  }

  test("concurrent finalizer stress test with many fibers") {
    val fiberCount = 500
    val executionOrder = mutable.ListBuffer.empty[Int]
    val lock = new Object
    
    def createFiberWithFinalizer(id: Int): Eru[Nothing, String] = {
      Eru.succeed(s"fiber-$id").ensure {
        Eru.effect {
          lock.synchronized {
            executionOrder += id
          }
        }
      }
    }
    
    val effects = (1 to fiberCount).map(createFiberWithFinalizer).toList
    val results = EruRuntime.parSequence(effects).unsafeRunSync()
    
    assertEquals(results.length, fiberCount)
    assertEquals(executionOrder.length, fiberCount)
    
    // All fiber IDs should be present
    assertEquals(executionOrder.toSet, (1 to fiberCount).toSet)
  }

  test("memory stability with large numbers of short-lived fibers".flaky) {
    val rounds = 10
    val fibersPerRound = 100
    
    for (round <- 1 to rounds) {
      val fibers = (1 to fibersPerRound).map { i =>
        val fiberId = round * fibersPerRound + i
        EruRuntime.fork(Eru.succeed(fiberId))
      }.toList
      
      val allFibers = EruRuntime.parSequence(fibers).unsafeRunSync()
      val results = EruRuntime.parSequence(allFibers.map(_.await)).unsafeRunSync()
      
      assertEquals(results.length, fibersPerRound)
      
      val successCount = results.count {
        case Exit.Success(_) => true
        case _ => false
      }
      assertEquals(successCount, fibersPerRound)
    }
  }

  test("zipPar stress test with many concurrent pairs") {
    val pairCount = 100
    
    val pairs = (1 to pairCount).map { i =>
      val left = Eru.succeed(s"left-$i")
      val right = Eru.succeed(s"right-$i")
      EruRuntime.zipPar(left, right)
    }.toList
    
    val results = EruRuntime.parSequence(pairs).unsafeRunSync()
    
    assertEquals(results.length, pairCount)
    
    results.zipWithIndex.foreach { case ((left, right), i) =>
      val expectedI = i + 1
      assertEquals(left, s"left-$expectedI")
      assertEquals(right, s"right-$expectedI")
    }
  }

  test("race operations with many contestants") {
    val contestantCount = 50
    
    // Create effects with different delays, fastest should win
    val effects = (1 to contestantCount).map { i =>
      val delay = Duration.ofMillis(i.toLong) // 1ms, 2ms, 3ms, etc.
      EruRuntime.sleep(delay).map(_ => s"contestant-$i")
    }.toList
    
    val (winner, index) = EruRuntime.raceAll(effects).unsafeRunSync()
    
    // First contestant (shortest delay) should win
    assertEquals(winner, "contestant-1")
    assertEquals(index, 0)
  }

  test("complex concurrent composition with nested operations") {
    val outerCount = 20
    val innerCount = 10
    
    val outerOperations = (1 to outerCount).map { outer =>
      val innerOperations = (1 to innerCount).map { inner =>
        val computation = Eru.succeed(outer * 100 + inner)
        EruRuntime.fork(computation).flatMap(_.await).flatMap(Eru.fromExit)
      }.toList
      
      EruRuntime.parSequence(innerOperations)
    }.toList
    
    val allResults = EruRuntime.parSequence(outerOperations).unsafeRunSync()
    
    assertEquals(allResults.length, outerCount)
    allResults.foreach(innerResults => assertEquals(innerResults.length, innerCount))
    
    // Verify all expected values are present
    val flatResults = allResults.flatten
    assertEquals(flatResults.length, outerCount * innerCount)
    
    val expectedValues = for {
      outer <- 1 to outerCount
      inner <- 1 to innerCount
    } yield outer * 100 + inner
    
    assertEquals(flatResults.sorted, expectedValues.toList.sorted)
  }

  test("error handling stress with mixed success and failure scenarios") {
    val operationCount = 200
    
    def createMixedEffect(id: Int): Eru[String, Int] = {
      if (id % 10 == 0) Eru.fail(s"error-$id")      // 10% failure rate  
      else if (id % 23 == 0) Eru.effect(throw new RuntimeException(s"defect-$id")).mapError(_ => s"defect-$id") // ~4% defect rate
      else Eru.succeed(id)                          // ~86% success rate
    }
    
    val effects = (1 to operationCount).map(createMixedEffect).toList
    
    // Process each effect individually to capture all outcomes
    val fibers = effects.map(EruRuntime.fork)
    val allFibers = EruRuntime.parSequence(fibers).unsafeRunSync()
    val exits = EruRuntime.parSequence(allFibers.map(_.await)).unsafeRunSync()
    
    assertEquals(exits.length, operationCount)
    
    val successes = exits.collect { case Exit.Success(value) => value }
    val failures = exits.collect { case Exit.Failure(error) => error }
    val dies = exits.collect { case Exit.Die(_) => 1 }
    
    // Verify we got the expected distribution
    assert(successes.length > operationCount * 0.8) // At least 80% success
    assert(failures.length >= operationCount / 10)   // Around 10% failures  
    assert(dies.length > 0)                          // Some defects occurred
    
    assertEquals(successes.length + failures.length + dies.length, operationCount)
  }

  test("finalizer execution under high concurrent load") {
    val fiberCount = 300
    val finalizersPerFiber = 3
    val executionCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    
    def createFiberWithMultipleFinalizers(id: Int): Eru[Nothing, String] = {
      var effect: Eru[Nothing, String] = Eru.succeed(s"fiber-$id")
      
      for (_ <- 1 to finalizersPerFiber) {
        effect = effect.ensure {
          Eru.effect {
            executionCounter.incrementAndGet()
          }
        }
      }
      
      effect
    }
    
    val effects = (1 to fiberCount).map(createFiberWithMultipleFinalizers).toList
    val results = EruRuntime.parSequence(effects).unsafeRunSync()
    
    assertEquals(results.length, fiberCount)
    
    // All finalizers should have executed
    val expectedFinalizerCount = fiberCount * finalizersPerFiber
    assertEquals(executionCounter.get(), expectedFinalizerCount)
  }

  test("resource cleanup under concurrent stress with failures") {
    val operationCount = 150
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    
    def createResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect(resourceCounter.incrementAndGet()).mapError(_ => s"resource-error-$id")
        resource = s"resource-$id"
        result <- if (id % 7 == 0) Eru.fail(s"resource-$id failed")
                  else Eru.succeed(resource)
      } yield result
      
      resourceEffect.ensure {
        Eru.effect {
          cleanupCounter.incrementAndGet()
        }
      }
    }
    
    val effects = (1 to operationCount).map(createResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()
    
    // Should fail due to some operations failing
    result match {
      case Result.Failure(_) => // Expected
      case Result.Success(_) => fail("Expected failure due to some resources failing")
    }
    
    // But all resources should have been acquired and cleaned up
    assertEquals(resourceCounter.get(), operationCount)
    assertEquals(cleanupCounter.get(), operationCount)
  }
}