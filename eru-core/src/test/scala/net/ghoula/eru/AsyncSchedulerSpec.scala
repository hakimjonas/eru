package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Test suite for AsyncScheduler traits and companion object.
  *
  * Validates the AsyncScheduler and AsyncFiber trait contracts, ensuring proper interface design
  * for asynchronous fiber execution. Since these are primarily interfaces used by runtime
  * implementations, tests focus on contract verification and basic functionality.
  */
class AsyncSchedulerSpec extends munit.FunSuite {

  /** Mock implementation of AsyncScheduler for testing */
  private class MockAsyncScheduler extends AsyncScheduler {
    var scheduledComputations: List[(Eru[?, ?], Option[EruObserver])] = Nil

    def scheduleAsync[E, A](
      computation: Eru[E, A],
      observer: Option[EruObserver]
    ): AsyncFiber[E, A] = {
      scheduledComputations = (computation, observer) :: scheduledComputations
      new MockAsyncFiber[E, A]()
    }

    def executeWithFinalizers[E, A](
      computation: Eru[E, A]
    ): (Exit[E, A], List[() => Eru[Nothing, Unit]]) = {
      // Simple mock implementation
      val result =
        try {
          val value = computation.unsafeRunSync()
          (Exit.Success(value), Nil)
        } catch {
          case ex: EruException[?] =>
            // In a real implementation, we would need proper type handling
            // For testing purposes, we'll create a Die exit instead
            (Exit.Die(ex), Nil)
          case t: Throwable =>
            (Exit.Die(t), Nil)
        }
      result
    }
  }

  /** Mock implementation of AsyncFiber for testing */
  private class MockAsyncFiber[E, A] extends AsyncFiber[E, A] {
    var callbacks: List[EruFiber[E, A] => Unit] = Nil
    var completed: Boolean = false
    var completedFiber: Option[EruFiber[E, A]] = None
    val id: FiberId = FiberId.fresh()

    def onComplete(callback: EruFiber[E, A] => Unit): Unit = {
      if (completed) {
        completedFiber.foreach(callback)
      } else {
        callbacks = callback :: callbacks
      }
    }

    def isCompleted: Boolean = completed

    def getCompleted: Option[EruFiber[E, A]] = completedFiber

    def await: Eru[Nothing, Exit[E, A]] = {
      completedFiber match {
        case Some(fiber) => Eru.succeed(fiber.exit)
        case None => Eru.succeed(Exit.Die(new IllegalStateException("Fiber not completed")))
      }
    }

    def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = Eru.unit

    // Test helper to complete the fiber
    def complete(exit: Exit[E, A]): Unit = {
      if (!completed) {
        completed = true
        completedFiber = Some(EruFiber.completed(exit, Nil))
        callbacks.foreach(_(completedFiber.get))
        callbacks = Nil
      }
    }
  }

  test("AsyncScheduler.scheduleAsync should accept computation and observer") {
    val scheduler = new MockAsyncScheduler()
    val computation = succeed(42)
    val observer = new EruObserver {
      def onEvent(event: EruEvent): Unit = ()
    }

    val fiber = scheduler.scheduleAsync(computation, Some(observer))

    // Verify the computation was scheduled
    assertEquals(scheduler.scheduledComputations.length, 1)
    assertEquals(scheduler.scheduledComputations.head._1, computation)
    assertEquals(scheduler.scheduledComputations.head._2, Some(observer))
    assert(Option(fiber).isDefined, "Should return a fiber")
  }

  test("AsyncScheduler.scheduleAsync should work with no observer") {
    val scheduler = new MockAsyncScheduler()
    val computation = succeed("test")

    val fiber = scheduler.scheduleAsync(computation, None)

    assertEquals(scheduler.scheduledComputations.length, 1)
    assertEquals(scheduler.scheduledComputations.head._2, None)
    assert(Option(fiber).isDefined, "Should return a fiber")
  }

