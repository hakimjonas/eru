package net.ghoula.eru

/** Core prelude for the Eru effect system.
  *
  * Exports:
  *   - Core types: Eru, Result
  *   - Domain types: AttemptCount, JitterFactor, FailureThreshold
  *   - Factory methods: succeed, fail, effect, blocking, fromEither, fromTry, fromOption, unit
  *   - Extension methods: resource safety, error handling, debugging, optimization
  *   - Supporting types: EruException, EruObserver, Exit, tracing and error patterns
  */
object CorePrelude {

  /** The core effect type representing a pure, lazy, composable computation. */
  type Eru[+E, +A] = net.ghoula.eru.Eru[E, A]
  val Eru = net.ghoula.eru.Eru

  /** The foundational result type for fallible computations. */
  type Result[+E, +A] = net.ghoula.eru.Result[E, A]
  val Result = net.ghoula.eru.Result

  /** Export all factory methods from the Eru companion object.
    *
    * This includes: succeed, fail, effect, blocking, suspend, fromEither, fromTry, fromOption, unit
    */
  export net.ghoula.eru.Eru.*

  /** Export Result companion object factory methods with qualified names to avoid conflicts.
    *
    * This includes: Result.succeed, Result.fail
    */
  export net.ghoula.eru.Result.{fail as resultFail, succeed as resultSucceed}

  /** Export all domain types with compile-time safety constraints. */
  export net.ghoula.eru.DomainTypes.*

  /** The exception type for failed computations. */
  type EruException[E] = net.ghoula.eru.EruException[E]

  /** Export the EruException companion object. */
  export net.ghoula.eru.EruException.*

  /** Export the EruObserver type and companion object, including EruEvent. */
  export net.ghoula.eru.EruObserver.*

  /** Export all Exit types and companion methods. */
  export net.ghoula.eru.Exit
  export net.ghoula.eru.InterruptCause

  /** Export error handling patterns and types. */
  export net.ghoula.eru.patterns.ErrorHandling.*

  /** Export all tracing types and functionality. */
  export net.ghoula.eru.trace.EruTrace.*

  /** Export all extension methods through the unified PreludeApi facade. */
  export net.ghoula.eru.api.PreludeApi.*
}
