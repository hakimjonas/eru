package net.ghoula.eru

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Phase 3.0 smoke test for the Option B design claim.
  *
  * The claim (from EVENT_DRIVEN_SLEEP_PLAN.md section 2.2):
  *
  * When an `InterruptedException` escapes the thunk of `Eru.blocking { ... }`, the interpreter's
  * `Effect` branch catches it and throws `InterruptedWithFinalizers`. That propagates through
  * `.attempt` boundaries via `evalSub`'s re-throw path for `Exit.Interrupt`, so a caller's
  * `.attempt.map { case Result.Failure => ... }` defensive block is BYPASSED. The fiber exits with
  * `Exit.Interrupt` and finalizers run FILO.
  *
  * This is load-bearing for the refactor. If it's wrong, Option B doesn't work and we pivot to
  * Option A (dedicated Sleep AST case). So we verify it here before touching any production code.
  *
  * Construction mirrors the current `handleSuspend` shape:
  * `Eru.blocking { ... may throw InterruptedException ... }.attempt.map { ... }`
  *
  * We simulate a fiber interrupted during an Eru.blocking call by throwing InterruptedException
  * directly from the thunk — that's what Thread.interrupt + future.get() would do on a real VT. The
  * propagation path after the throw is the same either way.
  *
  * These tests force the computation into a fiber so it runs through the safe state-machine
  * interpreter (runFiberSafe), where the InterruptedException catch lives; the fast path (runFast)
  * does not have that catch.
  */
class HandleSuspendPropagationSmokeSpec extends EruTestSuite {

  private def runInFiber[E, A](eru: Eru[E, A]): Exit[E, A] = {
    val forked = for {
      fiber <- runtime.fork(eru)
      exit <- fiber.await
    } yield exit
    forked.unsafeRunSync()
  }

  test("InterruptedException in blocking thunk propagates as Exit.Interrupt, not Result.Failure") {
    var landedInFailureBranch = false

    val prog: Eru[Nothing, Unit] = Eru.blocking {
      throw new InterruptedException("simulated interrupt at park")
    }.attempt.map {
      case Result.Success(_) => ()
      case Result.Failure(_) =>
        landedInFailureBranch = true
        ()
    }

    val exit = runInFiber(prog)

    assert(!landedInFailureBranch, "Option B claim FAILED: .attempt captured InterruptedException into Result.Failure")
    exit match {
      case Exit.Interrupt(_, cause) =>
        assertEquals(cause, InterruptCause.Cancelled())
      case other =>
        fail(s"Option B claim FAILED: expected Exit.Interrupt, got $other")
    }
  }

  test("finalizers run FILO when InterruptedException escapes a blocking thunk") {
    val order = new ConcurrentLinkedQueue[String]()

    val prog = Eru
      .succeed("a")
      .ensure(Eru.effect { order.add("fin-a"); () })
      .flatMap(_ =>
        Eru.blocking { throw new InterruptedException("simulated") }.attempt.map {
          case Result.Success(_) => ()
          case Result.Failure(_) => ()
        }
      )
      .ensure(Eru.effect { order.add("fin-b"); () })

    val exit = runInFiber(prog)

    exit match {
      case Exit.Interrupt(_, _) =>
        assertEquals(order.asScala.toList, List("fin-b", "fin-a"))
      case other =>
        fail(s"Expected Exit.Interrupt, got $other")
    }
  }

  test("attempt cannot convert Exit.Interrupt to Result.Failure") {
    var landedInFailureBranch = false

    val prog: Eru[Nothing, String] = Eru.blocking {
      throw new InterruptedException("simulated")
    }.attempt.flatMap {
      case Result.Success(_) => Eru.succeed("success")
      case Result.Failure(_) =>
        landedInFailureBranch = true
        Eru.succeed("failure-branch")
    }

    val exit = runInFiber(prog)

    assert(!landedInFailureBranch, "attempt INCORRECTLY captured InterruptedException into Result.Failure")
    exit match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected Exit.Interrupt, got $other")
    }
  }
}
