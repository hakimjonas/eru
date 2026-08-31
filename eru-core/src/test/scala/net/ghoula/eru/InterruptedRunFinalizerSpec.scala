package net.ghoula.eru

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.CorePrelude.*

/** Finalizers must run when a synchronous run is interrupted.
  *
  * When a fiber (or the main thread) is interrupted while an `interruptibleBlocking` thunk is
  * executing, the interpreter aborts with an interruption that carries the accumulated finalizers.
  * These tests pin that `unsafeRunSync` drains those finalizers before rethrowing, and that the
  * observer variant emits `ProgramEnd` on the same path.
  */
class InterruptedRunFinalizerSpec extends munit.FunSuite {

  private def effectWithFinalizer(started: CountDownLatch, finalized: AtomicBoolean): Eru[Throwable, Unit] =
    Eru.effect { started.countDown(); () }
      .ensure(Eru.effect { finalized.set(true); () })
      .flatMap(_ => Eru.interruptibleBlocking(Thread.sleep(60000L)))

  test("unsafeRunSync drains finalizers when the run is interrupted") {
    val finalized = new AtomicBoolean(false)
    val started = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()

    val thread = new Thread(() => {
      try effectWithFinalizer(started, finalized).unsafeRunSync()
      catch { case t: Throwable => failure.set(t) }
    })
    thread.start()
    assert(started.await(5, TimeUnit.SECONDS), "effect did not start")
    Thread.sleep(200)
    thread.interrupt()
    thread.join(10000)

    assert(Option(failure.get()).isDefined, "interruption must surface as a thrown exception")
    assert(finalized.get(), "ensure finalizer must run when the run is interrupted")
  }

  test("unsafeRunSyncWith drains finalizers and emits ProgramEnd when the run is interrupted") {
    val finalized = new AtomicBoolean(false)
    val started = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    val events = new ConcurrentLinkedQueue[EruObserver.EruEvent]()

    val observer = new EruObserver {
      def onEvent(event: EruObserver.EruEvent): Unit = events.add(event)
    }

    val thread = new Thread(() => {
      try effectWithFinalizer(started, finalized).unsafeRunSyncWith(observer)
      catch { case t: Throwable => failure.set(t) }
    })
    thread.start()
    assert(started.await(5, TimeUnit.SECONDS), "effect did not start")
    Thread.sleep(200)
    thread.interrupt()
    thread.join(10000)

    assert(Option(failure.get()).isDefined, "interruption must surface as a thrown exception")
    assert(finalized.get(), "ensure finalizer must run when the run is interrupted")
    val sawProgramEnd = events.asScala.exists {
      case EruObserver.EruEvent.ProgramEnd(_, _) => true
      case _ => false
    }
    assert(sawProgramEnd, "observer must receive ProgramEnd when the run is interrupted")
  }
}
