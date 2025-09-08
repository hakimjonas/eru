package net.ghoula.eru

import munit.FunSuite
import net.ghoula.eru.prelude.*
import scala.util.{Try, Success, Failure}
import java.time.Duration

/** The comprehensive public API specification for Eru 1.0.
  *
  * This specification serves a dual purpose:
  * 1. **Prove Correctness**: Provides test coverage for every public method in the unified API
  * 2. **Exemplify Ergonomics**: Written as living documentation that demonstrates "Radical Ergonomics"
  *
  * Every test case in this specification showcases the proper usage of Eru's unified API,
  * demonstrating how the single entry point (prelude) and universal runner (.run()) create
  * a seamless and intuitive developer experience.
  */
final class EruPublicApiSpec extends FunSuite {


  test("Eru.succeed creates successful effects") {
    val result = Eru.succeed(42).run()
    assertEquals(result, 42)
  }

  test("Eru.fail creates failed effects") {
    intercept[EruException[String]] {
      Eru.fail("error message").run()
    }
  }

  test("Eru.effect captures side effects safely") {
    var counter = 0
    val result = Eru.effect {
      counter += 1
      counter
    }.run()
    assertEquals(result, 1)
    assertEquals(counter, 1)
  }

  test("Eru.blocking handles blocking computations") {
    val result = Eru.blocking {
      Thread.sleep(1)
      "completed"
    }.run()
    assertEquals(result, "completed")
  }

  test("Eru.fromEither converts Either values") {
    val success = Eru.fromEither(Right(42)).run()
    assertEquals(success, 42)

    intercept[EruException[String]] {
      Eru.fromEither(Left("error")).run()
    }
  }

  test("Eru.fromTry converts Try values") {
    val success = Eru.fromTry(Success(42)).run()
    assertEquals(success, 42)

    intercept[RuntimeException] {
      Eru.fromTry(Failure(new RuntimeException("test"))).run()
    }
  }

  test("Eru.fromOption converts Option values") {
    val success = Eru.fromOption(Some(42), "none").run()
    assertEquals(success, 42)

    intercept[EruException[String]] {
      Eru.fromOption(None, "none").run()
    }
  }

  test("Eru.unit provides unit effect") {
    val result = Eru.unit.run()
    assertEquals(result, ())
  }


  test("map transforms successful values") {
    val result = Eru.succeed(21)
      .map(_ * 2)
      .run()
    assertEquals(result, 42)
  }

  test("flatMap chains effects") {
    val result = Eru.succeed(21)
      .flatMap(x => Eru.succeed(x * 2))
      .run()
    assertEquals(result, 42)
  }

  test("recover handles failures gracefully") {
    val result = Eru.fail("error")
      .recover { case "error" => "recovered" }
      .run()
    assertEquals(result, "recovered")
  }

  test("zip combines two effects") {
    val result = Eru.succeed(21)
      .zip(Eru.succeed(2))
      .map { case (a, b) => a * b }
      .run()
    assertEquals(result, 42)
  }


  test("Eru.ref creates mutable references") {
    val ref = Eru.ref(0).run()
    
    ref.set(42).run()
    val value = ref.get.run()
    assertEquals(value, 42)
  }

  test("Ref.update modifies values atomically") {
    val ref = Eru.ref(0).run()
    
    val newValue = ref.update(_ + 42).run()
    assertEquals(newValue, 42)
    
    val currentValue = ref.get.run()
    assertEquals(currentValue, 42)
  }

  test("Ref.modify atomically updates and returns auxiliary result") {
    val ref = Eru.ref(10).run()
    
    val oldValue = ref.modify(current => (current + 5, current)).run()
    assertEquals(oldValue, 10)
    
    val newValue = ref.get.run()
    assertEquals(newValue, 15)
  }

  test("Eru.deferred creates coordination primitives") {
    val deferred = Eru.deferred[String].run()
    
    val completed = deferred.complete("result").run()
    assert(completed, "First completion should succeed")
    
    val alreadyCompleted = deferred.complete("another").run()
    assert(!alreadyCompleted, "Second completion should fail")
    
    val result = deferred.await.run()
    assertEquals(result, "result")
  }

  test("Eru.semaphore controls resource access") {
    val semaphore = Eru.semaphore(2).run()
    
    val available1 = semaphore.permitsAvailable.run()
    assertEquals(available1, 2L)
    
    val acquired1 = semaphore.tryAcquire.run()
    assert(acquired1, "Should acquire permit when available")
    
    val acquired2 = semaphore.tryAcquire.run()
    assert(acquired2, "Should acquire second permit")
    
    val acquired3 = semaphore.tryAcquire.run()
    assert(!acquired3, "Should not acquire when no permits available")
    
    semaphore.release.run()
    val available2 = semaphore.permitsAvailable.run()
    assertEquals(available2, 1L)
  }

