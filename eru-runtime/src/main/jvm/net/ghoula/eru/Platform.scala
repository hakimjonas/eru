package net.ghoula.eru

import java.time.Duration

/** JVM-specific platform implementations. */
private[eru] object Platform {

  /** JVM timer implementation using java.util.Timer. */
  val timer: Timer = new Timer {
    private val jvmTimer = new java.util.Timer("eru-runtime-timer", true)

    def schedule(delay: Duration, task: () => Unit): Unit =
      try jvmTimer.schedule(
        new java.util.TimerTask { def run(): Unit = task() },
        Math.max(0L, delay.toMillis)
      )
      catch { case _: Throwable => task() }
  }
}
