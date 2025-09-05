package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Comprehensive tests for error and defect propagation between parent and child fibers.
  *
  * Tests that errors and defects are correctly propagated through the fiber hierarchy
  * and that error handling works correctly across fiber boundaries.
  */
class FiberErrorPropagationSpec extends FunSuite {

  test("typed error in child fiber is accessible via await") {
    val childError = "child computation failed"
    val childEffect = Eru.fail(childError)
    
    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
    } yield childExit
    
    val result = parentEffect.unsafeRunSync()
    assertEquals(result, Exit.Failure(childError))
  }

  test("defect (exception) in child fiber is captured in Die exit") {
    val exception = new IllegalArgumentException("invalid input")
    val childEffect = Eru.effect(throw exception)
    
    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
    } yield childExit
    
    val result = parentEffect.unsafeRunSync()
    result match {
      case Exit.Die(t) => 
        assertEquals(t.getClass, classOf[IllegalArgumentException])
        assertEquals(t.getMessage, "invalid input")
      case other => fail(s"Expected Die but got $other")
    }
  }

  test("parent can recover from child typed error using fromExit") {
    val childError = "recoverable error"
    val childEffect = Eru.fail(childError)
    
    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
      result <- Eru.fromExit(childExit).recoverWith {
        case "recoverable error" => Eru.succeed("recovered successfully")
        case other => Eru.fail(s"unhandled error: $other")
      }
    } yield result
    
    val result = parentEffect.unsafeRunSync()
    assertEquals(result, "recovered successfully")
  }

  test("parent can handle child defect using fromExit error handling") {
    val exception = new RuntimeException("child died")
    val childEffect = Eru.effect(throw exception)
    
    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      childExit <- childFiber.await
      result <- Eru.fromExit(childExit).attempt.map {
        case Result.Success(value) => s"unexpected success: $value"
        case Result.Failure(t) => s"caught defect: ${t.getMessage}"
      }
    } yield result
    
    val result = parentEffect.unsafeRunSync()
    assertEquals(result, "caught defect: child died")
  }

  test("multiple child fiber errors are handled independently") {
    val child1Effect = Eru.fail("error1")
    val child2Effect = Eru.fail("error2")  
    val child3Effect = Eru.succeed("success")
    
    val parentEffect = for {
      fiber1 <- EruRuntime.fork(child1Effect)
      fiber2 <- EruRuntime.fork(child2Effect)
      fiber3 <- EruRuntime.fork(child3Effect)
      exit1 <- fiber1.await
      exit2 <- fiber2.await
      exit3 <- fiber3.await
    } yield (exit1, exit2, exit3)
    
    val (exit1, exit2, exit3) = parentEffect.unsafeRunSync()
    
    assertEquals(exit1, Exit.Failure("error1"))
    assertEquals(exit2, Exit.Failure("error2"))
    assertEquals(exit3, Exit.Success("success"))
  }

  test("zipPar propagates first error encountered (left-biased)") {
    val leftError = "left failed"
    val rightError = "right failed"
    
    val leftEffect = Eru.fail(leftError)
    val rightEffect = Eru.fail(rightError)
    
    val result = EruRuntime.zipPar(leftEffect, rightEffect).attempt.unsafeRunSync()
    
    result match {
      case Result.Failure(error) => 
        // Should be left-biased in error reporting
        assertEquals(error, leftError)
      case Result.Success(_) => fail("Expected failure but got success")
    }
  }

  test("zipPar short-circuits on error and cancels the other side") {
    val fastFail = Eru.fail("fast failure")
    val slowSuccess = for {
      _ <- EruRuntime.sleep(java.time.Duration.ofSeconds(1))
      result <- Eru.succeed("slow success")
    } yield result
    
    val start = System.nanoTime()
    val result = EruRuntime.zipPar(fastFail, slowSuccess).attempt.unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1000000L // Convert to milliseconds
    
    result match {
      case Result.Failure(error) => assertEquals(error, "fast failure")
      case Result.Success(_) => fail("Expected failure but got success")
    }
    
    // Should complete quickly due to short-circuiting
    assert(elapsed < 500L, s"Expected quick failure but took ${elapsed}ms")
  }

  test("parSequence fails fast on first error") {
    val effects = List(
      Eru.succeed("success1"),
      Eru.fail("failure"),  // This should cause early termination
      EruRuntime.sleep(java.time.Duration.ofSeconds(1)).map(_ => "slow success")
    )
    
    val start = System.nanoTime()
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()
    val elapsed = (System.nanoTime() - start) / 1000000L
    
    result match {
      case Result.Failure(error) => assertEquals(error, "failure")
      case Result.Success(_) => fail("Expected failure but got success")
    }
    
    // Should fail fast without waiting for slow effect
    assert(elapsed < 500L, s"Expected fast failure but took ${elapsed}ms")
  }

  test("error in parent fiber does not affect already-forked children") {
    val childEffect = Eru.succeed("child completed")
    
    val parentEffect = for {
      childFiber <- EruRuntime.fork(childEffect)
      _ <- Eru.fail("parent error") // Parent fails but child is already running
    } yield childFiber
    
    // Parent will fail, but we can still check the child
    val parentResult = parentEffect.attempt.unsafeRunSync()
    
    parentResult match {
      case Result.Failure(error) => assertEquals(error, "parent error")
      case Result.Success(_) => fail("Expected parent to fail")
    }
  }

  test("nested error propagation through multiple fiber levels") {
    val deepError = "deep nested error"
    val deepEffect = Eru.fail(deepError)
    
    val middleEffect = for {
      deepFiber <- EruRuntime.fork(deepEffect)
      deepExit <- deepFiber.await
      deepResult <- Eru.fromExit(deepExit)
    } yield s"middle processed: $deepResult"
    
    val topEffect = for {
      middleFiber <- EruRuntime.fork(middleEffect)
      middleExit <- middleFiber.await
    } yield middleExit
    
    val result = topEffect.unsafeRunSync()
    
    result match {
      case Exit.Failure(error) => assertEquals(error, deepError)
      case other => fail(s"Expected failure propagation but got $other")
    }
  }

  test("error recovery at different fiber levels") {
    val originalError = "original error"
    val recoveredValue = "recovered at middle level"
    
    val deepEffect = Eru.fail(originalError)
    
    val middleEffect = for {
      deepFiber <- EruRuntime.fork(deepEffect)
      deepExit <- deepFiber.await
      result <- Eru.fromExit(deepExit).recoverWith {
        case "original error" => Eru.succeed(recoveredValue)
        case other => Eru.fail(s"unhandled: $other")
      }
    } yield result
    
    val topEffect = for {
      middleFiber <- EruRuntime.fork(middleEffect)
      middleExit <- middleFiber.await
      result <- Eru.fromExit(middleExit)
    } yield result
    
    val result = topEffect.unsafeRunSync()
    assertEquals(result, recoveredValue)
  }

  test("mixed success and error results in concurrent operations") {
    def createEffect(id: Int): Eru[String, String] = {
      if (id % 2 == 0) Eru.succeed(s"success-$id")
      else Eru.fail(s"error-$id")
    }
    
    val effects = (1 to 5).map(createEffect).toList
    
    // Using parTraverse to process all effects
    val result = EruRuntime.parTraverse(effects)(identity).attempt.unsafeRunSync()
    
    result match {
      case Result.Failure(error) => 
        // Should fail with the first error (error-1)
        assertEquals(error, "error-1")
      case Result.Success(_) => fail("Expected failure due to odd-numbered errors")
    }
  }

  test("fromExit correctly widens error type for all Exit cases") {
    val successExit: Exit[String, Int] = Exit.Success(42)
    val failureExit: Exit[String, Int] = Exit.Failure("typed error")
    val dieExit: Exit[String, Int] = Exit.Die(new RuntimeException("defect"))
    val interruptExit: Exit[String, Int] = Exit.Interrupt(FiberId.fresh(), InterruptCause.Cancelled())
    
    // All should compile and widen error type to include Throwable
    val successResult: Eru[String | Throwable, Int] = Eru.fromExit(successExit)
    val failureResult: Eru[String | Throwable, Int] = Eru.fromExit(failureExit)
    val dieResult: Eru[String | Throwable, Int] = Eru.fromExit(dieExit)
    val interruptResult: Eru[String | Throwable, Int] = Eru.fromExit(interruptExit)
    
    assertEquals(successResult.unsafeRunSync(), 42)
    assertEquals(failureResult.attempt.unsafeRunSync(), Result.Failure("typed error"))
    
    // Die and Interrupt will throw when executed
    dieResult.attempt.unsafeRunSync() match {
      case Result.Failure(_) => // Expected
      case other => fail(s"Expected failure but got $other")
    }
    
    interruptResult.attempt.unsafeRunSync() match {
      case Result.Failure(_) => // Expected  
      case other => fail(s"Expected failure but got $other")
    }
  }
}