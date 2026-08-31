package net.ghoula.eru.fiber

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.test.EruTestSuite

/** Tests that finalizers are preserved when InterruptedException occurs inside effectTotal, effect,
  * and interruptibleBlocking thunks.
  *
  * These tests verify the fix for the gap where bare InterruptedException escaping from
  * effectTotal/effect bypassed the InterruptedWithFinalizers catch chain in executeWithFinalizers,
  * causing accumulated finalizers to be lost.
  */
class InterruptionFinalizerSpec extends EruTestSuite {

  test("interruptibleBlocking - finalizers preserved on interruption (baseline)") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      _ <- Eru.succeed("setup").ensure(Eru.effectTotal(finalizerRan.set(true)))
      fiber <- runtime.fork {
        Eru.interruptibleBlocking(Thread.sleep(Long.MaxValue))
      }
      _ <- runtime.sleep(Duration.ofMillis(50))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- fiber.await
    } yield ()

    computation.unsafeRunSync()
    assert(finalizerRan.get(), "finalizer must run after interruptibleBlocking interruption")
  }

  test("effectTotal - finalizers preserved on interruption") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        Eru
          .succeed("setup")
          .ensure(Eru.effectTotal(finalizerRan.set(true)))
          .flatMap(_ => Eru.effectTotal(Thread.sleep(Long.MaxValue)))
      }
      _ <- runtime.sleep(Duration.ofMillis(50))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- fiber.await
    } yield ()

    computation.unsafeRunSync()
    assert(finalizerRan.get(), "finalizer must run after effectTotal interruption")
  }

  test("effect - finalizers preserved on interruption") {
    val finalizerRan = new AtomicBoolean(false)

    val computation = for {
      fiber <- runtime.fork {
        Eru
          .succeed("setup")
          .ensure(Eru.effectTotal(finalizerRan.set(true)))
          .flatMap(_ => Eru.effect(Thread.sleep(Long.MaxValue)))
      }
      _ <- runtime.sleep(Duration.ofMillis(50))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- fiber.await
    } yield ()

    computation.unsafeRunSync()
    assert(finalizerRan.get(), "finalizer must run after effect interruption")
  }

  test("effectTotal - nested ensure chain preserved in FILO order on interruption") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      fiber <- runtime.fork {
        for {
          _ <- Eru.succeed("a").ensure(Eru.effectTotal(executionOrder.add("fin-a")))
          _ <- Eru.succeed("b").ensure(Eru.effectTotal(executionOrder.add("fin-b")))
          _ <- Eru.succeed("c").ensure(Eru.effectTotal(executionOrder.add("fin-c")))
          _ <- Eru.effectTotal(Thread.sleep(Long.MaxValue))
        } yield ()
      }
      _ <- runtime.sleep(Duration.ofMillis(50))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- fiber.await
    } yield ()

    computation.unsafeRunSync()
    assertEquals(executionOrder.asScala.toList, List("fin-c", "fin-b", "fin-a"))
  }

  test("effectTotal - fiber exit is Interrupt, not Die") {
    val computation = for {
      fiber <- runtime.fork {
        Eru.effectTotal(Thread.sleep(Long.MaxValue))
      }
      _ <- runtime.sleep(Duration.ofMillis(50))
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      exit <- fiber.await
    } yield exit

    val exit = computation.unsafeRunSync()
    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }
}