  test("AsyncScheduler.executeWithFinalizers should run successful computation") {
    val scheduler = new MockAsyncScheduler()
    val computation = succeed(42)

    val (exit, finalizers) = scheduler.executeWithFinalizers(computation)

    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case _ => fail("Expected successful exit")
    }
    assertEquals(finalizers, Nil)
  }

  test("AsyncScheduler.executeWithFinalizers should handle exceptions as Die") {
    val scheduler = new MockAsyncScheduler()
    val computation: Eru[String, Int] = Eru.fail("error")

    val (exit, _) = scheduler.executeWithFinalizers(computation)

    exit match {
      case Exit.Die(throwable) =>
        throwable match {
          case ex: EruException[?] =>
            // Since EruException[?] has existential type, we need to compare as Any
            assertEquals(ex.error, "error": Any)
          case _ => fail("Expected EruException")
        }
      case _ => fail("Expected Die exit")
    }
  }

  test("AsyncFiber.onComplete should register callback for incomplete fiber") {
    val fiber = new MockAsyncFiber[String, Int]()
    var callbackInvoked = false
    var receivedFiber: Option[EruFiber[String, Int]] = None

    fiber.onComplete { f =>
      callbackInvoked = true
      receivedFiber = Some(f)
    }

    // Callback should not be invoked yet
    assertEquals(callbackInvoked, false)
    assertEquals(receivedFiber, None)

    // Complete the fiber
    fiber.complete(Exit.Success(42))

    // Callback should now be invoked
    assertEquals(callbackInvoked, true)
    assert(receivedFiber.isDefined, "Should have received completed fiber")
  }

  test("AsyncFiber.onComplete should immediately invoke callback for completed fiber") {
    val fiber = new MockAsyncFiber[String, Int]()
    fiber.complete(Exit.Success(42))

    var callbackInvoked = false
    var receivedFiber: Option[EruFiber[String, Int]] = None

    fiber.onComplete { f =>
      callbackInvoked = true
      receivedFiber = Some(f)
    }

    // Callback should be invoked immediately
    assertEquals(callbackInvoked, true)
    assert(receivedFiber.isDefined, "Should have received completed fiber")
  }

  test("AsyncFiber.isCompleted should track completion state") {
    val fiber = new MockAsyncFiber[String, Int]()

    assertEquals(fiber.isCompleted, false)

    fiber.complete(Exit.Success(42))

    assertEquals(fiber.isCompleted, true)
  }

  test("AsyncFiber.getCompleted should return None for incomplete fiber") {
    val fiber = new MockAsyncFiber[String, Int]()

    assertEquals(fiber.getCompleted, None)
  }

  test("AsyncFiber.getCompleted should return Some for completed fiber") {
    val fiber = new MockAsyncFiber[String, Int]()
    fiber.complete(Exit.Success(42))

    val completed = fiber.getCompleted
    assert(completed.isDefined, "Should have completed fiber")
    completed.get.exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case _ => fail("Expected successful exit")
    }
  }

  test("AsyncFiber should be a Fiber") {
    val fiber = new MockAsyncFiber[String, Int]()

    // Should compile - AsyncFiber extends Fiber
    val _: Fiber[String, Int] = fiber
    // FiberId is an opaque type backed by Long, so verify it's a valid FiberId
    val _: FiberId = fiber.id
    assert(Option(fiber.id).isDefined, "Fiber ID should be present")
  }

  test("AsyncFiber.await should provide Exit information") {
    val fiber = new MockAsyncFiber[String, Int]()
    fiber.complete(Exit.Success(42))

    val awaitEffect = fiber.await
    val _: Eru[Nothing, Exit[String, Int]] = awaitEffect

    // The await method returns an effect that yields the exit
    // In our mock, it should succeed with the exit
    val exit = awaitEffect.unsafeRunSync()
    exit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case _ => fail("Expected successful exit")
    }
  }

  test("AsyncFiber.interrupt should return Unit effect") {
    val fiber = new MockAsyncFiber[String, Int]()
    val cause = InterruptCause.Cancelled(Some("test"))

    val interruptEffect = fiber.interrupt(cause)
    val _: Eru[Nothing, Unit] = interruptEffect

    val result = interruptEffect.unsafeRunSync()
    assertEquals(result, ())
  }

  test("AsyncScheduler companion object returns None") {
    // The companion object should return None as documented
    assertEquals(AsyncScheduler.get, None)
  }

  test("Multiple callbacks should all be invoked on completion") {
    val fiber = new MockAsyncFiber[String, Int]()
    var callback1Invoked = false
    var callback2Invoked = false

    fiber.onComplete(_ => callback1Invoked = true)
    fiber.onComplete(_ => callback2Invoked = true)

    assertEquals(callback1Invoked, false)
    assertEquals(callback2Invoked, false)

    fiber.complete(Exit.Success(42))

    assertEquals(callback1Invoked, true)
    assertEquals(callback2Invoked, true)
  }

}
