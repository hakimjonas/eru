package net.ghoula.eru.fiber

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.*
import net.ghoula.eru.test.*

/** Tests for structured concurrency improvements.
  *
  * These tests are timing-sensitive: parent fibers sleep briefly so they complete before their
  * children, and trailing sleeps let child cleanups run before assertions. The root-fiber
  * auto-cleanup test cannot directly observe collection, but verifies that many short-lived fibers
  * complete without exhausting memory.
  */
class StructuredConcurrencySpec extends EruTestSuite {

  test("parent scope is properly propagated to child fibers") {
    val cleanupCount = new AtomicInteger(0)

    val parent = for {
      _ <- runtime.fork {
        for {
          _ <- runtime.fork {
            runtime.sleep(Duration.ofMillis(10)).ensure(Eru.effect(cleanupCount.incrementAndGet()))
          }
          _ <- runtime.fork {
            runtime.sleep(Duration.ofMillis(10)).ensure(Eru.effect(cleanupCount.incrementAndGet()))
          }
        } yield ()
      }
      _ <- runtime.sleep(Duration.ofMillis(5))
    } yield ()

    parent.unsafeRunSync()
    runtime.sleep(Duration.ofMillis(20)).unsafeRunSync()

    assert(cleanupCount.get() == 2, s"Expected 2 cleanups, got ${cleanupCount.get()}")
  }

  test("root fiber collection auto-cleans completed fibers") {
    val fiberEffects = (1 to 150).map { i =>
      runtime.fork(Eru.succeed(i))
    }

    val fibers = Eru.foreach(fiberEffects.toList)(identity).unsafeRunSync()

    val results = Eru.foreach(fibers)(_.await).unsafeRunSync()

    assert(results.forall {
      case Exit.Success(_) => true
      case _ => false
    })
  }

  test("race properly cancels loser with scope propagation") {
    val winnerCleanup = new AtomicInteger(0)
    val loserCleanup = new AtomicInteger(0)

    val fast = runtime
      .sleep(Duration.ofMillis(5))
      .ensure(Eru.effect(winnerCleanup.incrementAndGet()))
      .map(_ => "fast")

    val slow = runtime
      .sleep(Duration.ofMillis(50))
      .ensure(Eru.effect(loserCleanup.incrementAndGet()))
      .map(_ => "slow")

    val result = runtime.race(fast, slow).unsafeRunSync()

    result match {
      case Left("fast") =>
      case other => fail(s"Expected Left('fast'), got $other")
    }

    runtime.sleep(Duration.ofMillis(10)).unsafeRunSync()

    assert(winnerCleanup.get() == 1, s"Winner cleanup: ${winnerCleanup.get()}")
    assert(loserCleanup.get() == 1, s"Loser cleanup: ${loserCleanup.get()}")
  }

  test("nested fork maintains parent-child relationship across VT boundaries") {
    val executionOrder = scala.collection.mutable.ListBuffer.empty[String]

    val grandparent = for {
      parentFiber <- runtime.fork {
        for {
          childFiber <- runtime.fork {
            runtime
              .sleep(Duration.ofMillis(10))
              .ensure(Eru.effect(executionOrder += "child-cleanup"))
              .map(_ => "child")
          }
          _ <- runtime
            .sleep(Duration.ofMillis(5))
            .ensure(Eru.effect(executionOrder += "parent-cleanup"))
          childExit <- childFiber.await
        } yield childExit match {
          case Exit.Success(value) => value
          case _ => "failed"
        }
      }
      _ <- Eru.effect(executionOrder += "grandparent-continues")
      result <- parentFiber.await
    } yield result

    val finalResult = grandparent.unsafeRunSync()

    assert(finalResult == Exit.Success("child"))
    assert(executionOrder.contains("grandparent-continues"))
    assert(executionOrder.contains("parent-cleanup"))
    assert(executionOrder.contains("child-cleanup"))
  }

  test("interrupt propagates through scope hierarchy") {
    val cleanupCount = new AtomicInteger(0)

    val computation = for {
      fiber <- runtime.fork {
        for {
          child1 <- runtime.fork {
            runtime
              .sleep(Duration.ofMillis(100))
              .ensure(Eru.effect(cleanupCount.incrementAndGet()))
          }
          child2 <- runtime.fork {
            runtime
              .sleep(Duration.ofMillis(100))
              .ensure(Eru.effect(cleanupCount.incrementAndGet()))
          }
          _ <- runtime.sleep(Duration.ofMillis(100))
          _ <- child1.await
          _ <- child2.await
        } yield ()
      }
      _ <- runtime.sleep(Duration.ofMillis(10))
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      result <- fiber.await
    } yield result

    val exitResult = computation.unsafeRunSync()
    runtime.sleep(Duration.ofMillis(20)).unsafeRunSync()

    exitResult match {
      case Exit.Interrupt(_, _) =>
      case other => fail(s"Expected Interrupt, got $other")
    }

    assert(cleanupCount.get() >= 2, s"Expected at least 2 cleanups, got ${cleanupCount.get()}")
  }
}