  test("Semaphore.withPermit safely manages resources") {
    val semaphore = Eru.semaphore(1).run()
    
    val result = semaphore.withPermit {
      Eru.succeed("work done")
    }.run()
    
    assertEquals(result, Some("work done"))
    
    val available = semaphore.permitsAvailable.run()
    assertEquals(available, 1L, "Permit should be released after use")
  }


  test("fork creates concurrent fibers") {
    val fiber = Eru.succeed("async work").fork.run()
    val result = fiber.await.run()
    assertEquals(result.toEither, Right("async work"))
  }

  test("zipPar runs effects in parallel") {
    val result = Eru.succeed(21)
      .zipPar(Eru.succeed(2))
      .map { case (a, b) => a * b }
      .run()
    assertEquals(result, 42)
  }

  test("race returns first completing effect") {
    val fast = Eru.succeed("fast")
    val slow = Eru.effect { Thread.sleep(100); "slow" }
    
    val result = fast.race(slow).run()
    assertEquals(result, Left("fast"))
  }


  test("ensure runs finalizers regardless of outcome") {
    var finalized = false
    
    val result = Try {
      Eru.succeed(42)
        .ensure(Eru.effect { finalized = true })
        .run()
    }
    
    assert(result.isSuccess)
    assertEquals(result.get, 42)
    assert(finalized, "Finalizer should have run")
  }

  test("ensure runs finalizers even on failure") {
    var finalized = false
    
    val result = Try {
      Eru.fail("error")
        .ensure(Eru.effect { finalized = true })
        .run()
    }
    
    assert(result.isFailure)
    assert(finalized, "Finalizer should run even on failure")
  }


  test("timeout fails slow effects") {
    val slow = Eru.effect { Thread.sleep(100); "result" }
    
    intercept[java.util.concurrent.TimeoutException] {
      slow.timeout(Duration.ofMillis(10)).run()
    }
  }

  test("timeoutTo provides fallback values") {
    val slow = Eru.effect { Thread.sleep(100); "result" }
    
    val result = slow.timeoutTo(Duration.ofMillis(10), "fallback").run()
    assertEquals(result, "fallback")
  }


  test("retryN retries failed effects") {
    var attempts = 0
    val flaky = Eru.effect {
      attempts += 1
      if (attempts < 3) throw new RuntimeException("fail")
      else "success"
    }
    
    val result = flaky.retryN(3).run()
    assertEquals(result, "success")
    assertEquals(attempts, 3)
  }

  test("retryWithBackoff implements exponential backoff") {
    var attempts = 0
    val flaky = Eru.effect {
      attempts += 1
      if (attempts < 2) throw new RuntimeException("fail")
      else "success"  
    }
    
    val result = flaky.retryWithBackoff(Duration.ofMillis(1), 2).run()
    assertEquals(result, "success")
    assertEquals(attempts, 2)
  }


  test("run() handles all effect types seamlessly") {
    assertEquals(Eru.succeed(42).run(), 42)
    
    assertEquals(Eru.effect("side effect").run(), "side effect")
    
    assertEquals(
      Eru.succeed(21).flatMap(x => Eru.succeed(x * 2)).run(),
      42
    )
    
    val fiber = Eru.succeed("concurrent").fork.run()
    assertEquals(fiber.await.run().toEither, Right("concurrent"))
    
    var cleaned = false
    assertEquals(
      Eru.succeed("resource").ensure(Eru.effect { cleaned = true }).run(),
      "resource"
    )
    assert(cleaned)
  }


  test("unified prelude provides single import") {
    
    val workflow = for {
      ref <- Eru.ref(0)
      deferred <- Eru.deferred[String]
      semaphore <- Eru.semaphore(1)
      _ <- ref.set(42)
      _ <- deferred.complete("done")
      acquired <- semaphore.tryAcquire
      value <- ref.get
      result <- deferred.await
    } yield (value, result, acquired)
    
    val (value, result, acquired) = workflow.run()
    assertEquals(value, 42)
    assertEquals(result, "done")
    assert(acquired)
  }

  test("error handling is intuitive and composable") {
    val robustWorkflow = Eru.effect {
      throw new RuntimeException("simulated failure")
    }.recover { 
      case _: RuntimeException => "recovered gracefully" 
    }.ensure {
      Eru.effect {}
    }
    
    val result = robustWorkflow.run()
    assertEquals(result, "recovered gracefully")
  }

  test("concurrent operations compose naturally") {
    val parallelWork = for {
      fiber1 <- Eru.succeed("work-1").fork
      fiber2 <- Eru.succeed("work-2").fork
      result1 <- fiber1.await
      result2 <- fiber2.await
    } yield (result1.toEither, result2.toEither)
    
    val (result1, result2) = parallelWork.run()
    assertEquals(result1, Right("work-1"))
    assertEquals(result2, Right("work-2"))
  }
}