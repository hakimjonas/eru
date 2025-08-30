package net.ghoula.eru.internal

import java.time.{Duration, Instant}

import net.ghoula.eru.EruObserver.EruEvent
import net.ghoula.eru.{patterns, trace, DomainTypes, Eru, Result}

/** Consolidated extension methods for Eru.
  *
  * Groups extensions by type:
  *   - Result[E, A]: map, flatMap, fold, and basic queries
  *   - Eru[E, A]: resource patterns, error handling, debugging, and supporting combinators
  */
object extensions {

  /** Extension methods providing the core API for `Result[E, A]`.
    *
    * Provides a fluent, discoverable API for transforming and inspecting results.
    */
  extension [E, A](result: Result[E, A]) {

    /** Transforms the success value of this `Result` using the given function.
      *
      * If this `Result` is a `Success`, applies the function to the contained value and returns a
      * new `Success` with the transformed value. If this `Result` is a `Failure`, returns the
      * failure unchanged.
      *
      * This operation preserves the error type and maintains referential transparency.
      *
      * @param f
      *   the function to apply to the success value
      * @tparam B
      *   the type of the transformed success value
      * @return
      *   a `Result[E, B]` with the transformed success value or the original failure
      */
    def map[B](f: A => B): Result[E, B] = result match {
      case Result.Success(value) => Result.Success(f(value))
      case Result.Failure(error) => Result.Failure(error)
    }

    /** Transforms the success value of this `Result` using a function that returns another
      * `Result`.
      *
      * If this `Result` is a `Success`, applies the function to the contained value and returns the
      * resulting `Result`. If this `Result` is a `Failure`, returns the failure unchanged.
      *
      * This is the monadic bind operation for `Result`, enabling sequential composition of fallible
      * computations. The error types are unified through the upper bound `E1 >: E`.
      *
      * @param f
      *   the function to apply to the success value, returning a `Result`
      * @tparam E1
      *   the unified error type (supertype of `E`)
      * @tparam B
      *   the type of the new success value
      * @return
      *   a `Result[E1, B]` representing the composed computation
      */
    def flatMap[E1 >: E, B](f: A => Result[E1, B]): Result[E1, B] = result match {
      case Result.Success(value) => f(value)
      case Result.Failure(error) => Result.Failure(error)
    }

    /** Transforms this `Result` into a value of type `B` by applying one of two functions.
      *
      * If this `Result` is a `Success`, applies `ifSuccess` to the contained value. If this
      * `Result` is a `Failure`, applies `ifFailure` to the contained error.
      *
      * This is the catamorphism for `Result`, providing a way to extract values from both success
      * and failure cases in a type-safe manner.
      *
      * @param ifFailure
      *   the function to apply if this `Result` is a failure
      * @param ifSuccess
      *   the function to apply if this `Result` is a success
      * @tparam B
      *   the type of the result value
      * @return
      *   the result of applying the appropriate function
      */
    def fold[B](ifFailure: E => B, ifSuccess: A => B): B =
      Result.fold(result)(ifFailure, ifSuccess)

    /** Returns `true` if this `Result` is a `Success`, `false` otherwise.
      *
      * This provides a convenient way to check the state of a `Result` without pattern matching or
      * extracting values.
      *
      * @return
      *   `true` if this `Result` represents success
      */
    def isSuccess: Boolean = result match {
      case Result.Success(_) => true
      case Result.Failure(_) => false
    }

    /** Returns `true` if this `Result` is a `Failure`, `false` otherwise.
      *
      * This provides a convenient way to check the state of a `Result` without pattern matching or
      * extracting values.
      *
      * @return
      *   `true` if this `Result` represents failure
      */
    def isFailure: Boolean = result match {
      case Result.Success(_) => false
      case Result.Failure(_) => true
    }
  }

  /** Extension methods providing built-in caching and resource helpers for `Eru[E, A]`.
    *
    * Makes common operations discoverable as natural extensions of the Eru type. Timeout and retry
    * functionality are available from the runtime module to avoid circular dependencies.
    */
  extension [E, A](eru: Eru[E, A]) {

