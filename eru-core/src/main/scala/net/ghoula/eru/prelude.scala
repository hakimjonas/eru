package net.ghoula.eru

/** The single entry point for the Eru effect system.
  *
  * This object provides the complete public API of the eru-core module through a single import:
  * `import net.ghoula.eru.prelude.*`
  *
  * The prelude embodies Eru's commitment to "Radical Ergonomics" by consolidating the entire
  * surface area of the library into one discoverable namespace. This design follows modern Scala 3
  * practices, using export clauses to curate a cohesive developer experience.
  *
  * Everything a developer needs for 99% of use cases is available through this single import:
  *   - Core types: Eru, Result
  *   - Domain types: AttemptCount, JitterFactor, FailureThreshold
  *   - Factory methods: succeed, fail, effect, blocking, etc.
  *   - All extension methods: caching, resource safety, error handling, tracing
  *   - Supporting types: EruException, EruObserver, Exit, ErrorHandling patterns, Trace types
  *
  * This approach makes the library feel like a natural extension of the Scala language while
  * maintaining the principle of "Guided Correctness" by making the safest, most powerful patterns
  * always available and discoverable.
  */
object prelude {

  // ===== CORE TYPES =====

  /** The core effect type representing a pure, lazy, composable computation. */
  type Eru[+E, +A] = net.ghoula.eru.Eru[E, A]
  val Eru = net.ghoula.eru.Eru

  /** The foundational result type for fallible computations. */
  type Result[+E, +A] = net.ghoula.eru.Result[E, A]
  val Result = net.ghoula.eru.Result

  // ===== CORE TYPE COMPANIONS AND FACTORY METHODS =====

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

  // ===== DOMAIN TYPES =====

  /** Export all domain types with compile-time safety constraints. */
  export net.ghoula.eru.DomainTypes.*

  // ===== EXCEPTION TYPES =====

  /** The exception type for failed computations. */
  type EruException[E] = net.ghoula.eru.EruException[E]

  /** Export the EruException companion object. */
  export net.ghoula.eru.EruException.*

  // ===== OBSERVABILITY =====

  /** Export the EruObserver type and companion object, including EruEvent. */
  export net.ghoula.eru.EruObserver.*

  // ===== EXIT AND TERMINATION =====

  /** Export all Exit types and companion methods. */
  export net.ghoula.eru.Exit

  // ===== ERROR HANDLING PATTERNS =====

  /** Export all sophisticated error handling patterns and types. */
  export net.ghoula.eru.patterns.ErrorHandling.*

  // ===== TRACING AND OBSERVABILITY =====

  /** Export all tracing types and functionality. */
  export net.ghoula.eru.trace.EruTrace.*

  // ===== EXTENSION METHODS =====

  /** Export all extension methods from the internal extensions object. */
  export net.ghoula.eru.internal.extensions.*
}
