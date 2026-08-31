package net.ghoula.eru.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import net.ghoula.eru.{TimerHandle, TimerService}

/** A hashed wheel timer with configurable tick resolution.
  *
  * This is the JVM implementation of `TimerService` that schedules tasks with O(1) insert and
  * O(bucket_size) per-tick cost. A single daemon virtual thread advances the wheel, draining one
  * bucket per tick and firing due tasks as new virtual threads.
  *
  * With default parameters (10ms tick, 4096 slots), the wheel covers ~40.96 seconds per full
  * rotation. Tasks scheduled further into the future use a `rounds` counter that decrements each
  * time the wheel passes their bucket.
  *
  * 100K entries across 4096 buckets = ~24 entries per bucket on average.
  *
  * @param tickDurationMs
  *   milliseconds per tick (default 10)
  * @param ticksPerWheel
  *   number of buckets, should be a power of 2 for fast modulo (default 4096)
  */
private[eru] final class HashedTimerWheel(
  tickDurationMs: Long = 10L,
  ticksPerWheel: Int = 4096,
  clock: () => Long = () => System.currentTimeMillis(),
  daemonEnabled: Boolean = true,
  taskRunner: Runnable => Unit = r => { Thread.startVirtualThread(r); () }
) extends TimerService {

  private final class TimerEntry(
    val task: Runnable,
    @volatile var rounds: Int
  ) extends TimerHandle {

    /** Set to `true` by [[cancel]]. Checked by `drainBucket` before firing. */
    private val canceled: AtomicBoolean = new AtomicBoolean(false)

    def isCanceled: Boolean = canceled.get()

    /** Marks the entry canceled, idempotently (via CAS). `drainBucket` drops the entry when it
      * polls it and sees `isCanceled`.
      *
      * The `task` closure is deliberately not released here: releasing it would require storing a
      * nullable reference, which violates the project's no-null policy. Retention of the canceled
      * closure is bounded — it lives until the wheel rotates to its bucket, at most one full
      * rotation per `rounds` count.
      */
    def cancel(): Unit = {
      val _ = canceled.compareAndSet(false, true)
    }
  }

  private val mask: Int = ticksPerWheel - 1
  private val wheel: Array[ConcurrentLinkedQueue[TimerEntry]] =
    Array.fill(ticksPerWheel)(new ConcurrentLinkedQueue[TimerEntry]())
  private val currentTick = new AtomicLong(0L)
  private val running = new AtomicBoolean(true)
  private val daemonThreadRef = new AtomicReference[Option[Thread]](None)
  private val scheduleCalls = new AtomicLong(0L)

  /** Test-only: total number of completed `schedule` calls since construction. Used by correctness-
    * invariant tests to prove which wheel a scheduling call routed through. Production code must
    * not depend on this.
    */
  private[eru] def scheduleCountForTests: Long = scheduleCalls.get()

  if (daemonEnabled) startDaemon()

  private def startDaemon(): Unit = {
    val thread = Thread.startVirtualThread { () =>
      var alive = true
      while (alive && running.get()) {
        try {
          Thread.sleep(tickDurationMs)
        } catch {
          case _: InterruptedException =>
            alive = running.get()
        }

        if (alive && running.get()) {
          tick()
        }
      }
    }
    daemonThreadRef.set(Some(thread))
  }

  private[eru] def tick(): Unit = {
    val t = currentTick.incrementAndGet()
    val bucketIdx = (t & mask).toInt
    val bucket = wheel(bucketIdx)
    val requeue = new java.util.ArrayList[TimerEntry]()
    drainBucket(bucket, requeue)
    val it = requeue.iterator()
    while (it.hasNext) bucket.add(it.next())
  }

  /** Drains one bucket: drops canceled entries, decrements `rounds` and requeues entries not yet
    * due, and fires due entries.
    *
    * A cancel concurrent with the drain (after the `isCanceled` check but before firing) is allowed
    * to fire once — `cancel` means "don't fire if not already fired", not "undo a started fire" —
    * so the fired branch does not re-check `isCanceled`.
    */
  @annotation.tailrec
  private def drainBucket(
    bucket: ConcurrentLinkedQueue[TimerEntry],
    requeue: java.util.ArrayList[TimerEntry]
  ): Unit = {
    Option(bucket.poll()) match {
      case Some(entry) if entry.isCanceled =>
        drainBucket(bucket, requeue)
      case Some(entry) if entry.rounds > 0 =>
        entry.rounds -= 1
        requeue.add(entry)
        drainBucket(bucket, requeue)
      case Some(entry) =>
        taskRunner(entry.task)
        drainBucket(bucket, requeue)
      case None => ()
    }
  }

  /** Schedules a task to fire after `delayMillis` of (approximately) elapsed time. O(1) insertion
    * into the appropriate wheel bucket. Returns a [[TimerHandle]] that can cancel the task before
    * it fires.
    *
    * This is the duration-based primitive — the wheel fires the task after `delayMillis` elapses on
    * the daemon's tick stream, which advances independently of wall-clock state. The deadline never
    * touches `System.currentTimeMillis()`. This is the path the typed `Monotonic` capability
    * ultimately rests on.
    *
    * The delay is converted to ticks with ceiling division plus a one-tick pad: ceiling alone
    * rounds a delay up to the next whole tick but still lets a task scheduled late in the current
    * tick fire at the next boundary — up to `tickDurationMs - 1` early relative to the requested
    * delay. The pad makes the wheel fire at-or-after for any schedule phase; worst-case oversleep
    * is under two ticks.
    */
  def scheduleAfter(delayMillis: Long, task: Runnable): TimerHandle = {
    val safeDelayMs = math.max(0L, delayMillis)
    val delayTicks = math.max(2L, (safeDelayMs + tickDurationMs - 1) / tickDurationMs + 1L)
    val rounds = (delayTicks / ticksPerWheel).toInt
    val bucketOffset = (delayTicks % ticksPerWheel).toInt
    val current = currentTick.get()
    val targetBucket = ((current + bucketOffset) & mask).toInt

    val entry = new TimerEntry(task, rounds)
    wheel(targetBucket).add(entry)
    val _ = scheduleCalls.incrementAndGet()
    entry
  }

  /** Schedules a task for execution at the given epoch time. Computes the delay relative to
    * `clock()` and delegates to [[scheduleAfter]]. Retained for callers that genuinely think in
    * absolute-time terms (test code, downstream wall-time scheduling).
    *
    * Production sleep / timeout / retry paths SHOULD use [[scheduleAfter]] directly to avoid the
    * round-trip read of `clock()` and to keep the deadline in pure duration space.
    */
  def schedule(epochMillis: Long, task: Runnable): TimerHandle = {
    val delayMs = epochMillis - clock()
    scheduleAfter(delayMs, task)
  }

  /** Stops the daemon tick thread. Idempotent — subsequent calls are no-ops. */
  def shutdown(): Unit = {
    if (running.compareAndSet(true, false)) {
      daemonThreadRef.get().foreach(_.interrupt())
    }
  }
}
