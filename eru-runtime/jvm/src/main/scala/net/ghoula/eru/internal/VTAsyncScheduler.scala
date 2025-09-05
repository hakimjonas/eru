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
    // Use the new public API that properly captures finalizers
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

  // Start the virtual thread immediately
  private val thread = java.lang.Thread.startVirtualThread(() => {
    // Emit started event
    observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(id)))

    try {
      // Execute the computation - this is where finalizers are collected
      val scheduler = new VTAsyncScheduler()
      val (exit, finalizers) = scheduler.executeWithFinalizers(computation)

      // Create the completed fiber with collected finalizers
      val completedFiber = EruFiber.withId(id, exit, finalizers)

      // Emit completed event
      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(id, exit)))

      // Store the completed fiber and notify any waiting callback
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
    // If already completed, invoke callback immediately
    completedRef.get() match {
      case Some(completed) =>
        callback(completed)
      case None =>
        // Register callback for later invocation
        callbackRef.set(Some(callback))
        // Double-check in case completion happened between checks
        completedRef.get().foreach(callback)
    }
  }

  def isCompleted: Boolean = completedRef.get().isDefined

  def getCompleted: Option[EruFiber[E, A]] = completedRef.get()

  def await: Eru[Nothing, Exit[E, A]] = {
    // For AsyncFiber, await should delegate to EruFiber.await once completed
    // This ensures proper integration with the interpreter's Await case
    Eru.blocking {
      latch.await() // Wait for completion - this will block the virtual thread but not the carrier
      getCompleted.get // Safe because latch ensures completion
    }.attempt.flatMap {
      case Result.Success(fiber) => fiber.await
      case Result.Failure(_) =>
        // Create a dummy fiber with interrupt exit for error case
        val interruptFiber =
          EruFiber.withId(id, Exit.Interrupt(id, InterruptCause.Cancelled(Some("Await interrupted"))), Nil)
        interruptFiber.await
    }
  }

  def interrupt(cause: InterruptCause): Eru[Nothing, Unit] = {
    Eru.effect {
      // Interrupt the virtual thread
      thread.interrupt()
    }.attempt.flatMap {
      case Result.Success(_) => Eru.unit
      case Result.Failure(_) => Eru.unit // Interruption failure is not critical
    }
  }
}
