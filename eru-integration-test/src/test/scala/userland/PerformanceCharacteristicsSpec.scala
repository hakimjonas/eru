package userland

import java.util.concurrent.atomic.AtomicLong

import net.ghoula.eru.prelude.*

/** Performance characteristics validation for the Eru effect system.
  *
  * Tests that verify expected performance characteristics including stack safety, memory
  * efficiency, and execution speed under various load conditions.
  */
class PerformanceCharacteristicsSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  test("stack safety with deep flatMap chains") {
    // Use iterative approach instead of recursive to avoid stack overflow
    def deepChain(n: Int): Eru[Nothing, Int] = {
      (1 to n).foldLeft(Eru.succeed(0)) { (acc, _) =>
        acc.flatMap(current => Eru.succeed(current + 1))
      }
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
        acc.flatMap(n =>
          Eru.succeed {
            counter.incrementAndGet()
            n + i
          }
        )
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
    // Use iterative approach to build chain without stack overflow
    def errorChain(depth: Int): Eru[String, Int] = {
      (1 to depth).foldLeft(Eru.fail("deep error"): Eru[String, Int]) { (acc, i) =>
        Eru.succeed(i).flatMap(_ => acc)
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