    /** Ensures multiple finalizers run in FILO order, providing a more ergonomic way to chain
      * multiple cleanup operations.
      *
      * This method makes it easy to add multiple cleanup operations without nested .ensure calls,
      * improving readability, and making the resource cleanup chain more visible.
      *
      * @param finalizers
      *   variable number of finalizers to run in reverse order
      * @return
      *   an effect that guarantees all finalizers run after this effect completes
      */
    def ensureAll(finalizers: Eru[Any, Unit]*): Eru[E, A] = {
      finalizers.foldLeft(eru) { (acc, finalizer) =>
        acc.ensure(finalizer)
      }
    }

    /** Creates a resource-safe computation that automatically calls a cleanup function on the
      * success value, regardless of whether the subsequent computation succeeds or fails.
      *
      * This is particularly useful for resources that need cleanup based on their value, such as
      * closing file handles, database connections, or network resources.
      *
      * @param cleanup
      *   function to extract cleanup logic from the success value
      * @tparam F
      *   the error type of the cleanup operation
      * @return
      *   an effect that will automatically clean up the resource
      */
    def autoCleanup[F](cleanup: A => Eru[F, Unit]): Eru[E, A] = {
      eru.flatMap(value => Eru.succeed(value).ensure(cleanup(value)))
    }

    /** Provides automatic resource management for AutoCloseable resources.
      *
      * This method automatically handles closing of AutoCloseable resources (like files, streams,
      * database connections) by calling their close() method in a finalizer, making resource
      * management completely automatic.
      *
      * @param ev
      *   evidence that A is an AutoCloseable
      * @return
      *   an effect that automatically closes the resource
      */
    def autoClose(implicit ev: A <:< AutoCloseable): Eru[E, A] = {
      eru.autoCleanup(resource => Eru.effect(ev(resource).close()))
    }

    /** Creates a scoped resource that provides the resource to a use function and ensures cleanup.
      *
      * This is a more ergonomic alternative to bracket that reads more naturally and makes the
      * resource scoping more explicit. The resource is guaranteed to be cleaned up regardless of
      * how the use function terminates.
      *
      * @param use
      *   function that uses the resource and produces a result
      * @param cleanup
      *   function to clean up the resource
      * @tparam E1
      *   the unified error type
      * @tparam F
      *   the error type of the cleanup operation
      * @tparam B
      *   the result type of the use function
      * @return
      *   an effect that uses the resource safely
      */
    def useScoped[E1 >: E, F, B](use: A => Eru[E1, B])(cleanup: A => Eru[F, Unit]): Eru[E1, B] = {
      eru.bracket(cleanup)(use)
    }

    /** Creates a resource pool entry that can be safely returned to a pool after use.
      *
      * This method is designed for integration with resource pools where resources need to be
      * returned rather than destroyed after use.
      *
      * @param returnToPool
      *   function to return the resource to its pool
      * @tparam F
      *   the error type of the return operation
      * @return
      *   an effect that ensures the resource is returned to the pool
      */
    def pooled[F](returnToPool: A => Eru[F, Unit]): Eru[E, A] = {
      eru.autoCleanup(returnToPool)
    }

    /** Wraps this effect with resource validation to ensure a proper resource lifecycle.
      *
      * This method first validates the resource. If successful, it proceeds with the resource while
      * scheduling a post-use validation check. This post-use check, which runs in a finalizer, is
      * non-failing and serves to detect potential resource corruption or unexpected state changes
      * after use without altering the outcome of the main computation.
      *
      * @param validate
      *   function to validate the resource state, run before and after the resource is used.
      * @param description
      *   human-readable description of what is being validated, used in the failure message.
      * @return
      *   an effect that validates the resource lifecycle before and after use.
      */
    def validateResource(validate: A => Boolean, description: String): Eru[E | String, A] = {
      eru.flatMap { resource =>
        if (validate(resource)) {
          Eru.succeed(resource).ensure {
            Eru.effect {
              validate(resource)
              ()
            }
          }
        } else {
          Eru.fail(s"Resource validation failed: $description")
        }
      }
    }

