package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Regression fence for Phase 3.3: event-driven VT sleep.
  *
  * Before the refactor, `RuntimeBackend.VirtualThreads.sleep` used
  * `Eru.interruptibleBlocking { Thread.sleep(ms) }`. Interrupts worked because the JVM delivers
  * InterruptedException to Thread.sleep, and the interpreter's `evalInterruptible` catch converted
  * it to Exit.Interrupt. Retention per parked fiber included: a VT `StackChunk` (Loom base), a
  * DelayScheduler$ScheduledForkJoinTask (allocated per Thread.sleep call on a VT), plus the Eru
  * wrapper graph (InterruptibleBlocking + Attempt + Chain + Continuation.Step/End).
  *
  * After Phase 3.3, sleep is event-driven: it schedules a wake-up on the HashedTimerWheel and parks
  * via handleSuspend. Expected behavior unchanged: `sleep(d)` completes roughly after `d`;
  * interrupt mid-sleep produces Exit.Interrupt with FILO finalizers. The allocation profile should
  * improve — measured in Phase 4 remeasurement. This spec verifies the behavioral correctness.
  *
  * The quick-release test guards its interrupt with an elapsed-time assertion: a regression to an
  * un-interruptible sleep would hang rather than release the fiber quickly.
  */
class SleepInterruptSpec extends EruTestSuite {

  test("sleep completes normally when not interrupted") {
    val result = runtime.sleep(Duration.ofMillis(10)).unsafeRunSync()
    assertEquals(result, ())
  }

  test("sleep with zero duration is a fast-path no-op") {
    val result = runtime.sleep(Duration.ZERO).unsafeRunSync()
    assertEquals(result, ())
  }

  test("sleep with negative duration is a fast-path no-op") {
    val result = runtime.sleep(Duration.ofMillis(-1)).unsafeRunSync()
    assertEquals(result, ())
  }

  test("fiber interrupted mid-sleep yields Exit.Interrupt") {
    val prog = for {
      fiber <- runtime.sleep(Duration.ofSeconds(30)).fork
      _ <- Eru.effect { Thread.sleep(10); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("interrupted sleep runs finalizers FILO") {
    val order = new ConcurrentLinkedQueue[String]()

    val prog = for {
      fiber <- (Eru
        .succeed("before")
        .ensure(Eru.effect { order.add("fin-before"); () })
        .flatMap(_ => runtime.sleep(Duration.ofSeconds(30)))
        .ensure(Eru.effect { order.add("fin-after"); () }))
        .fork
      _ <- Eru.effect { Thread.sleep(10); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) =>
        assertEquals(order.asScala.toList, List("fin-after", "fin-before"))
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("interrupted sleep completes quickly, not at wall-clock duration") {
    val start = System.nanoTime()
    val prog = for {
      fiber <- runtime.sleep(Duration.ofSeconds(30)).fork
      _ <- Eru.effect { Thread.sleep(5); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()
    val elapsedMs = (System.nanoTime() - start) / 1_000_000L

    assert(elapsedMs < 2000L, s"Interrupt should release the sleep quickly; took ${elapsedMs}ms")
    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("sleep does not run the rest of the fiber's computation after interrupt") {
    val reachedAfterSleep = new AtomicBoolean(false)

    val prog = for {
      fiber <- (runtime
        .sleep(Duration.ofSeconds(30))
        .flatMap { _ =>
          Eru.effect { reachedAfterSleep.set(true); () }
        })
        .fork
      _ <- Eru.effect { Thread.sleep(10); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      _ <- fiber.await
    } yield ()

    prog.unsafeRunSync()

    assert(!reachedAfterSleep.get(), "Code after an interrupted sleep must not execute")
  }

  test("parallel sleeps can be interrupted independently") {
    val prog = for {
      slow <- runtime.sleep(Duration.ofSeconds(30)).fork
      fast <- runtime.sleep(Duration.ofMillis(20)).fork
      _ <- Eru.effect { Thread.sleep(5); () }
      _ <- slow.interrupt(InterruptCause.Cancelled())
      slowExit <- slow.await
      fastExit <- fast.await
    } yield (slowExit, fastExit)

    val (slowExit, fastExit) = prog.unsafeRunSync()

    slowExit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected slow to be interrupted, got $other")
    }
    fastExit match {
      case Exit.Success(()) => ()
      case other => fail(s"Expected fast to succeed, got $other")
    }
  }
}
