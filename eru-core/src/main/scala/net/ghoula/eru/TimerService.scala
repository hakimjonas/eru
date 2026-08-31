package net.ghoula.eru

/** Internal service for scheduling deferred task execution.
  *
  * At interpretation time the `At` case resolves its timer through `TimerService.get` — which reads
  * a thread-local first and falls back to a write-once default provider. This mirrors the pattern
  * established by `StructuredConcurrency.currentScope`: the interpreter's ambient runtime context
  * lives on the interpreting thread, not in a mutable process-global slot.
  *
  * Lifecycle:
  *   - Fork / race / handleSuspend entry points in the runtime push the forking runtime's timer
  *     into `currentTimer` before spawning a VT that will interpret a user Eru value, and restore
  *     the prior value on exit. This is the same push/pop discipline used by
  *     `StructuredConcurrency.withNewScope`.
  *   - `EruRuntime.shared`'s lazy initializer installs a default-provider into `defaultProvider`
  *     (write-once via compareAndSet). Subsequent `EruRuntime.create()` calls do NOT touch the
  *     provider slot — so multi-runtime setups remain fully isolated while a bare
  *     `Eru.at(...).unsafeRunSync()` from a thread that has never been touched by runtime plumbing
  *     still falls back to the shared runtime's wheel (matching single-runtime user expectations).
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
    * @return
    *   a [[TimerHandle]] that can be used to cancel the scheduled task before it fires
    */
  def schedule(epochMillis: Long, task: Runnable): TimerHandle

  /** Schedule a task to run after the given relative delay (milliseconds, monotonic clock).
    *
    * The delay is measured from the moment of scheduling on the monotonic clock: wall-clock
    * adjustments neither shorten nor lengthen it. The implementation must fork the task onto a
    * suitable execution context (e.g., a virtual thread). The caller does not block. The task fires
    * at-or-after `delayMillis` has elapsed.
    *
    * @param delayMillis
    *   the relative delay before the task runs, in milliseconds
    * @param task
    *   the runnable to execute after the delay
    * @return
    *   a [[TimerHandle]] that can be used to cancel the scheduled task before it fires
    */
  def scheduleAfter(delayMillis: Long, task: Runnable): TimerHandle

  /** Shuts down the timer, stopping the background tick thread. */
  def shutdown(): Unit
}

/** Handle for a scheduled timer task.
  *
  * Returned by [[TimerService.schedule]]. Calling [[cancel]] before the task fires prevents the
  * task from running; after the task has fired, [[cancel]] is a no-op. Cancellation is intended to
  * be safe under concurrent fire-vs-cancel races.
  */
private[eru] trait TimerHandle {

  /** Cancel the scheduled task if it hasn't already fired.
    *
    * Safe to call from any thread. Idempotent — multiple calls are no-ops after the first.
    */
  def cancel(): Unit
}

private[eru] object TimerHandle {

  /** A no-op handle used by timer implementations that do not support cancellation or by fallback
    * paths where there is nothing to cancel.
    */
  val NoOp: TimerHandle = () => ()
}

private[eru] object TimerService {
  private val currentTimer: ThreadLocal[Option[TimerService]] =
    ThreadLocal.withInitial(() => None)

  private val defaultProvider: java.util.concurrent.atomic.AtomicReference[Option[() => Option[TimerService]]] =
    new java.util.concurrent.atomic.AtomicReference(None)

  /** Resolve the TimerService visible at the current interpretation site.
    *
    * Thread-local first (runtime-scoped push/pop), then the write-once default provider. `None`
    * means no timer is reachable and the `At` interpreter must fall back to inline execution.
    */
  def get: Option[TimerService] = currentTimer.get() match {
    case some @ Some(_) => some
    case None =>
      defaultProvider.get() match {
        case Some(provider) => provider()
        case None => None
      }
  }

  /** Set the thread-local to a specific timer (or clear it). Used by fork / race / handleSuspend
    * entry points to capture-and-restore the forking runtime's timer across VT boundaries. VTs do
    * not inherit ThreadLocal values, so every spawned VT that will interpret an Eru value must be
    * primed by the spawning site.
    */
  private[eru] def setCurrent(t: Option[TimerService]): Unit = currentTimer.set(t)

  /** Run `action` with `t` bound to the thread-local, restoring the prior binding in a finally.
    * Mirrors the `StructuredConcurrency.withNewScope` push/pop shape.
    */
  private[eru] def withTimer[A](t: TimerService)(action: => A): A = {
    val prior = currentTimer.get()
    currentTimer.set(Some(t))
    try action
    finally currentTimer.set(prior)
  }

  /** Install the default-provider invoked when no thread-local binding is present. Write-once via
    * compareAndSet: the first caller wins (by convention, `EruRuntime.shared`'s lazy initializer).
    * Subsequent calls are no-ops, so `EruRuntime.create()` cannot clobber the shared fallback.
    */
  private[eru] def installDefaultProvider(provider: () => Option[TimerService]): Unit = {
    val _ = defaultProvider.compareAndSet(None, Some(provider))
  }
}
