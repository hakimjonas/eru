package net.ghoula.eru

/** Internal service for scheduling deferred task execution.
  *
  * The timer service is propagated via thread-local storage, following the same pattern as
  * `StructuredConcurrency.setCurrentScope`. When a `TimerService` is available, the `At`
  * interpreter case delegates scheduling to it and returns immediately. When absent (e.g., in the
  * synchronous kernel or tests without a runtime), the interpreter falls back to inline execution.
  *
  * The JVM runtime provides a `HashedTimerWheel` implementation that fires tasks as virtual threads
  * at the scheduled time, giving O(1) insert and O(bucket) per-tick cost.
  */
private[eru] trait TimerService {

  /** Schedule a task to run at the given absolute time (epoch millis).
    *
    * The implementation must fork the task onto a suitable execution context (e.g., a virtual
    * thread). The caller does not block.
    *
    * @param epochMillis
    *   the target execution time in milliseconds since epoch
    * @param task
    *   the runnable to execute at (or shortly after) the target time
    */
  def schedule(epochMillis: Long, task: Runnable): Unit

  /** Shuts down the timer, stopping the background tick thread. */
  def shutdown(): Unit
}

private[eru] object TimerService {
  private val current = new java.util.concurrent.atomic.AtomicReference[Option[TimerService]](None)

  def get: Option[TimerService] = current.get()
  def set(service: TimerService): Unit = current.set(Some(service))

  /** Swap in a new timer service, returning the previous one for restoration. Used by tests. */
  private[eru] def swap(service: Option[TimerService]): Option[TimerService] =
    current.getAndSet(service)
}