    /** Protects this effect with a circuit breaker. */
    def withCircuitBreaker(
      circuitBreaker: patterns.ErrorHandling.CircuitBreaker
    ): Eru[E | patterns.ErrorHandling.CircuitBreakerOpen, A] = {
      circuitBreaker.protect(eru)
    }

    /** Combines multiple effects, accumulating errors if they fail. */
    def accumulateErrors[E1 >: E](other: Eru[E1, A]): Eru[patterns.ErrorHandling.ErrorAccumulator[E1], (A, A)] = {
      import DomainTypes.*
      import patterns.ErrorHandling.*

      for {
        firstResult <- eru.attempt
        secondResult <- other.attempt
        combined <- (firstResult, secondResult) match {
          case (Result.Success(a), Result.Success(b)) =>
            Eru.succeed((a, b))

          case (Result.Failure(e1), Result.Success(_)) =>
            Eru.fail(ErrorAccumulator.empty[E1].add(e1, AttemptCount(1)))

          case (Result.Success(_), Result.Failure(e2)) =>
            Eru.fail(ErrorAccumulator.empty[E1].add(e2, AttemptCount(1)))

          case (Result.Failure(e1), Result.Failure(e2)) =>
            val accumulator = ErrorAccumulator
              .empty[E1]
              .add(e1, AttemptCount(1))
              .add(e2, AttemptCount(2))
            Eru.fail(accumulator)
        }
      } yield combined
    }

    /** Validates this effect's result and accumulates validation errors. */
    def validate[V](validations: (A => Eru[V, Unit])*): Eru[E | patterns.ErrorHandling.ErrorAccumulator[V], A] = {
      import DomainTypes.*
      import patterns.ErrorHandling.*

      eru.flatMap { value =>
        val validationResults = validations.map(validate => validate(value).attempt)

        def collectErrors(
          remaining: List[Eru[Nothing, Result[V, Unit]]],
          accumulator: ErrorAccumulator[V],
          validationIndex: Int
        ): Eru[ErrorAccumulator[V], A] = {
          remaining match {
            case Nil =>
              if (accumulator.nonEmpty) Eru.fail(accumulator)
              else Eru.succeed(value)

            case head :: tail =>
              head.flatMap {
                case Result.Success(_) =>
                  collectErrors(tail, accumulator, validationIndex + 1)
                case Result.Failure(error) =>
                  val updatedAccumulator = accumulator.add(error, AttemptCount(validationIndex + 1))
                  collectErrors(tail, updatedAccumulator, validationIndex + 1)
              }
          }
        }

        collectErrors(validationResults.toList, ErrorAccumulator.empty[V], 0)
      }
    }

    /** Provides fallback values for specific error conditions. */
    def fallback[E1 >: E, A1 >: A](fallbacks: PartialFunction[E1, A1]): Eru[E1, A1] = {
      eru.recoverWith { error =>
        if (fallbacks.isDefinedAt(error)) {
          Eru.succeed(fallbacks(error))
        } else {
          Eru.fail(error)
        }
      }
    }

    /** Adds contextual information to errors for better debugging. */
    def contextualizeError[E1](f: E => E1): Eru[E1, A] = {
      eru.mapError(f)
    }

    /** Times out with a specific error rather than a generic TimeoutException. */
    def failAfter[E1 >: E](_timeout: Duration, _timeoutError: E1): Eru[E1, A] = {
      val _ = (_timeout, _timeoutError)
      eru
    }

