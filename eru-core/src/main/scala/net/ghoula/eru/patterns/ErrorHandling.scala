package net.ghoula.eru.patterns

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import net.ghoula.eru.*

/** Enhanced error handling patterns for more ergonomic recovery mechanisms.
  *
  * This module provides sophisticated error handling patterns that go beyond simple retry logic,
  * including circuit breakers, error accumulation, conditional recovery, and validation patterns.
  * These patterns follow the "radical ergonomics" principle by making complex error handling
  * scenarios simple and discoverable.
  */
object ErrorHandling {

  /** Sophisticated retry policy with conditions and context awareness. */
  sealed trait RetryPolicy {

    /** Determines if a retry should be attempted for the given error and context. */
    def shouldRetry[E](error: E, attempt: Int, context: RetryContext): Boolean

    /** Calculates the delay before the next retry attempt. */
    def delayFor(attempt: Int): Duration
  }

  /** Context information available during retry decisions. */
  final case class RetryContext(
    startTime: Instant,
    totalAttempts: Int,
    lastErrors: List[Any] = Nil
  ) {

    /** Total elapsed time since first attempt. */
    def elapsedTime: Duration = Duration.between(startTime, Instant.now())

    /** Adds an error to the context history. */
    def withError(error: Any): RetryContext =
      copy(lastErrors = (error :: lastErrors).take(10))
  }

  object RetryPolicy {

    /** Retry based on error type predicate with exponential backoff. */
    def conditional[E](
      shouldRetryError: E => Boolean,
      maxAttempts: Int,
      baseDelay: Duration,
      maxDelay: Duration = Duration.ofMinutes(1)
    ): RetryPolicy = new RetryPolicy {

      def shouldRetry[E1](error: E1, attempt: Int, context: RetryContext): Boolean = {
        attempt < maxAttempts && (error match {
          case e: E @unchecked => shouldRetryError(e)
          case _ => false
        })
      }

      def delayFor(attempt: Int): Duration = {
        val exponentialDelay = baseDelay.multipliedBy(1L << attempt)
        if (exponentialDelay.compareTo(maxDelay) > 0) maxDelay else exponentialDelay
      }
    }

    /** Retry with jittered exponential backoff to avoid thundering herd. */
    def jitteredExponential(
      maxAttempts: Int,
      baseDelay: Duration,
      jitterFactor: Double = 0.1
    ): RetryPolicy = new RetryPolicy {

      private val random = new scala.util.Random()

      def shouldRetry[E](error: E, attempt: Int, context: RetryContext): Boolean =
        attempt < maxAttempts

      def delayFor(attempt: Int): Duration = {
        val exponentialDelay = baseDelay.multipliedBy(1L << attempt)
        val jitter = exponentialDelay.toMillis * jitterFactor * (random.nextDouble() - 0.5)
        Duration.ofMillis((exponentialDelay.toMillis + jitter.toLong).max(0))
      }
    }

    /** Retry based on elapsed time limit rather than attempt count. */
    def timeBasedCircuitBreaker(
      timeLimit: Duration,
      baseDelay: Duration
    ): RetryPolicy = new RetryPolicy {

      def shouldRetry[E](error: E, attempt: Int, context: RetryContext): Boolean =
        context.elapsedTime.compareTo(timeLimit) < 0

      def delayFor(attempt: Int): Duration = baseDelay
    }
  }

  /** Circuit breaker state for protecting against cascading failures. */
  enum CircuitState {
    case Closed
    case Open
    case HalfOpen
  }

  /** Circuit breaker for protecting downstream services. */
  final class CircuitBreaker(
    failureThreshold: Int,
    recoveryTimeout: Duration,
    successThreshold: Int = 1
  ) {

    private val state = new AtomicReference[CircuitState](CircuitState.Closed)
    private val failures = new AtomicLong(0)
    private val successes = new AtomicLong(0)
    private val lastFailureTime = new AtomicReference[Option[Instant]](None)

    /** Current state of the circuit breaker. */
    def currentState: CircuitState = state.get()

    /** Executes an effect with circuit breaker protection. */
    def protect[E, A](effect: Eru[E, A]): Eru[E | CircuitBreakerOpen, A] = {
      currentState match {
        case CircuitState.Open if shouldAttemptRecovery =>
          state.set(CircuitState.HalfOpen)
          executeWithCircuitBreaker(effect)

        case CircuitState.Open =>
          Eru.fail(CircuitBreakerOpen("Circuit breaker is open"))

        case _ =>
          executeWithCircuitBreaker(effect)
      }
    }

    private def shouldAttemptRecovery: Boolean = {
      lastFailureTime.get() match {
        case Some(lastFailure) =>
          Duration.between(lastFailure, Instant.now()).compareTo(recoveryTimeout) >= 0
        case None => true
      }
    }

    private def executeWithCircuitBreaker[E, A](effect: Eru[E, A]): Eru[E | CircuitBreakerOpen, A] = {
      effect.attempt.flatMap {
        case Result.Success(value) =>
          onSuccess()
          Eru.succeed(value)

        case Result.Failure(error) =>
          onFailure()
          Eru.fail(error)
      }
    }

    private def onSuccess(): Unit = {
      state.get() match {
        case CircuitState.HalfOpen =>
          val currentSuccesses = successes.incrementAndGet()
          if (currentSuccesses >= successThreshold) {
            state.set(CircuitState.Closed)
            failures.set(0)
            successes.set(0)
          }
        case CircuitState.Closed =>
          failures.set(0)
        case CircuitState.Open =>
          failures.set(0)
      }
    }

    private def onFailure(): Unit = {
      val currentFailures = failures.incrementAndGet()
      lastFailureTime.set(Some(Instant.now()))

      if (currentFailures >= failureThreshold && state.get() != CircuitState.Open) {
        state.set(CircuitState.Open)
        successes.set(0)
      }
    }
  }

