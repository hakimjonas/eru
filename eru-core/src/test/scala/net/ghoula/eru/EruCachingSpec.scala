package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

class EruCachingSpec extends FunSuite {

  test("cached executes effect only once and reuses result on success") {
    var executions = 0
    val effect = Eru.effect {
      executions += 1
      42
    }.cached

    // First execution
    val result1 = effect.unsafeRunSync()
    assertEquals(result1, 42)
    assertEquals(executions, 1)

    // Second execution should use cached result
    val result2 = effect.unsafeRunSync()
    assertEquals(result2, 42)
    assertEquals(executions, 1) // Still 1, not 2
  }

  test("cached preserves failure and doesn't cache across different instances") {
    var executions = 0
    def createFailingEffect = Eru
      .effect[Int] {
        executions += 1
        throw new RuntimeException("boom")
      }
      .cached

    val effect1 = createFailingEffect
    val effect2 = createFailingEffect

    // First effect fails
    intercept[RuntimeException] {
      effect1.unsafeRunSync()
    }
    assertEquals(executions, 1)

    // Same effect instance should use cached failure
    intercept[RuntimeException] {
      effect1.unsafeRunSync()
    }
    assertEquals(executions, 1) // Still 1

    // Different effect instance should execute again
    intercept[RuntimeException] {
      effect2.unsafeRunSync()
    }
    assertEquals(executions, 2) // Now 2
  }

  test("cached works with typed failures") {
    var executions = 0
    val effect = Eru.effect {
      executions += 1
      if (executions == 1) throw new RuntimeException("first")
      42
    }.attempt.flatMap {
      case Result.Success(value) => Eru.succeed(value)
      case Result.Failure(_) => Eru.fail("typed error")
    }.cached

    // First execution
    val ex = intercept[EruException[String]] {
      effect.unsafeRunSync()
    }
    assertEquals(ex.error, "typed error")
    assertEquals(executions, 1)

    // Second execution should use cached failure
    val ex2 = intercept[EruException[String]] {
      effect.unsafeRunSync()
    }
    assertEquals(ex2.error, "typed error")
    assertEquals(executions, 1) // Still cached
  }

  test("cached works with complex effect chains") {
    var counter = 0
    val effect = Eru
      .succeed(10)
      .map(_ + 5)
      .flatMap(x =>
        Eru.effect {
          counter += 1
          x * 2
        }
      )
      .map(_ + 1)
      .cached

    val result1 = effect.unsafeRunSync()
    assertEquals(result1, 31) // (10 + 5) * 2 + 1
    assertEquals(counter, 1)

    val result2 = effect.unsafeRunSync()
    assertEquals(result2, 31)
    assertEquals(counter, 1) // Should not execute the effect block again
  }

  test("cached interacts properly with ensure") {
    var effectRuns = 0
    var finalizerRuns = 0

    val effect = Eru.effect {
      effectRuns += 1
      "cached-with-ensure"
    }.ensure(Eru.effect {
      finalizerRuns += 1
    }).cached

    val result1 = effect.unsafeRunSync()
    assertEquals(result1, "cached-with-ensure")
    assertEquals(effectRuns, 1)
    assertEquals(finalizerRuns, 1)

    // Cached execution - note: finalizer won't run again since we cache the result
    val result2 = effect.unsafeRunSync()
    assertEquals(result2, "cached-with-ensure")
    assertEquals(effectRuns, 1) // Cached
    assertEquals(finalizerRuns, 1) // Finalizer was part of the original execution
  }
}
