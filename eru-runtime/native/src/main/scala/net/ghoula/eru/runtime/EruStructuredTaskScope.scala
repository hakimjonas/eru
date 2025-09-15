package net.ghoula.eru.runtime

import net.ghoula.eru.*

/** Structured Concurrency stub for Scala Native.
  *
  * StructuredTaskScope is not available on Scala Native, so this provides no-op implementations
  * that maintain API compatibility while falling back to basic sequential execution.
  *
  * For full Structured Concurrency functionality, use the JVM version of Eru.
  */
object EruStructuredTaskScope {

  /** Always false on Native since StructuredTaskScope is not available. */
  val isAvailable: Boolean = false

  /** Task scope policy enum for API compatibility. */
  enum TaskScopePolicy {
    case FailFast
    case AllSucceed
    case Race
  }

  /** Stub implementation for Native compatibility. */
  class EruTaskScope[T] private (policy: TaskScopePolicy) extends AutoCloseable {
    val _ = policy // Acknowledge unused parameter
    def close(): Unit = ()
  }

  object EruTaskScope {
    def apply[T](policy: TaskScopePolicy): EruTaskScope[T] = new EruTaskScope[T](policy)
    def failFast[T]: EruTaskScope[T] = apply(TaskScopePolicy.FailFast)
    def allSucceed[T]: EruTaskScope[T] = apply(TaskScopePolicy.AllSucceed)
    def race[T]: EruTaskScope[T] = apply(TaskScopePolicy.Race)
  }

  /** High-level operations with fallback implementations for Native. */
  object StructuredOps {

    /** Fallback to sequential execution on Native. */
    def forkAll[E, A](
      effects: List[Eru[E, A]],
      policy: TaskScopePolicy = TaskScopePolicy.FailFast
    ): Eru[E | Throwable, List[A]] = {
      val _ = policy // Acknowledge unused parameter
      // Sequential execution on Native
      Eru.sequence(effects)
    }

    /** Fallback to first effect on Native. */
    def race[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, A] = {
      // Return first effect on Native
      effects.headOption.getOrElse(Eru.effect(throw new RuntimeException("No effects to race")))
    }

    /** Fallback to sequential collection on Native. */
    def collectPar[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[Either[E, A]]] = {
      // Sequential execution with attempt on Native
      import net.ghoula.eru.prelude.fold
      Eru.sequence(effects.map(_.attempt.map(result => result.fold(Left(_), Right(_)))))
    }
  }
}