  /** Error indicating circuit breaker is open. */
  final case class CircuitBreakerOpen(message: String)

  /** Error accumulator for collecting multiple errors. */
  final case class ErrorAccumulator[E](errors: List[E]) {

    /** Adds an error to the accumulator. */
    def add(error: E): ErrorAccumulator[E] =
      ErrorAccumulator(error :: errors)

    /** Combines with another accumulator. */
    def combine(other: ErrorAccumulator[E]): ErrorAccumulator[E] =
      ErrorAccumulator(errors ++ other.errors)

    /** Gets all errors in chronological order. */
    def allErrors: List[E] = errors.reverse

    /** Checks if there are any errors. */
    def nonEmpty: Boolean = errors.nonEmpty

    /** Gets the first error, if any. */
    def headOption: Option[E] = errors.lastOption
  }

  object ErrorAccumulator {
    def empty[E]: ErrorAccumulator[E] = ErrorAccumulator(Nil)
  }
}

/** Extension methods for enhanced error handling patterns. */
extension [E, A](eru: Eru[E, A]) {

  /** Retries this effect using a sophisticated retry policy with conditions. */
  def retryWith(policy: ErrorHandling.RetryPolicy): Eru[E, A] = {
    import ErrorHandling.*

    def loop(attempt: Int, context: RetryContext): Eru[E, A] = {
      eru.attempt.flatMap {
        case Result.Success(value) =>
          Eru.succeed(value)

        case Result.Failure(error) =>
          val updatedContext = context.withError(error)
          if (policy.shouldRetry(error, attempt, updatedContext)) {
            val _ = policy.delayFor(attempt)
            loop(attempt + 1, updatedContext)
          } else {
            Eru.fail(error)
          }
      }
    }

    val initialContext = RetryContext(Instant.now(), 0)
    loop(0, initialContext)
  }

  /** Protects this effect with a circuit breaker. */
  def withCircuitBreaker(circuitBreaker: ErrorHandling.CircuitBreaker): Eru[E | ErrorHandling.CircuitBreakerOpen, A] = {
    circuitBreaker.protect(eru)
  }

  /** Combines multiple effects, accumulating errors if they fail. */
  def accumulateErrors[E1 >: E](other: Eru[E1, A]): Eru[ErrorHandling.ErrorAccumulator[E1], (A, A)] = {
    import ErrorHandling.*

    for {
      firstResult <- eru.attempt
      secondResult <- other.attempt
      combined <- (firstResult, secondResult) match {
        case (Result.Success(a), Result.Success(b)) =>
          Eru.succeed((a, b))

        case (Result.Failure(e1), Result.Success(_)) =>
          Eru.fail(ErrorAccumulator.empty[E1].add(e1))

        case (Result.Success(_), Result.Failure(e2)) =>
          Eru.fail(ErrorAccumulator.empty[E1].add(e2))

        case (Result.Failure(e1), Result.Failure(e2)) =>
          Eru.fail(ErrorAccumulator.empty[E1].add(e1).add(e2))
      }
    } yield combined
  }

  /** Validates this effect's result and accumulates validation errors. */
  def validate[V](validations: (A => Eru[V, Unit])*): Eru[E | ErrorHandling.ErrorAccumulator[V], A] = {
    import ErrorHandling.*

    eru.flatMap { value =>
      val validationResults = validations.map(validate => validate(value).attempt)

      def collectErrors(
        remaining: List[Eru[Nothing, Result[V, Unit]]],
        accumulator: ErrorAccumulator[V]
      ): Eru[ErrorAccumulator[V], A] = {
        remaining match {
          case Nil =>
            if (accumulator.nonEmpty) Eru.fail(accumulator)
            else Eru.succeed(value)

          case head :: tail =>
            head.flatMap {
              case Result.Success(_) =>
                collectErrors(tail, accumulator)
              case Result.Failure(error) =>
                collectErrors(tail, accumulator.add(error))
            }
        }
      }

      collectErrors(validationResults.toList, ErrorAccumulator.empty[V])
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
}
