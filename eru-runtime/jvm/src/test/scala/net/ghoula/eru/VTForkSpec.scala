package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class VTForkSpec extends FunSuite {

  test("fork runs effect on a virtual thread (JVM VT backend)") {
    val isVirtualEffect: Eru[Throwable, Boolean] = Eru.effect(Thread.currentThread().isVirtual)
    val fiber = isVirtualEffect.fork.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    exit match {
      case Exit.Success(isVT) => assert(isVT, clue("Effect should run on a Java Virtual Thread"))
      case other => fail(s"Unexpected exit: $other")
    }
  }

  test("forkWithObserver emits FiberStarted then FiberCompleted with same id") {
    val events = scala.collection.mutable.ListBuffer.empty[EruObserver.EruEvent]
    val obs = new EruObserver { def onEvent(e: EruObserver.EruEvent): Unit = events += e }
    val fiber = Eru.succeed(42).forkWithObserver(obs).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    assertEquals(exit, Exit.Success(42))

    val started = events.collect { case e: EruObserver.EruEvent.FiberStarted => e }
    val finished = events.collect { case e: EruObserver.EruEvent.FiberCompleted => e }

    assertEquals(started.size, 1, clue("one FiberStarted expected"))
    assertEquals(finished.size, 1, clue("one FiberCompleted expected"))

    val startId = started.head.fiberId
    val endId = finished.head.fiberId
    assertEquals(startId, endId, clue("Fiber ids should match for start and completion"))
  }
}
