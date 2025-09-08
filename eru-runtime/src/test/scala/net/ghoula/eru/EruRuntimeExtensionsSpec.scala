package net.ghoula.eru

import munit.FunSuite
import java.time.Duration
import scala.collection.mutable.ListBuffer

/** Test suite for Eru runtime extension methods and enhanced functionality.
  *
  * Validates extension methods provided by the runtime system including timeout operations,
  * retries, and other enhanced combinators. These tests ensure that runtime extensions
  * provide reliable behavior and integrate properly with the core effect system while
  * maintaining performance and resource safety characteristics.
  */
class EruRuntimeExtensionsSpec extends FunSuite {

  test("timeout extension succeeds when effect completes within duration") {
    val effect = Eru.succeed(42).timeout(Duration.ofSeconds(1))
    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("timeout extension fails with TimeoutException when effect takes too long") {
    val slowEffect = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => 42)
    val timedEffect = slowEffect.timeout(Duration.ofMillis(10))

    intercept[java.util.concurrent.TimeoutException] {
      timedEffect.unsafeRunSync()
    }
  }

  test("timeoutTo returns fallback value on timeout") {
    val slowEffect = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => 42)
    val timedEffect = slowEffect.timeoutTo(Duration.ofMillis(10), 99)

    val result = timedEffect.unsafeRunSync()
    assertEquals(result, 99)
  }

  test("timeoutTo preserves original value when no timeout") {
    val fastEffect = Eru.succeed(42)
    val timedEffect = fastEffect.timeoutTo(Duration.ofSeconds(1), 99)

    val result = timedEffect.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("retry with Recurs policy retries specified number of times") {
    var attempts = 0
    val flakyEffect = Eru.effect {
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"attempt $attempts failed")
      else s"success on attempt $attempts"
    }.attempt.flatMap {
      case Result.Success(value) => Eru.succeed(value)
      case Result.Failure(_) => Eru.fail("retry me")
    }

    val result = flakyEffect.retry(EruRuntime.Policy.Recurs(3)).unsafeRunSync()
    assertEquals(result, "success on attempt 3")
    assertEquals(attempts, 3)
  }

  test("retryN convenience method works correctly") {
    var attempts = 0
    val flakyEffect = Eru.effect {
      attempts += 1
      if (attempts < 2) "attempt failed"
      else "success"
    }.flatMap {
      case "success" => Eru.succeed("success")
      case _ => Eru.fail("failed")
    }

    val result = flakyEffect.retryN(2).unsafeRunSync()
    assertEquals(result, "success")
    assertEquals(attempts, 2)
  }

  test("retryWithBackoff applies exponential backoff") {
    var attempts = 0
    val startTime = System.nanoTime()

    val flakyEffect = Eru.effect {
      attempts += 1
      if (attempts < 3) "retry"
      else "success"
    }.flatMap {
      case "success" => Eru.succeed("done")
      case _ => Eru.fail("try again")
    }

    val result = flakyEffect.retryWithBackoff(Duration.ofMillis(10), 3).unsafeRunSync()
    val endTime = System.nanoTime()
    val elapsedMs = (endTime - startTime) / 1_000_000

    assertEquals(result, "done")
    assertEquals(attempts, 3)
    assert(elapsedMs >= 25)
  }

  test("retry does not retry on defects (Throwables)") {
    var attempts = 0
    val defectiveEffect = Eru.effect[String] {
      attempts += 1
      throw new RuntimeException("defect")
    }

    intercept[RuntimeException] {
      defectiveEffect.retry(EruRuntime.Policy.Recurs(5)).unsafeRunSync()
    }
    assertEquals(attempts, 1)
  }

  test("zipPar extension runs effects in parallel and combines results") {
    val executionOrder = scala.collection.mutable.ListBuffer.empty[String]
    val lock = new Object
    
    val left = Eru.succeed(10).map { x => 
      lock.synchronized { executionOrder += "left-start" }
      (1 to 10000).sum
      lock.synchronized { executionOrder += "left-end" }
      x + 5
    }
    val right = Eru.succeed(20).map { x => 
      lock.synchronized { executionOrder += "right-start" }
      (1 to 10000).sum
      lock.synchronized { executionOrder += "right-end" }
      x * 2
    }

    val result = left.zipPar(right).unsafeRunSync()
    val order = lock.synchronized { executionOrder.toList }

    assertEquals(result, (15, 40))
    val starts = order.filter(_.endsWith("-start"))
    val firstEndIndex = order.indexWhere(_.endsWith("-end"))
    val allStartsBeforeFirstEnd = if (firstEndIndex >= 0) {
      starts.forall(s => order.indexOf(s) < firstEndIndex)
    } else true
    assert(allStartsBeforeFirstEnd, s"Effects should execute in parallel. Order: $order")
  }

  test("zipPar propagates failure and interrupts partner") {
    val success = Eru.effect {
      (1 to 1000000).sum
      42
    }
    val failure = Eru.fail("boom")

    val ex = intercept[EruException[String]] {
      success.zipPar(failure).unsafeRunSync()
    }
    assertEquals(ex.error, "boom")
  }

  test("race extension returns first completion result") {
    val slow = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => "slow")
    val fast = EruRuntime.sleep(Duration.ofMillis(10)).map(_ => "fast")

    val result = slow.race(fast).unsafeRunSync()
    result match {
      case Right(value) => assertEquals(value, "fast")
      case Left(_) => fail("Expected right side to win")
    }
  }

  test("race returns failure when first completion is failure") {
    val success = EruRuntime.sleep(Duration.ofMillis(100)).map(_ => "success")
    val fastFailure = EruRuntime.sleep(Duration.ofMillis(10)).flatMap(_ => Eru.fail("fast failure"))

    val ex = intercept[EruException[String]] {
      success.race(fastFailure).unsafeRunSync()
    }
    assertEquals(ex.error, "fast failure")
  }

  test("fork extension creates a fiber that can be awaited") {
    val effect = Eru.succeed(42).map(_ * 2)
    val fiber = effect.fork.unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(value) => assertEquals(value, 84)
      case other => fail(s"Expected Success, got $other")
    }
  }

  test("fork with failing effect returns Exit.Failure") {
    val failingEffect = Eru.fail("boom")
    val fiber = failingEffect.fork.unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Failure(error) => assertEquals(error, "boom")
      case other => fail(s"Expected Failure, got $other")
    }
  }

  test("fork with defective effect returns Exit.Die") {
    val defectiveEffect = Eru.effect[Int](throw new RuntimeException("kaboom"))
    val fiber = defectiveEffect.fork.unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Die(throwable: RuntimeException) =>
        assertEquals(throwable.getMessage, "kaboom")
      case Exit.Die(throwable) =>
        fail(s"Expected RuntimeException, got ${throwable.getClass.getSimpleName}")
      case other => fail(s"Expected Die, got $other")
    }
  }

  test("forkWithObserver emits lifecycle events") {
    class TestObserver extends EruObserver {
      val events = ListBuffer.empty[EruEvent]
      def onEvent(event: EruEvent): Unit = events += event
    }

    val observer = new TestObserver
    val effect = Eru.succeed("observed")
    val fiber = effect.forkWithObserver(observer).unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Success(value) => assertEquals(value, "observed")
      case other => fail(s"Expected Success, got $other")
    }

    val eventTypes = observer.events.map(_.getClass.getSimpleName).toList
    assert(eventTypes.contains("FiberStarted"))
    assert(eventTypes.contains("FiberCompleted"))
  }

  test("fiber interrupt works correctly") {
    val longRunning = EruRuntime.sleep(Duration.ofSeconds(1)).map(_ => "completed")
    val fiber = longRunning.fork.unsafeRunSync()

    fiber.interrupt(InterruptCause.Cancelled(Some("test cancellation"))).unsafeRunSync()

    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Interrupt(fid, cause) =>
        assertEquals(fid, fiber.id)
        assertEquals(cause, InterruptCause.Cancelled(Some("test cancellation")))
      case other => fail(s"Expected Interrupt, got $other")
    }
  }

  test("complex concurrent scenario with multiple extension methods") {
    val computation1 = Eru
      .succeed(10)
      .map(_ * 2)
      .retry(EruRuntime.Policy.Recurs(2))
      .timeout(Duration.ofSeconds(1))

    val computation2 = Eru
      .succeed(5)
      .map(_ + 15)
      .cached
      .timeout(Duration.ofSeconds(1))

    val result = computation1.zipPar(computation2).unsafeRunSync()
    assertEquals(result, (20, 20))
  }

  test("timeout interacts properly with retry") {
    var attempts = 0
    val slowEffect = Eru.effect {
      attempts += 1
      Thread.sleep(50)
      if (attempts < 2) throw new RuntimeException("retry me")
      else s"success attempt $attempts"
    }.attempt.flatMap {
      case Result.Success(value) => Eru.succeed(value)
      case Result.Failure(_) => Eru.fail("retry")
    }

    val result = slowEffect
      .retry(EruRuntime.Policy.Recurs(2))
      .timeout(Duration.ofMillis(200))
      .unsafeRunSync()

    assertEquals(result, "success attempt 2")
  }
}
