package net.ghoula.eru

import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** JVM-only Promise tests that require real concurrency via Future. */
class PromiseConcurrentSpec extends EruTestSuite {

  test("promise await blocks until completion") {
    val promise = Eru.promise[String, String].unsafeRunSync()
    val latch = new CountDownLatch(1)

    val awaitFuture = {
      import scala.concurrent.{Future, ExecutionContext}
      implicit val ec: ExecutionContext = ExecutionContext.global
      Future {
        latch.countDown()
        promise.await.eru.unsafeRunSync()
      }
    }

    assert(latch.await(1, TimeUnit.SECONDS), "Await should start within timeout")

    promise.succeed("completed").eru.unsafeRunSync()

    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    val result = Await.result(awaitFuture, Duration(1, TimeUnit.SECONDS))
    assertEquals(result, "completed")
  }

  test("promise handles completion during await") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)

    import scala.concurrent.{Future, ExecutionContext}
    implicit val ec: ExecutionContext = ExecutionContext.global

    val awaitFuture = Future {
      latch1.countDown()
      promise.await.eru.unsafeRunSync()
    }

    val completeFuture = Future {
      latch1.await(1, TimeUnit.SECONDS)
      promise.succeed(555).eru.unsafeRunSync()
      latch2.countDown()
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    latch2.await(2, TimeUnit.SECONDS)
    val result = Await.result(awaitFuture, Duration(2, TimeUnit.SECONDS))
    Await.result(completeFuture, Duration(1, TimeUnit.SECONDS))

    assertEquals(result, 555)
  }
}
