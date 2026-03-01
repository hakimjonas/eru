package net.ghoula.eru.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import net.ghoula.eru.TimerService

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
    val epochMillis: Long,
    val task: Runnable,
    @volatile var rounds: Int
  )

  private val mask: Int = ticksPerWheel - 1
  private val wheel: Array[ConcurrentLinkedQueue[TimerEntry]] =
    Array.fill(ticksPerWheel)(new ConcurrentLinkedQueue[TimerEntry]())
  private val currentTick = new AtomicLong(0L)
  private val running = new AtomicBoolean(true)
  private val daemonThreadRef = new AtomicReference[Option[Thread]](None)

  // Start the background tick thread
  if (daemonEnabled) startDaemon()

  private def startDaemon(): Unit = {
    val thread = Thread.startVirtualThread { () =>
      while (running.get()) {
        try {
          Thread.sleep(tickDurationMs)
        } catch {
          case _: InterruptedException => ()
        }

        if (running.get()) {
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
    requeue.forEach(entry => bucket.add(entry))
  }

  @annotation.tailrec
  private def drainBucket(
    bucket: ConcurrentLinkedQueue[TimerEntry],
    requeue: java.util.ArrayList[TimerEntry]
  ): Unit = {
    Option(bucket.poll()) match {
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

  /** Schedules a task for execution at the given epoch time. O(1) insertion into the appropriate
    * wheel bucket.
    */
  def schedule(epochMillis: Long, task: Runnable): Unit = {
    val now = clock()
    val delayMs = math.max(0L, epochMillis - now)
    val delayTicks = math.max(1L, delayMs / tickDurationMs)
    val rounds = (delayTicks / ticksPerWheel).toInt
    val bucketOffset = (delayTicks % ticksPerWheel).toInt
    val current = currentTick.get()
    val targetBucket = ((current + bucketOffset) & mask).toInt

    wheel(targetBucket).add(new TimerEntry(epochMillis, task, rounds))
  }

  /** Stops the daemon tick thread. Idempotent — subsequent calls are no-ops. */
  def shutdown(): Unit = {
    if (running.compareAndSet(true, false)) {
      daemonThreadRef.get().foreach(_.interrupt())
    }
  }
}
