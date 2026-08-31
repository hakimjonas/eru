package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*

/** Structured concurrency contracts on the Virtual Threads backend.
  *
  * Containment is absolute: a fiber forked inside a scope dies with its scope. `fork` children are
  * joined (interrupted, then awaited) at scope exit; `forkDaemon` children are interrupted but not
  * joined. Children interrupted by their parent's completion carry the real parent identity and the
  * parent's real exit in the `ParentTerminated` cause. Root fibers' parent is the runtime itself,
  * identified by the reserved [[FiberId.Root]].
  *
  * When a parent completes normally, its children are interrupted; each child's scope then
  * interrupts its own children, so grandchildren die transitively. Joined semantics would block on
  * a daemon's slow finalizer, which is what the daemon scope-exit test measures. The two-phase
  * drain test relies on timing: three children with ~500ms finalizers finish in ~500ms if drains
  * are concurrent, ~1500ms if sequential.
  */
class StructuredConcurrencySpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  private def awaitDone[A](fiber: Fiber[?, ?]): Exit[?, ?] = fiber.await.unsafeRunSync()

  test("an interrupt issued immediately after fork is never lost") {
    val child = runtime.fork(runtime.sleep(Duration.ofSeconds(30))).unsafeRunSync()
    child.interrupt(InterruptCause.Cancelled(Some("early"))).unsafeRunSync()

    child.await.unsafeRunSync() match {
      case Exit.Interrupt(_, InterruptCause.Cancelled(Some("early"))) => ()
      case other => fail(s"Expected the early interrupt to be delivered, got: $other")
    }
  }

  test("a child interrupted by its parent's completion carries the real parent id and exit") {
    val childRef = new AtomicReference[Fiber[Nothing, Unit]]()
    val parentEffect = Eru.effect {
      childRef.set(runtime.fork(runtime.sleep(Duration.ofSeconds(30))).unsafeRunSync())
      ()
    }
      .flatMap(_ => Eru.fail("boom"))

    val parentFiber = runtime.fork(parentEffect).unsafeRunSync()
    val parentExit = awaitDone(parentFiber)

    parentExit match {
      case Exit.Failure("boom") => ()
      case other => fail(s"Expected the parent to fail with 'boom', got: $other")
    }

    awaitDone(childRef.get()) match {
      case Exit.Interrupt(_, InterruptCause.ParentTerminated(parentId, parentExit)) =>
        assertEquals(parentId, parentFiber.id)
        assertEquals(parentExit, Exit.Failure("boom"))
      case other => fail(s"Expected ParentTerminated with the real cause, got: $other")
    }
  }

  test("grandchildren die transitively when the root of the tree completes") {
    val grandchildRef = new AtomicReference[Fiber[Nothing, Unit]]()
    val parentEffect = Eru.effect {
      val child = runtime
        .fork(
          Eru.effect {
            grandchildRef.set(runtime.fork(runtime.sleep(Duration.ofSeconds(30))).unsafeRunSync())
            ()
          }
            .flatMap(_ => Eru.succeed(()))
        )
        .unsafeRunSync()
      val _ = child
      ()
    }
      .flatMap(_ => Eru.succeed(()))
    val _ = runtime.fork(parentEffect).unsafeRunSync()

    var spins = 0
    while (Option(grandchildRef.get()).isEmpty && spins < 5000) {
      Thread.sleep(1L)
      spins += 1
    }
    assert(Option(grandchildRef.get()).isDefined, "grandchild was never forked")

    awaitDone(grandchildRef.get()) match {
      case Exit.Interrupt(_, _) => ()
      case other => fail(s"Expected the grandchild to be interrupted, got: $other")
    }
  }

  test("forkDaemon children are interrupted but not joined at scope exit") {
    var finalizerRan = false
    val daemonEffect = runtime
      .sleep(Duration.ofSeconds(30))
      .ensure(
        Eru.effect {
          Thread.sleep(3000L)
          finalizerRan = true
        }
      )

    val parentEffect = Eru.effect {
      val _ = runtime.forkDaemon(daemonEffect).unsafeRunSync()
      ()
    }
      .flatMap(_ => Eru.succeed("done"))

    val parentFiber = runtime.fork(parentEffect).unsafeRunSync()

    val t0 = System.nanoTime()
    val parentExit = awaitDone(parentFiber)
    val parentElapsedMs = (System.nanoTime() - t0) / 1_000_000L

    assertEquals(parentExit, Exit.Success("done"))
    assert(parentElapsedMs < 2200L, s"Scope exit joined the daemon (took ${parentElapsedMs}ms)")

    var spins = 0
    while (!finalizerRan && spins < 5000) {
      Thread.sleep(1L)
      spins += 1
    }
    assert(finalizerRan, "daemon finalizer did not run after the interrupt")
  }

  test("scope exit interrupts all children before joining any (two-phase drain)") {
    def childWithSlowFinalizer(): Eru[Nothing, Unit] =
      Eru.unit.ensure(Eru.effect { Thread.sleep(500L) })

    val parentEffect = Eru.effect {
      (1 to 3).foreach { _ =>
        val _ = runtime.fork(childWithSlowFinalizer()).unsafeRunSync()
      }
      ()
    }
      .flatMap(_ => Eru.succeed("done"))

    val parentFiber = runtime.fork(parentEffect).unsafeRunSync()
    val t0 = System.nanoTime()
    awaitDone(parentFiber)
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

    assert(
      elapsedMs < 1200L,
      s"Scope drain appears sequential (took ${elapsedMs}ms for three 500ms finalizers)"
    )
  }

  test("root fiber tracking stays bounded across completed forks") {
    val tracker = FiberTracker()
    (1 to 50).foreach { i =>
      val _ = runtime.forkTracked(Eru.succeed(i), tracker).unsafeRunSync()
    }

    var spins = 0
    while (tracker.queue.size() > 5 && spins < 5000) {
      Thread.sleep(1L)
      spins += 1
    }
    assert(
      tracker.queue.size() <= 5,
      s"Root tracking queue grew unboundedly: ${tracker.queue.size()} fibers"
    )
  }

  test("root cleanup identifies the runtime as the parent via FiberId.Root") {
    val events = new java.util.concurrent.ConcurrentLinkedQueue[EruObserver.EruEvent]()
    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = events.offer(event)
    }

    val _ = runtime.fork(Eru.succeed(1)).unsafeRunSync()
    val _ = runtime.shutdownRootFibers(Some(observer)).unsafeRunSync()

    val sawRootCause = events.asScala.exists {
      case EruObserver.EruEvent.ChildInterruptionRequested(
            _,
            _,
            InterruptCause.ParentTerminated(FiberId.Root, Exit.Success(())),
            _
          ) =>
        true
      case _ => false
    }
    assert(sawRootCause, s"Expected a root-identified interruption event, got: ${events.asScala.toList}")
  }
}
