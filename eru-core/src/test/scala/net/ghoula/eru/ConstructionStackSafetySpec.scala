package net.ghoula.eru

import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.CorePrelude.*

/** Construction-time stack safety for Eru's recursive builders.
  *
  * `Eru.flatMap` fuses `Succeed` receivers eagerly, which makes plain iterative builders
  * (`foldLeft`, `iterateN`, `unfold`) allocate nothing. The same fusion makes recursively
  * *constructed* chains recurse on the JVM stack while building. These tests pin the guarantee that
  * Eru's own recursive builders (`iterate`, `repeatN`, `forever`) construct and run without stack
  * overflow, at depths far beyond the JVM stack limit.
  *
  * Each probe runs inside a thread created with an explicit 512 KB stack (the JVM minimum is ~136
  * KB, so this size is honored on all platforms). The recursive construction predating the fix used
  * roughly a few hundred bytes of stack per iteration, so 1M iterations overflow even the largest
  * default JVM stacks; pinning a small explicit stack makes the tests environment independent
  * rather than dependent on whatever stack size the host JVM was launched with.
  */
class ConstructionStackSafetySpec extends munit.FunSuite {

  /** Runs `body` on a thread with a fixed 512 KB stack and rethrows its result or failure. */
  private def onSmallStack[A](body: => A): A = {
    val box = new AtomicReference[Either[Throwable, A]]()
    val thread = new Thread(
      new ThreadGroup("construction-stack-probe"),
      () =>
        box.set(try Right(body)
        catch { case e: Throwable => Left(e) }),
      "probe",
      512 * 1024
    )
    thread.start()
    thread.join()
    box.get() match {
      case Right(value) => value
      case Left(error) => throw error
    }
  }

  test("iterate constructs and runs 1M iterations without stack overflow") {
    val result = onSmallStack {
      Eru.iterate(0)(current => Eru.succeed(current + 1))(_ >= 1000000).unsafeRunSync()
    }
    assertEquals(result, 1000000)
  }

  test("iterateN constructs and runs 1M iterations without stack overflow") {
    val result = onSmallStack {
      Eru.iterateN(0, 1000000)(x => Eru.succeed(x + 1)).unsafeRunSync()
    }
    assertEquals(result, 1000000)
  }

  test("repeatN constructs and runs 1M repetitions of a pure effect without stack overflow") {
    val result = onSmallStack {
      Eru.repeatN(1000000)(Eru.unit).unsafeRunSync()
    }
    assertEquals(result, ())
  }

  test("foldLeft constructs and runs 1M pure steps without stack overflow") {
    val result = onSmallStack {
      Eru.foldLeft((1 to 1000000).toList)(0L)((s, a) => Eru.succeed(s + a)).unsafeRunSync()
    }
    assertEquals(result, (1L to 1000000L).sum)
  }

  test("foldRight constructs and runs 100K pure steps without stack overflow") {
    val result = onSmallStack {
      Eru.foldRight((1 to 100000).toList)(0L)((a, s) => Eru.succeed(s + a)).unsafeRunSync()
    }
    assertEquals(result, (1L to 100000L).sum)
  }

  test("forever of a pure effect constructs without stack overflow") {
    val forever = onSmallStack(Eru.forever(Eru.unit))
    assertEquals(forever, forever)
  }
}