    /** Wraps this effect with a trace span for observability.
      *
      * This method creates a new span for this effect's execution, providing detailed timing and
      * context information for debugging and performance analysis. The span integrates with the
      * existing EruObserver pattern.
      *
      * @param operation
      *   name of the operation for the span
      * @param tags
      *   additional context tags for the span
      * @return
      *   an effect that executes within a trace span
      */
    def traced(operation: String, tags: Map[String, String] = Map.empty): Eru[E | Throwable, A] = {
      import trace.EruTrace.*

      Eru.effect {
        val context = getCurrentContext.getOrElse(startTrace("root-trace"))
        val (newContext, span) = context.createChildSpan(operation)
        val taggedSpan = span.withTags(tags)

        setCurrentContext(Some(newContext.copy(currentSpan = Some(taggedSpan))))
        (newContext, taggedSpan)
      }.flatMap { case (_, span) =>
        eru.attempt.map { result =>
          val completedSpan = result match {
            case Result.Success(value) =>
              span.complete(SpanStatus.Success)
            case Result.Failure(error) =>
              val errorMsg = error match {
                case t: Throwable => t.getMessage
                case other => other.toString
              }
              span.complete(SpanStatus.Error(errorMsg))
          }

          EruEvent.TraceSpan(completedSpan)

          result
        }.flatMap {
          case Result.Success(value) => Eru.succeed(value)
          case Result.Failure(error) => Eru.fail(error)
        }
      }
    }

    /** Adds a trace event at this point in the effect execution.
      *
      * This is useful for marking important milestones or checkpoints within a larger operation for
      * detailed performance analysis.
      *
      * @param eventName
      *   name of the event
      * @param attributes
      *   additional context for the event
      * @return
      *   the effect unchanged, with a trace event recorded
      */
    def traceEvent(eventName: String, attributes: Map[String, String] = Map.empty): Eru[E, A] = {
      import trace.EruTrace.*

      eru.map { value =>
        getCurrentContext.flatMap(_.currentSpan) match {
          case Some(span) =>
            val event = SpanEvent(
              timestamp = Instant.now(),
              name = eventName,
              attributes = attributes
            )
            val updatedSpan = span.withEvent(event)
            val updatedContext = getCurrentContext.get.copy(currentSpan = Some(updatedSpan))
            setCurrentContext(Some(updatedContext))
          case None =>
        }
        value
      }
    }

    /** Adds baggage (trace-wide context) to the current trace.
      *
      * Baggage flows through the entire trace and can be used to propagate important context like
      * user IDs, request IDs, or feature flags.
      *
      * @param key
      *   baggage key
      * @param value
      *   baggage value
      * @return
      *   the effect unchanged, with baggage added to trace context
      */
    def withTraceBaggage(key: String, value: String): Eru[E, A] = {
      import trace.EruTrace.*

      eru.map { result =>
        getCurrentContext match {
          case Some(context) =>
            val updatedContext = context.withBaggage(key, value)
            setCurrentContext(Some(updatedContext))
          case None =>
            val context = startTrace("implicit-trace").withBaggage(key, value)
            setCurrentContext(Some(context))
        }
        result
      }
    }

    /** Performs compile-time analysis to detect antipatterns and guide best practices.
      *
      * This provides the ergonomic `myEffect.validated` API, moving away from the verbose
      * `EruMacros.validated(myEffect)` pattern. This extension method delegates to the macro
      * implementation while providing a discoverable, fluent interface.
      *
      * @return
      *   the original expression unchanged, with compile-time diagnostics reported
      */
    inline def validated: Eru[E, A] = {
      net.ghoula.eru.meta.EruMacros.validated(eru)
    }

    /** Applies compile-time optimizations to improve performance without changing semantics.
      *
      * This provides the ergonomic `myEffect.optimize` API, moving away from the verbose
      * `EruMacros.optimize(myEffect)` pattern. This extension method delegates to the macro
      * implementation while providing a discoverable, fluent interface.
      *
      * @return
      *   an optimized version of the effect with improved performance characteristics
      */
    inline def optimize: Eru[E, A] = {
      net.ghoula.eru.meta.EruMacros.optimize(eru)
    }
  }
}
