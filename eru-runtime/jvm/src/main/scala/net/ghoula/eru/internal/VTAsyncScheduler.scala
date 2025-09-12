package net.ghoula.eru.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.*

/** Virtual Thread implementation of AsyncScheduler.
  *
  * This scheduler provides true asynchronous execution using Java Virtual Threads, enabling proper
  * FILO finalizer semantics by allowing parent fibers to continue executing while children run
  * concurrently.
  */
private[eru] final class VTAsyncScheduler extends AsyncScheduler {

  def scheduleAsync[E, A](
    computation: Eru[E, A],
    observer: Option[EruObserver]
  ): AsyncFiber[E, A] = {
    new VTAsyncFiber(computation, observer)
  }

  def executeWithFinalizers[E, A](
    computation: Eru[E, A]
  ): (Exit[E, A], List[() => Eru[Nothing, Unit]]) = {
    Eru.executeWithFinalizers(computation)
  }
}

/** Virtual Thread implementation of AsyncFiber.
  *
  * This fiber runs the computation on a Virtual Thread and provides callback registration for async
  * completion notification.
  */
private final class VTAsyncFiber[E, A](
  computation: Eru[E, A],
  observer: Option[EruObserver]
) extends AsyncFiber[E, A] {

  val id: FiberId = FiberId.fresh()

  private val completedRef = new AtomicReference[Option[EruFiber[E, A]]](None)
  private val callbackRef = new AtomicReference[Option[EruFiber[E, A] => Unit]](None)
  private val latch = new CountDownLatch(1)

  private val thread = java.lang.Thread.startVirtualThread(() => {
    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

    try {
      val scheduler = new VTAsyncScheduler()
      val (exit, finalizers) = scheduler.executeWithFinalizers(computation)

      val completedFiber = EruFiber.withId(id, exit, finalizers)

      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))

      completedRef.set(Some(completedFiber))
      callbackRef.get().foreach(cb => cb(completedFiber))

    } catch {
      case t: Throwable =>
        val exit = Exit.Die(t)
        val completedFiber = EruFiber.withId(id, exit, Nil)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))

        completedRef.set(Some(completedFiber))
        callbackRef.get().foreach(cb => cb(completedFiber))
    } finally {
      latch.countDown()
    }
  })

  def onComplete(callback: EruFiber[E, A] => Unit): Unit = {
    completedRef.get() match {
      case Some(completed) =>
        callback(completed)
      case None =>
        callbackRef.set(Some(callback))
        completedRef.get().foreach(callback)
    }
  }

  def isCompleted: Boolean = completedRef.get().isDefined

  def getCompleted: Option[EruFiber[E, A]] = completedRef.get()

  def await: Eru[Nothing, Exit[E, A]] = {
    Eru.blocking {
      latch.await()
      getCompleted.get
    }.attempt.flatMap {
      case Result.Success(fiber) => fiber.await
      case Result.Failure(_) =>
        val interruptFiber =
          EruFiber.withId(id, Exit.Interrupt(id, InterruptCause.Cancelled(Some("Await interrupted"))), Nil)
        interruptFiber.await
    }
  }

  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
    Eru.effect {
      thread.interrupt()
    }.attempt.flatMap {
      case Result.Success(_) => Eru.unit
      case Result.Failure(_) => Eru.unit
    }
  }
}
