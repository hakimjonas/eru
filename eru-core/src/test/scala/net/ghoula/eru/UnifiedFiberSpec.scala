package net.ghoula.eru

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.CorePrelude.*

/** Test suite for UnifiedFiber implementation and state transitions.
  *
  * Validates both completed and active fiber states, including creation, state transitions,
  * await/interrupt operations, and thread coordination. These tests ensure UnifiedFiber correctly
  * implements the Fiber interface for both synchronous (completed) and asynchronous (active)
  * execution models.
  */
class UnifiedFiberSpec extends munit.FunSuite {

  test("UnifiedFiber.completed creates fiber with Completed state") {
    val id = FiberId.fresh()
    val exit = Exit.Success(42)
    val fiber = UnifiedFiber.completed(id, exit)

    assertEquals(fiber.id, id)
    fiber.currentState match {
      case UnifiedFiberState.Completed(e) => assertEquals(e, exit)
      case _ => fail("Expected Completed state")
    }
  }

  test("UnifiedFiber.active creates fiber with Active state") {
    val id = FiberId.fresh()
    val fiber = UnifiedFiber.active[String, Int](id)

    assertEquals(fiber.id, id)
    fiber.currentState match {
      case UnifiedFiberState.Active(latch, exitRef, threadRef, _, _) =>
        assertEquals(latch.getCount(), 1L, "Latch should start at 1")
        assert(Option(exitRef.get()).isEmpty, "Exit ref should be unset")
        assertEquals(threadRef.get(), None, "Thread ref should be None")
      case _ => fail("Expected Active state")
    }
  }

  test("UnifiedFiber.await on completed fiber returns immediately") {
    val id = FiberId.fresh()
    val exit = Exit.Success(42)
    val fiber = UnifiedFiber.completed(id, exit)

    val result = fiber.await.unsafeRunSync()
    assertEquals(result, exit)
  }

  test("UnifiedFiber.interrupt on completed fiber is no-op") {
    val id = FiberId.fresh()
    val exit = Exit.Success(42)
    val fiber = UnifiedFiber.completed(id, exit)

    val result = fiber.interrupt(InterruptCause.Cancelled()).unsafeRunSync()
    assertEquals(result, ())
  }

  test("UnifiedFiber.complete transitions active fiber to completed") {
    val id = FiberId.fresh()
    val fiber = UnifiedFiber.active[String, Int](id)
    val exit = Exit.Success(42)

    // Complete the fiber
    UnifiedFiber.complete(fiber, exit)

    // Verify state change
    fiber.currentState match {
      case UnifiedFiberState.Active(latch, exitRef, _, _, _) =>
        assertEquals(latch.getCount(), 0L, "Latch should be released")
        assertEquals(exitRef.get(), exit, "Exit should be set")
      case _ => fail("Should still be Active state (state is not mutated)")
    }
  }

  test("UnifiedFiber.setThread updates thread reference for active fiber") {
    val id = FiberId.fresh()
    val fiber = UnifiedFiber.active[String, Int](id)
    val thread = Thread.currentThread()

    // Set the thread
    UnifiedFiber.setThread(fiber, thread)

    // Verify thread was set
    fiber.currentState match {
      case UnifiedFiberState.Active(_, _, threadRef, _, _) =>
        assertEquals(threadRef.get(), Some(thread))
      case _ => fail("Expected Active state")
    }
  }

  test("UnifiedFiber.complete on already completed fiber is no-op") {
    val id = FiberId.fresh()
    val exit1 = Exit.Success(42)
    val fiber = UnifiedFiber.completed(id, exit1)
    val exit2 = Exit.Success(24)

    // Try to complete an already completed fiber
    UnifiedFiber.complete(fiber, exit2)

    // State should remain unchanged
    fiber.currentState match {
      case UnifiedFiberState.Completed(e) => assertEquals(e, exit1)
      case _ => fail("Expected Completed state")
    }
  }

  test("UnifiedFiber.setThread on completed fiber is no-op") {
    val id = FiberId.fresh()
    val exit = Exit.Success(42)
    val fiber = UnifiedFiber.completed(id, exit)
    val thread = Thread.currentThread()

    // Try to set thread on completed fiber
    UnifiedFiber.setThread(fiber, thread)

    // State should remain unchanged
    fiber.currentState match {
      case UnifiedFiberState.Completed(e) => assertEquals(e, exit)
      case _ => fail("Expected Completed state")
    }
  }

  test("UnifiedFiber handles Exit.Failure correctly") {
    val id = FiberId.fresh()
    val error = "test error"
    val exit = Exit.Failure(error)
    val fiber = UnifiedFiber.completed(id, exit)

    val result = fiber.await.unsafeRunSync()
    assertEquals(result, exit)
  }

  test("UnifiedFiber handles Exit.Die correctly") {
    val id = FiberId.fresh()
    val throwable = new RuntimeException("boom")
    val exit = Exit.Die(throwable)
    val fiber = UnifiedFiber.completed(id, exit)

    val result = fiber.await.unsafeRunSync()
    assertEquals(result, exit)
  }

  test("UnifiedFiber handles Exit.Interrupt correctly") {
    val id = FiberId.fresh()
    val cause = InterruptCause.Timeout(java.time.Duration.ofSeconds(30))
    val exit = Exit.Interrupt(FiberId.fresh(), cause)
    val fiber = UnifiedFiber.completed(id, exit)

    val result = fiber.await.unsafeRunSync()
    assertEquals(result, exit)
  }

  test("UnifiedFiberState.Active stores coordination primitives correctly") {
    val id = FiberId.fresh()
    val latch = new CountDownLatch(1)
    val exitRef = new AtomicReference[Exit[String, Int]]()
    val threadRef = new AtomicReference[Option[Thread]](None)
    val observerRef = new AtomicReference[Option[EruObserver]](None)

    val state = UnifiedFiberState.Active(latch, exitRef, threadRef, observerRef, id)

    state match {
      case UnifiedFiberState.Active(l, e, t, o, fid) =>
        assert(l.eq(latch), "Latch should be the same reference")
        assert(e.eq(exitRef), "Exit ref should be the same reference")
        assert(t.eq(threadRef), "Thread ref should be the same reference")
        assert(o.eq(observerRef), "Observer ref should be the same reference")
        assertEquals(fid, id, "Fiber ID should match")
      case _ => fail("Expected Active state")
    }
  }

  test("Multiple complete calls on active fiber - last one wins") {
    val id = FiberId.fresh()
    val fiber = UnifiedFiber.active[String, Int](id)
    val exit1 = Exit.Success(42)
    val exit2 = Exit.Success(99)

    // Complete multiple times
    UnifiedFiber.complete(fiber, exit1)
    UnifiedFiber.complete(fiber, exit2) // Different value

    // Last completion wins (not thread-safe, but that's the current implementation)
    fiber.currentState match {
      case UnifiedFiberState.Active(latch, exitRef, _, _, _) =>
        assertEquals(exitRef.get(), exit2, "Last exit should be set")
        assertEquals(latch.getCount(), 0L, "Latch should be released")
      case _ => fail("Expected Active state")
    }
  }
}
