package net.ghoula.eru

/** Observability primitives for Eru program execution.
  *
  * These types define a minimal, synchronous footprint that allows users to observe lifecycle and
  * step events when running programs via `unsafeRunSyncWith`. The shapes are designed to evolve
  * naturally with fibers and async runtime in 0.3.0.
  */
object EruObserver {

  /** A stable identifier for a single program run.
    *
    * In 0.2.0 this identifies the scope of a single `unsafeRunSyncWith` invocation. In 0.3.0+ each
    * fiber will have its own identity.
    */
  opaque type ScopeId = Long

  object ScopeId {
    private var next: Long = 1L

    /** Generates a fresh [[ScopeId]]. */
    def fresh(): ScopeId = {
      val id = next
      next = next + 1L
      id
    }
  }

  /** Structured outcome used at program end events.
    *
    *   - Success: the program produced a value
    *   - TypedFailure(error): the program failed with a typed, non-Throwable error value
    *   - Defect(throwable): the program failed with an untyped exception (Throwable)
    */
  enum Outcome {
    case Success
    case TypedFailure(error: Any)
    case Defect(throwable: Throwable)
  }

  /** Events emitted by the observer-aware interpreter.
    *
    * Each event carries a ScopeId that identifies the run scope.
    */
  enum EruEvent {
    case ProgramStart(scopeId: ScopeId)
    case ProgramEnd(scopeId: ScopeId, outcome: Outcome)
    case Step(scopeId: ScopeId, label: String)
  }

  /** Observer interface for receiving events.
    *
    * Implementations should be side-effecting (e.g., logging) and strive to be low-overhead.
    */
  trait EruObserver {
    def onEvent(event: EruEvent): Unit
  }
}

export EruObserver.*
