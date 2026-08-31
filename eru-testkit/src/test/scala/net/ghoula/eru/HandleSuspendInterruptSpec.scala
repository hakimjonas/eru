package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Regression fence for Phase 3.2 of the event-driven-sleep refactor.
  *
  * Before this change, `handleSuspend` on the VT backend caught `InterruptedException` from
  * `future.get()` and converted it into `Left(InterruptedException)` — a typed failure. The four
  * production callers of `runtime.suspend` each have defensive `.attempt` handling of
  * `Result.Failure` that does NOT produce `Exit.Interrupt`:
  *
  *   - Promise.await (Promise.scala:217) → throw IllegalStateException
  *   - CyclicBarrier.await (CyclicBarrier.scala:142) → throw IllegalStateException
  *   - Deferred.await (Deferred.scala:144) → throw IllegalStateException
  *   - CountDownLatch.await (CountDownLatch.scala:137) → fall into Thread.yield busy-loop
  *
  * None of these produce `Exit.Interrupt` today. None of them is tested for fiber interruption.
  *
  * With the handleSuspend contract change (let InterruptedException escape), the interpreter's
  * Effect-branch catch at Eru.scala:1708 converts the bare exception into InterruptedWithFinalizers
  * which bypasses every `.attempt` boundary via evalSub's re-throw at Eru.scala:1316, yielding
  * Exit.Interrupt at the fiber boundary.
  *
  * Each test here:
  *   1. Forks a fiber parked inside one of the four suspension primitives.
  *   2. Waits briefly for the fiber to actually reach the parked state.
  *   3. Interrupts the fiber.
  *   4. Asserts Exit.Interrupt(_, Cancelled).
  *
  * Timing: a small Thread.sleep(10ms) is used to ensure the fiber has entered the suspend before we
  * interrupt. We intentionally keep this dependency on wall time isolated to the test — the
  * fiber-under-test itself is interrupted deterministically.
  *
  * The CountDownLatch test additionally guards its await with a timeout: if the old Thread.yield
  * polling-fallback still ran, the await would never return, so the timeout makes a regression fail
  * instead of hanging.
  */
class HandleSuspendInterruptSpec extends EruTestSuite {

  private val settleMs: Long = 10L

  test("Promise.await: interrupted fiber yields Exit.Interrupt, not IllegalStateException") {
    val prog = for {
      promise <- Eru.promise[String, Int]
      fiber <- promise.await.eru.fork
      _ <- Eru.effect { Thread.sleep(settleMs); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("Deferred.await: interrupted fiber yields Exit.Interrupt") {
    val prog = for {
      deferred <- Eru.deferred[Int]
      fiber <- deferred.await.eru.fork
      _ <- Eru.effect { Thread.sleep(settleMs); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("CountDownLatch.await: interrupted fiber yields Exit.Interrupt, not busy-loop") {
    val prog = for {
      latch <- Eru.countDownLatch(1)
      fiber <- latch.await.eru.fork
      _ <- Eru.effect { Thread.sleep(settleMs); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      exit <- runtime.timeout(Duration.ofSeconds(5))(fiber.await).attempt
    } yield exit

    val result = prog.unsafeRunSync()

    result match {
      case Result.Success(Exit.Interrupt(_, _)) => ()
      case other =>
        fail(s"Expected Success(Exit.Interrupt), got $other (likely a regression to busy-loop)")
    }
  }

  test("CyclicBarrier.await: interrupted fiber yields Exit.Interrupt") {
    val prog = for {
      barrier <- Eru.cyclicBarrier(2)
      fiber <- barrier.await.eru.fork
      _ <- Eru.effect { Thread.sleep(settleMs); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("test")))
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("Interrupted suspension runs finalizers FILO") {
    val order = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val prog = for {
      promise <- Eru.promise[String, Int]
      fiber <- (Eru
        .succeed("before")
        .ensure(Eru.effect { order.add("fin-before"); () })
        .flatMap(_ => promise.await.eru)
        .ensure(Eru.effect { order.add("fin-after"); () }))
        .fork
      _ <- Eru.effect { Thread.sleep(settleMs); () }
      _ <- fiber.interrupt(InterruptCause.Cancelled())
      exit <- fiber.await
    } yield exit

    val exit = prog.unsafeRunSync()

    exit match {
      case Exit.Interrupt(_, _) =>
        import scala.jdk.CollectionConverters.*
        assertEquals(order.asScala.toList, List("fin-after", "fin-before"))
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }
}
