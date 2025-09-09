package userland

import munit.FunSuite

import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.prelude.*

/** Performance characteristics validation for the Eru effect system.
  *
  * Tests that verify expected performance characteristics including stack safety,
  * memory efficiency, and execution speed under various load conditions. These tests
  * ensure that the effect system maintains its performance guarantees and helps
  * detect performance regressions across different usage patterns.
  */
class PerformanceCharacteristicsSpec extends FunSuite {

  test("stack safety with deep flatMap chains") {
    def deepChain(n: Int, acc: Int = 0): Eru[Nothing, Int] = {
      if (n <= 0) Eru.succeed(acc)
      else Eru.succeed(acc + 1).flatMap(_ => deepChain(n - 1, acc + 1))
    }

    val result = deepChain(50000).runExit()
    
    result match {
      case Exit.Success(value) => assertEquals(value, 50000)
      case _ => fail("Stack safety test should succeed")
    }
  }

  test("stack safety with deep map chains") {
    def deepMap(n: Int): Eru[Nothing, Int] = {
      val initial = Eru.succeed(0)
      (1 to n).foldLeft(initial)((acc, _) => acc.map(_ + 1))
    }

    val result = deepMap(100000).runExit()
    
    result match {
      case Exit.Success(value) => assertEquals(value, 100000)
      case _ => fail("Deep map chain should succeed")
    }
  }

  test("memory efficiency with effect construction and disposal") {
    val iterations = 10000
    val counter = new AtomicLong(0)

    def createEffectChain(): Eru[Nothing, Long] = {
      (1 to 100).foldLeft(Eru.succeed(0L)) { (acc, i) =>
        acc.flatMap(n => Eru.succeed {
          counter.incrementAndGet()
          n + i
        })
      }
    }

    // Run multiple iterations to test memory usage patterns
    val results = (1 to iterations).map { _ =>
      createEffectChain().runExit()
    }

    val successCount = results.count {
      case Exit.Success(_) => true
      case _ => false
    }

    assertEquals(successCount, iterations)
    assert(counter.get() == iterations * 100L)
  }

  test("execution speed with parallel operations") {
    val startTime = System.nanoTime()
    
    val parallelOps = (1 to 4).map { i =>
      Eru.succeed(i * 2)
    }

    // Use zipPar for parallel operations
    val program = parallelOps.head.zipPar(parallelOps(1)).map { case (a, b) => a + b }
    
    val result = program.runExit()
    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000

    result match {
      case Exit.Success(_) => 
        // Should complete reasonably quickly due to parallelism
        assert(durationMs < 1000) // Less than 1 second for parallel execution
      case _ => fail("Parallel execution should succeed")
    }
  }

  test("resource cleanup efficiency with many finalizers") {
    val cleanupCount = new AtomicLong(0)
    val resourceCount = 1000

    def createResourceWithCleanup(id: Int): Eru[Nothing, Int] = {
      Eru.succeed(id).ensure {
        Eru.effect { cleanupCount.incrementAndGet() }
      }
    }

    val program = (1 to resourceCount).foldLeft(Eru.succeed(0)) { (acc, i) =>
      acc.flatMap(total => createResourceWithCleanup(i).map(total + _))
    }

    val result = program.runExit()
    
    result match {
      case Exit.Success(total) => 
        assertEquals(total, (1 to resourceCount).sum)
        assertEquals(cleanupCount.get(), resourceCount.toLong)
      case _ => fail("Resource cleanup test should succeed")
    }
  }

  test("error handling efficiency with deep error chains") {
    def errorChain(depth: Int): Eru[String, Int] = {
      if (depth <= 0) {
        Eru.fail("deep error")
      } else {
        Eru.succeed(depth).flatMap(_ => errorChain(depth - 1))
      }
    }

    val program = errorChain(10000).orElse(Eru.succeed(42))
    val result = program.runExit()
    
    result match {
      case Exit.Success(42) => () // Expected fallback value
      case _ => fail("Error handling should recover with fallback")
    }
  }
}