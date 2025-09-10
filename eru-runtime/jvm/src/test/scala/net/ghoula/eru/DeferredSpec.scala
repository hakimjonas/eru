package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Test suite for Deferred concurrent primitive functionality.
  *
  * Validates all operations of the Deferred data type including construction, completion, and await
  * semantics. Deferred provides single-assignment variable semantics that enable safe coordination
  * between concurrent fibers, supporting common patterns like producer-consumer communication and
  * synchronization barriers.
  */
final class DeferredSpec extends TestWithRuntime {

  test("await blocks until completion and returns the value") {
    val d = Deferred.make[Int].unsafeRunSync()

    // Fork a fiber that completes the deferred after a short delay
    val completingFiber = runtime.fork {
      runtime.sleep(java.time.Duration.ofMillis(10)).flatMap { _ =>
        d.complete(42)
      }
    }.unsafeRunSync()

    // Await should block until completion
    val value = d.await.unsafeRunSync()
    assertEquals(value, 42)

    // Verify the completing fiber succeeded
    val completed = completingFiber.await.unsafeRunSync()
    assertEquals(completed, Exit.Success(true))
  }

  test("await returns immediately if already completed") {
    val d = Deferred.make[String].unsafeRunSync()
    val completed = d.complete("immediate").unsafeRunSync()
    assertEquals(completed, true)

    // Await should return immediately since already completed
    val value = d.await.unsafeRunSync()
    assertEquals(value, "immediate")
  }

  test("complete is idempotent and returns false on second call") {
    val d = Deferred.make[String].unsafeRunSync()
    val first = d.complete("ok").unsafeRunSync()
    val second = d.complete("again").unsafeRunSync()
    assertEquals(first, true)
    assertEquals(second, false)

    // Value should be from first completion
    val value = d.await.unsafeRunSync()
    assertEquals(value, "ok")
  }

  test("multiple fibers can await the same deferred") {
    val d = Deferred.make[Int].unsafeRunSync()

    // Fork multiple fibers that all await the same deferred
    val waitingFibers = (1 to 5).map { _ =>
      runtime.fork(d.await).unsafeRunSync()
    }.toList

    // Complete the deferred
    val completed = d.complete(99).unsafeRunSync()
    assertEquals(completed, true)

    // All fibers should receive the same value
    waitingFibers.foreach { fiber =>
      val exit = fiber.await.unsafeRunSync()
      assertEquals(exit, Exit.Success(99))
    }
  }
}
