package net.ghoula.eru

import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

final class KeyedSemaphoreSpec extends EruTestSuite {

  test("withPermit for same key serializes access") {
    val counter = new AtomicInteger(0)
    val maxConcurrent = new AtomicInteger(0)

    val result = (for {
      ks <- KeyedSemaphore.make[String](1)
      fibers <- Eru.foreach(1 to 10) { _ =>
        ks.withPermit("a") {
          Eru.effectTotal {
            val c = counter.incrementAndGet()
            maxConcurrent.updateAndGet(m => math.max(m, c))
            Thread.sleep(5)
            counter.decrementAndGet()
          }
        }.fork
      }
      _ <- Eru.foreachDiscard(fibers)(_.await)
    } yield maxConcurrent.get()).unsafeRunSync()

    assertEquals(result, 1, "Only one fiber should hold the permit at a time")
  }

  test("withPermit for different keys runs concurrently") {
    val counter = new AtomicInteger(0)
    val maxConcurrent = new AtomicInteger(0)

    val result = (for {
      ks <- KeyedSemaphore.make[String](1)
      fibers <- Eru.foreach(1 to 5) { i =>
        ks.withPermit(s"key-$i") {
          Eru.effectTotal {
            val c = counter.incrementAndGet()
            maxConcurrent.updateAndGet(m => math.max(m, c))
            Thread.sleep(50)
            counter.decrementAndGet()
          }
        }.fork
      }
      _ <- Eru.foreachDiscard(fibers)(_.await)
    } yield maxConcurrent.get()).unsafeRunSync()

    assert(result > 1, s"Different keys should run concurrently, got max=$result")
  }

  test("permitsAvailable returns correct count") {
    val result = (for {
      ks <- KeyedSemaphore.make[String](3)
      // Unaccessed key should report full permits
      p1 <- ks.permitsAvailable("unaccessed")
      // Acquire one permit
      _ <- ks.acquire("a")
      p2 <- ks.permitsAvailable("a")
      _ <- ks.release("a")
      p3 <- ks.permitsAvailable("a")
    } yield (p1, p2, p3)).unsafeRunSync()
    assertEquals(result, (3L, 2L, 3L))
  }

  test("activeKeys tracks accessed keys") {
    val result = (for {
      ks <- KeyedSemaphore.make[String](1)
      k0 <- ks.activeKeys
      _ <- ks.acquire("a")
      _ <- ks.release("a")
      _ <- ks.acquire("b")
      _ <- ks.release("b")
      k2 <- ks.activeKeys
    } yield (k0, k2)).unsafeRunSync()
    assertEquals(result._1, Set.empty[String])
    assertEquals(result._2, Set("a", "b"))
  }

  test("multiple permits per key") {
    val counter = new AtomicInteger(0)
    val maxConcurrent = new AtomicInteger(0)

    val result = (for {
      ks <- KeyedSemaphore.make[String](3)
      fibers <- Eru.foreach(1 to 6) { _ =>
        ks.withPermit("shared") {
          Eru.effectTotal {
            val c = counter.incrementAndGet()
            maxConcurrent.updateAndGet(m => math.max(m, c))
            Thread.sleep(30)
            counter.decrementAndGet()
          }
        }.fork
      }
      _ <- Eru.foreachDiscard(fibers)(_.await)
    } yield maxConcurrent.get()).unsafeRunSync()

    assert(result <= 3, s"Max concurrent should be <= 3, got $result")
    assert(result >= 2, s"With 3 permits and 6 tasks, should see concurrency >= 2, got $result")
  }
}
