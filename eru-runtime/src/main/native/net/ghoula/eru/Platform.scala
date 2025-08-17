package net.ghoula.eru

import java.time.Duration

/** Native-specific platform implementations. */
private[eru] object Platform {

  /** Native timer implementation (stub for compilation).
    *
    * This is a minimal stub that executes tasks immediately to allow compilation.
    * A full implementation would use platform-specific timer mechanisms.
    */
  val timer: Timer = new Timer {
    def schedule(delay: Duration, task: () => Unit): Unit = {
      // For now, execute immediately to avoid blocking
      // TODO: Implement proper Native timer when needed
      task()
    }
  }
}
