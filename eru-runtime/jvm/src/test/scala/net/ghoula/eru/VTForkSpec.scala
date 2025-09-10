package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Test suite for JVM Virtual Thread integration in the Eru runtime.
  *
  * Validates that the JVM runtime backend properly utilizes virtual threads for fiber execution
  * when available. These tests ensure that the virtual thread integration provides the expected
  * concurrency characteristics and proper thread management while maintaining compatibility with
  * the Eru effect system's semantics.
  */
final class VTForkSpec extends TestWithRuntime {

  /** Validates that fork runs effects on virtual threads in the JVM backend.
    *
    * Tests that the JVM runtime backend properly utilizes virtual threads for fiber execution when
    * available.
    */
  test("fork runs effect on a virtual thread (JVM VT backend)") {
    val isVirtualEffect: Eru[Throwable, Boolean] = Eru.effect(Thread.currentThread().isVirtual)
    val fiber = isVirtualEffect.fork.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(isVT) => assert(isVT, clue("Effect should run on a Java Virtual Thread"))
      case other => fail(s"Unexpected exit: $other")
    }
  }

  /** Validates that forkWithObserver emits proper lifecycle events with consistent IDs.
    *
    * Tests that the observability system emits FiberStarted and FiberCompleted events with matching
    * fiber IDs for proper event correlation.
    */
  test("forkWithObserver emits FiberStarted then FiberCompleted with same id") {
    val events = java.util.concurrent.ConcurrentLinkedQueue[EruObserver.EruEvent]()
    val completedSignal = java.util.concurrent.CountDownLatch(1)
    
    val obs = new EruObserver { 
      def onEvent(e: EruObserver.EruEvent): Unit = {
        events.offer(e)
        e match {
          case _: EruObserver.EruEvent.FiberCompleted => completedSignal.countDown()
          case _ => ()
        }
      }
    }
    val fiber = Eru.succeed(42).forkWithObserver(obs).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    assertEquals(exit, Exit.Success(42))

    // Wait for the FiberCompleted event to be recorded (with timeout for safety)
    assert(completedSignal.await(1, java.util.concurrent.TimeUnit.SECONDS), 
           "FiberCompleted event should be recorded within 1 second")

    import scala.jdk.CollectionConverters.*
    val eventList = events.asScala.toList
    val started = eventList.collect { case e: EruObserver.EruEvent.FiberStarted => e }
    val finished = eventList.collect { case e: EruObserver.EruEvent.FiberCompleted => e }


    assertEquals(started.size, 1, clue("one FiberStarted expected"))
    assertEquals(finished.size, 1, clue("one FiberCompleted expected"))

    val startId = started.head.fiberId
    val endId = finished.head.fiberId
    assertEquals(startId, endId, clue("Fiber ids should match for start and completion"))
  }
}
