package net.ghoula.eru.internal

import net.ghoula.eru.*

/** Additional runner conveniences that expose structured execution and observer-aware runs.
  *
  * These extensions provide alternative ways to execute Eru effects, offering more control
  * over the execution process and result handling without throwing exceptions at the API level.
  *
  * These methods are intended for advanced use cases where structured error handling or
  * observability integration is required.
  */
object extensions_runner {
  
  /** Extension methods for Eru instances providing structured execution. */
  extension [E, A](e: Eru[E, A]) {
    
    /** Executes this effect and returns the result as a structured Exit value.
      *
      * This method provides a way to execute effects without throwing exceptions, instead
      * returning a structured result that can represent success, failure, or defect states.
      * This is useful for cases where you need to handle errors at the value level rather
      * than through exception handling.
      *
      * @return
      *   an Exit value representing the structured result of the execution
      */
    def runExit(): Exit[E, A] =
      e.attempt.map(Result.toExit).unsafeRunSync()

    /** Executes this effect with the provided observer for enhanced observability.
      *
      * This method allows for observer-aware execution, enabling detailed monitoring and
      * diagnostics of effect execution. If an observer-aware interpreter exists, it will
      * be used; otherwise, it falls back to the standard execution method.
      *
      * @param observer
      *   the observer to receive execution events
      * @return
      *   the result of executing the effect with observability
      */
    def runWith(observer: EruObserver): A =
      // If an observer-aware interpreter exists, delegate to it; otherwise fallback to run()
      e.unsafeRunSyncWith(observer)
  }
}