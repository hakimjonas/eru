package net.ghoula.eru

import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.prelude.*

/** Race interruption semantics.
  *
  * When a race participant completes with `Exit.Interrupt`, the race must resolve with an
  * interruption outcome rather than parking forever: the previous implementation ignored the
  * interrupt exits entirely, and a race whose participants were both interrupted never released the
  * result latch (a hang). These tests run the race on a guarded thread so a regression surfaces as
  * a failed assertion, not a frozen suite.
  */
class RaceInterruptionSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  private val interruptedEffect: Eru[Nothing, Nothing] =
    Eru.interruptibleBlocking(throw new InterruptedException("boom"))

  /** Runs `body` on a separate thread and returns whether it finished within 5s, plus the caught
    * throwable if one was thrown.
    */
  private def runGuarded(body: => Unit): (Boolean, Option[Throwable]) = {
    val thrown = new AtomicReference[Option[Throwable]](None)
    val t = new Thread(() => {
      try body
      catch { case e: Throwable => thrown.set(Some(e)) }
    })
    t.start()
    t.join(5000L)
    (t.isAlive == false, Option(thrown.get()).flatten)
  }

  test("race of two interrupted effects resolves with interruption instead of hanging") {
    val (finished, thrown) = runGuarded {
      runtime.race(interruptedEffect, interruptedEffect).unsafeRunSync()
    }

    assert(finished, "race hung: both participants were interrupted and no result was produced")
    thrown match {
      case Some(_: InterruptedException) => ()
      case other => fail(s"Expected an interruption to propagate, got: $other")
    }
  }

  test("race resolves with the other side when one participant is interrupted") {
    val winner = Eru.succeed(42)
    val (finished, result) = runGuardedWithResult {
      runtime.race(interruptedEffect, winner).unsafeRunSync()
    }

    assert(finished, "race hung: the interrupted side blocked resolution")
    result match {
      case Some(Right(42)) => ()
      case other => fail(s"Expected the successful side to win, got: $other")
    }
  }

  private def runGuardedWithResult[A](body: => A): (Boolean, Option[A]) = {
    val box = new AtomicReference[Option[A]](None)
    val finished = runGuarded { box.set(Some(body)) }._1
    (finished, Option(box.get()).flatten)
  }
}
