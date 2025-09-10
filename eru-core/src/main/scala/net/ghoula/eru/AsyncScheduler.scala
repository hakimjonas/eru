package net.ghoula.eru

/** Contract for asynchronous fiber execution.
  */
private[eru] trait AsyncScheduler {

  /** Schedules a computation for asynchronous execution.
    *
    * @param computation
    *   the effect to execute asynchronously
    * @param observer
    *   optional observer for fiber lifecycle events
    * @return
    *   a fiber representing the asynchronous computation
    */
  def scheduleAsync[E, A](
    computation: Eru[E, A],
    observer: Option[EruObserver]
  ): AsyncFiber[E, A]

  /** Executes a computation and collects finalizers.
    *
    * @param computation
    *   the effect to execute
    * @return
    *   tuple of (exit result, collected finalizers)
    */
  def executeWithFinalizers[E, A](
    computation: Eru[E, A]
  ): (Exit[E, A], List[() => Eru[Nothing, Unit]])
}

/** Represents a fiber that may still be executing asynchronously.
  *
  * Unlike EruFiber which contains completed results, AsyncFiber represents a computation that may
  * still be running. The await operation can suspend the calling fiber until completion.
  */
private[eru] trait AsyncFiber[+E, +A] extends Fiber[E, A] {

  /** Register a callback to be invoked when this fiber completes.
    *
    * This enables the Await case to suspend the parent fiber and resume it when the child
    * completes, preserving the asynchronous execution model.
    *
    * @param callback
    *   function to invoke with the completed fiber when done
    */
  def onComplete(callback: EruFiber[E, A] => Unit): Unit

  /** Check if this fiber has completed execution.
    *
    * @return
    *   true if the fiber has finished, false if still running
    */
  def isCompleted: Boolean

  /** Get the completed result if available.
    *
    * @return
    *   Some(completed fiber) if done, None if still executing
    */
  def getCompleted: Option[EruFiber[E, A]]
}

/** Registry for the current async scheduler.
  *
  * This provides a clean way for the interpreter to access the scheduler without creating circular
  * dependencies or violating privacy boundaries.
  */
private[eru] object AsyncScheduler {

  @volatile private var currentScheduler: Option[AsyncScheduler] = None

  /** Set the current scheduler (called by runtime initialization). */
  def setScheduler(scheduler: AsyncScheduler): Unit = {
    currentScheduler = Some(scheduler)
  }

  /** Get the current scheduler for use by the interpreter. */
  def get: Option[AsyncScheduler] = currentScheduler
}
