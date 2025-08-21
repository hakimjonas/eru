package net.ghoula.eru.patterns

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference

import net.ghoula.eru.*
import net.ghoula.eru.{DataClassUtils, DomainTypes}

/** Enhanced error handling patterns for more ergonomic recovery mechanisms.
  *
  * This module provides sophisticated error handling patterns that go beyond simple retry logic,
  * including circuit breakers, error accumulation, conditional recovery, and validation patterns.
  * These patterns follow the "radical ergonomics" principle by making complex error handling
  * scenarios simple and discoverable.
  */
object ErrorHandling {
  import DomainTypes.*

  /** Sophisticated retry policies providing flexible, context-aware retry strategies.
    *
    * RetryPolicy represents different retry strategies that can be applied to effects, each
    * optimized for specific failure patterns and operational requirements. The policies follow
    * Eru's "Radical Ergonomics" principle by providing intuitive configuration while maintaining
    * correctness guarantees.
    *
    * All policies support:
    *   - Context-aware retry decisions based on error type and attempt history
    *   - Configurable delay calculations with overflow protection
    *   - Integration with Eru's structured error handling
    *
    * @example
    *   {{{ // Conditional retry for specific errors val policy =
    *   RetryPolicy.conditional[NetworkError]( shouldRetryError = _.isTransient, maxAttempts = 3,
    *   baseDelay = Duration.ofMillis(100) )
    *
    * // Jittered exponential backoff val jitteredPolicy = RetryPolicy.jitteredExponential(
    * maxAttempts = 5, baseDelay = Duration.ofMillis(50), jitterFactor = 0.2 ) }}}
    */
  enum RetryPolicy {

    /** Conditional retry with exponential backoff based on error type predicate.
      *
      * This policy only retries errors that match the provided predicate, allowing fine-grained
      * control over which errors should trigger retry attempts. Uses exponential backoff with
      * configurable maximum delay to prevent excessive resource consumption.
      *
      * @param shouldRetryError
      *   predicate to determine if an error should be retried
      * @param maxAttempts
      *   maximum number of retry attempts before giving up
      * @param baseDelay
      *   initial delay between attempts, doubled for each retry
      * @param maxDelay
      *   maximum delay cap to prevent unbounded exponential growth
      */
    case Conditional(
      shouldRetryError: Any => Boolean,
      maxAttempts: AttemptCount,
      baseDelay: Duration,
      maxDelay: Duration
    )

    /** Exponential backoff with jitter to prevent thundering herd effects.
      *
      * This policy adds randomized jitter to exponential backoff delays, preventing multiple
      * clients from retrying simultaneously after shared resource failures. The jitter factor
      * controls the amount of randomization applied to delays.
      *
      * @param maxAttempts
      *   maximum number of retry attempts before giving up
      * @param baseDelay
      *   initial delay between attempts, doubled for each retry
      * @param jitterFactor
      *   amount of randomization (0.0-1.0) applied to delays
      * @param random
      *   random number generator for jitter calculations
      */
    case JitteredExponential(
      maxAttempts: AttemptCount,
      baseDelay: Duration,
      jitterFactor: JitterFactor,
      random: scala.util.Random
    )

    /** Time-bounded retry policy based on elapsed duration rather than attempt count.
      *
      * This policy continues retrying as long as the total elapsed time remains within the
      * specified limit. Useful for scenarios where timing matters more than attempt count, such as
      * real-time processing or user-facing operations.
      *
      * @param timeLimit
      *   maximum total time to spend retrying before giving up
      * @param baseDelay
      *   fixed delay between retry attempts
      */
    case TimeBasedCircuitBreaker(
      timeLimit: Duration,
      baseDelay: Duration
    )

    /** Determines if a retry should be attempted for the given error and context. */
    def shouldRetry[E](error: E, attempt: AttemptCount, context: RetryContext): Boolean = this match {
      case Conditional(shouldRetryError, maxAttempts, _, _) =>
        attempt < maxAttempts && shouldRetryError(error)

      case JitteredExponential(maxAttempts, _, _, _) =>
        attempt < maxAttempts

      case TimeBasedCircuitBreaker(timeLimit, _) =>
        context.elapsedTime.compareTo(timeLimit) < 0
    }

    /** Calculates the delay before the next retry attempt. */
    def delayFor(attempt: AttemptCount): Duration = this match {
      case Conditional(_, _, baseDelay, maxDelay) =>
        val exponentialDelay = baseDelay.multipliedBy(exponentialMultiplier(attempt.value))
        if (exponentialDelay.compareTo(maxDelay) > 0) maxDelay else exponentialDelay

      case JitteredExponential(_, baseDelay, jitterFactor, random) =>
        val exponentialDelay = baseDelay.multipliedBy(exponentialMultiplier(attempt.value))
        val jitter = exponentialDelay.toMillis * jitterFactor.value * (random.nextDouble() - 0.5)
        Duration.ofMillis((exponentialDelay.toMillis + jitter.toLong).max(0))

      case TimeBasedCircuitBreaker(_, baseDelay) =>
        baseDelay
    }

    /** Efficiently calculates exponential multiplier with overflow protection. */
    private def exponentialMultiplier(attempt: Int): Long =
      if (attempt >= 63) Long.MaxValue else 1L << attempt
  }

  /** Context information available during retry decisions. */
  final class RetryContext(
    val startTime: Instant,
    val totalAttempts: Int,
    val lastErrors: List[Any] = Nil
  ) {

    /** Total elapsed time since the first attempt. */
    def elapsedTime: Duration = Duration.between(startTime, Instant.now())

    /** Adds an error to the context history. */
    def withError(error: Any): RetryContext =
      RetryContext(startTime, totalAttempts, (error :: lastErrors).take(10))

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: RetryContext =>
        startTime == that.startTime &&
        totalAttempts == that.totalAttempts &&
        lastErrors == that.lastErrors
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(startTime, totalAttempts, lastErrors)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor("RetryContext", startTime, totalAttempts, lastErrors)
  }

  object RetryContext {

    /** Creates a new RetryContext. */
    def apply(
      startTime: Instant,
      totalAttempts: Int,
      lastErrors: List[Any] = Nil
    ): RetryContext = new RetryContext(startTime, totalAttempts, lastErrors)
  }

  object RetryPolicy {

    /** Retry based on error type predicate with exponential backoff. */
    def conditional[E](
      shouldRetryError: E => Boolean,
      maxAttempts: Int,
      baseDelay: Duration,
      maxDelay: Duration = Duration.ofMinutes(1)
    ): RetryPolicy = {
      require(maxAttempts > 0, "maxAttempts must be positive")
      require(!baseDelay.isNegative, "baseDelay cannot be negative")
      require(!maxDelay.isNegative, "maxDelay cannot be negative")
      require(maxDelay.compareTo(baseDelay) >= 0, "maxDelay must be >= baseDelay")

      val anyErrorPredicate: Any => Boolean = {
        case e: E @unchecked => shouldRetryError(e)
        case _ => false
      }
      RetryPolicy.Conditional(anyErrorPredicate, AttemptCount(maxAttempts), baseDelay, maxDelay)
    }

    /** Retry with jittered exponential backoff to avoid thundering herd. */
    def jitteredExponential(
      maxAttempts: Int,
      baseDelay: Duration,
      jitterFactor: Double = 0.1
    ): RetryPolicy = {
      require(maxAttempts > 0, "maxAttempts must be positive")
      require(!baseDelay.isNegative, "baseDelay cannot be negative")
      require(
        jitterFactor >= 0.0 && jitterFactor <= 1.0,
        s"jitterFactor must be between 0.0 and 1.0, got: $jitterFactor"
      )

      RetryPolicy.JitteredExponential(
        AttemptCount(maxAttempts),
        baseDelay,
        JitterFactor(jitterFactor),
        new scala.util.Random()
      )
    }

    /** Retry based on elapsed time limit rather than attempt count. */
    def timeBasedCircuitBreaker(
      timeLimit: Duration,
      baseDelay: Duration
    ): RetryPolicy = {
      require(!timeLimit.isNegative, "timeLimit cannot be negative")
      require(!timeLimit.isZero, "timeLimit cannot be zero")
      require(!baseDelay.isNegative, "baseDelay cannot be negative")
      require(timeLimit.compareTo(baseDelay) > 0, "timeLimit must be greater than baseDelay")

      RetryPolicy.TimeBasedCircuitBreaker(timeLimit, baseDelay)
    }

    /** Common retry patterns for ergonomic usage. */

    /** Exponential backoff with sensible defaults. */
    def exponential(maxAttempts: Int): RetryPolicy = {
      require(maxAttempts > 0, "maxAttempts must be positive")
      conditional(_ => true, maxAttempts, Duration.ofMillis(100), Duration.ofMinutes(1))
    }

    /** Immediate retry without delay. */
    def immediate(maxAttempts: Int): RetryPolicy = {
      require(maxAttempts > 0, "maxAttempts must be positive")
      conditional(_ => true, maxAttempts, Duration.ZERO, Duration.ZERO)
    }
  }

  /** Circuit breaker state for protecting against cascading failures. */
  enum CircuitState {
    case Closed
    case Open
    case HalfOpen
  }

  /** Internal state representation for circuit breaker. */
  private final class CircuitBreakerState(
    val state: CircuitState,
    val failures: Long,
    val successes: Long,
    val lastFailureTime: Option[Instant]
  ) {

    /** Creates a copy with modified fields. */
    def copy(
      state: CircuitState = this.state,
      failures: Long = this.failures,
      successes: Long = this.successes,
      lastFailureTime: Option[Instant] = this.lastFailureTime
    ): CircuitBreakerState =
      CircuitBreakerState(state, failures, successes, lastFailureTime)

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: CircuitBreakerState =>
        state == that.state &&
        failures == that.failures &&
        successes == that.successes &&
        lastFailureTime == that.lastFailureTime
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(state, failures, successes, lastFailureTime)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor("CircuitBreakerState", state, failures, successes, lastFailureTime)
  }

  private object CircuitBreakerState {

    /** Creates a new CircuitBreakerState. */
    def apply(
      state: CircuitState,
      failures: Long,
      successes: Long,
      lastFailureTime: Option[Instant]
    ): CircuitBreakerState =
      new CircuitBreakerState(state, failures, successes, lastFailureTime)
  }

  /** Circuit breaker for protecting downstream services. */
  final class CircuitBreaker(
    failureThreshold: FailureThreshold,
    recoveryTimeout: Duration,
    successThreshold: Int = 1
  ) {

    private val atomicState = new AtomicReference[CircuitBreakerState](
      CircuitBreakerState(CircuitState.Closed, 0L, 0L, None)
    )

    /** Current state of the circuit breaker. */
    def currentState: CircuitState = atomicState.get().state

    /** Executes an effect with circuit breaker protection. */
    def protect[E, A](effect: Eru[E, A]): Eru[E | CircuitBreakerOpen, A] = {
      val currentStateValue = atomicState.get()
      currentStateValue.state match {
        case CircuitState.Open if shouldAttemptRecovery(currentStateValue) =>
          atomicState.compareAndSet(currentStateValue, currentStateValue.copy(state = CircuitState.HalfOpen))
          executeWithCircuitBreaker(effect)

        case CircuitState.Open =>
          Eru.fail(CircuitBreakerOpen("Circuit breaker is open"))

        case _ =>
          executeWithCircuitBreaker(effect)
      }
    }

    private def shouldAttemptRecovery(state: CircuitBreakerState): Boolean = {
      state.lastFailureTime match {
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

    /** Helper method to atomically update circuit breaker state using compare-and-set. */
    private def updateStateAtomically(stateUpdater: CircuitBreakerState => CircuitBreakerState): Unit = {
      var currentState = atomicState.get()
      var newState = stateUpdater(currentState)

      while (!atomicState.compareAndSet(currentState, newState)) {
        currentState = atomicState.get()
        newState = stateUpdater(currentState)
      }
    }

    private def onSuccess(): Unit = {
      updateStateAtomically { currentState =>
        currentState.state match {
          case CircuitState.HalfOpen =>
            val newSuccesses = currentState.successes + 1
            if (newSuccesses >= successThreshold) {
              currentState.copy(state = CircuitState.Closed, failures = 0L, successes = 0L)
            } else {
              currentState.copy(successes = newSuccesses)
            }
          case CircuitState.Closed =>
            currentState.copy(failures = 0L)
          case CircuitState.Open =>
            currentState.copy(failures = 0L)
        }
      }
    }

    private def onFailure(): Unit = {
      val now = Some(Instant.now())
      updateStateAtomically { currentState =>
        val newFailures = currentState.failures + 1
        currentState.copy(
          failures = newFailures,
          lastFailureTime = now,
          state = if (failureThreshold <= newFailures && currentState.state != CircuitState.Open) {
            CircuitState.Open
          } else {
            currentState.state
          },
          successes = if (failureThreshold <= newFailures) 0L else currentState.successes
        )
      }
    }
  }

  /** Error indicating circuit breaker is open. */
  final class CircuitBreakerOpen(val message: String) {

    /** Equality based on message. */
    override def equals(obj: Any): Boolean = obj match {
      case that: CircuitBreakerOpen => message == that.message
      case _ => false
    }

    /** Hash code based on message. */
    override def hashCode(): Int = DataClassUtils.hashCodeFor(message)

    /** String representation for debugging. */
    override def toString: String = DataClassUtils.toStringFor("CircuitBreakerOpen", message)
  }

  object CircuitBreakerOpen {

    /** Creates a new CircuitBreakerOpen error. */
    def apply(message: String): CircuitBreakerOpen = new CircuitBreakerOpen(message)

    /** Extracts the message from a CircuitBreakerOpen for pattern matching. */
    def unapply(error: CircuitBreakerOpen): Option[String] = Some(error.message)
  }

  /** Error context providing structured diagnostic information. */
  final class ErrorWithContext[E](
    val error: E,
    val timestamp: Instant,
    val attempt: AttemptCount,
    val context: Map[String, Any] = Map.empty
  ) {

    /** Equality based on all fields. */
    override def equals(obj: Any): Boolean = obj match {
      case that: ErrorWithContext[_] =>
        error == that.error &&
        timestamp == that.timestamp &&
        attempt == that.attempt &&
        context == that.context
      case _ => false
    }

    /** Hash code based on all fields. */
    override def hashCode(): Int =
      DataClassUtils.hashCodeFor(error, timestamp, attempt, context)

    /** String representation for debugging. */
    override def toString: String =
      DataClassUtils.toStringFor("ErrorWithContext", error, timestamp, attempt, context)
  }

  private object ErrorWithContext {

    /** Creates a new ErrorWithContext. */
    def apply[E](
      error: E,
      timestamp: Instant,
      attempt: AttemptCount,
      context: Map[String, Any] = Map.empty
    ): ErrorWithContext[E] =
      new ErrorWithContext(error, timestamp, attempt, context)
  }

  /** Enhanced error accumulator for collecting multiple errors with context. */
  final class ErrorAccumulator[E](val errors: List[ErrorWithContext[E]]) {

    /** Adds an error to the accumulator with the current timestamp and attempt information. */
    def add(error: E, attempt: AttemptCount, context: Map[String, Any] = Map.empty): ErrorAccumulator[E] =
      ErrorAccumulator(ErrorWithContext(error, Instant.now(), attempt, context) :: errors)

    /** Combines with another accumulator. */
    def combine(other: ErrorAccumulator[E]): ErrorAccumulator[E] =
      ErrorAccumulator(errors ++ other.errors)

    /** Gets all error contexts in chronological order. */
    def allErrorsWithContext: List[ErrorWithContext[E]] = errors.reverse

    /** Gets all errors without context in chronological order. */
    def allErrors: List[E] = errors.reverse.map(_.error)

    /** Checks if there are any errors. */
    def nonEmpty: Boolean = errors.nonEmpty

    /** Gets the most recent error with context. */
    def mostRecentWithContext: Option[ErrorWithContext[E]] = errors.headOption

    /** Gets the most recent error. */
    def mostRecent: Option[E] = errors.headOption.map(_.error)

    /** Gets the first error with context. */
    def firstWithContext: Option[ErrorWithContext[E]] = errors.lastOption

    /** Gets the first error, if any. */
    def headOption: Option[E] = errors.lastOption.map(_.error)

    /** Gets error statistics for diagnostic purposes. */
    def errorStats: Map[String, Any] = {
      val errorsByType = errors.groupBy(_.error.getClass.getSimpleName)
      Map(
        "totalErrors" -> errors.size,
        "errorTypes" -> errorsByType.view.mapValues(_.size).toMap,
        "timeSpan" -> (for {
          first <- errors.lastOption
          last <- errors.headOption
        } yield Duration.between(first.timestamp, last.timestamp).toString).getOrElse("N/A")
      )
    }

    /** Equality based on errors list. */
    override def equals(obj: Any): Boolean = obj match {
      case that: ErrorAccumulator[_] => errors == that.errors
      case _ => false
    }

    /** Hash code based on errors list. */
    override def hashCode(): Int = DataClassUtils.hashCodeFor(errors)

    /** String representation for debugging. */
    override def toString: String = DataClassUtils.toStringFor("ErrorAccumulator", errors)
  }

  object ErrorAccumulator {

    /** Creates a new ErrorAccumulator. */
    def apply[E](errors: List[ErrorWithContext[E]]): ErrorAccumulator[E] =
      new ErrorAccumulator(errors)

    /** Creates an empty error accumulator. */
    def empty[E]: ErrorAccumulator[E] = ErrorAccumulator(List.empty)
  }

  /** Fluent builder for CircuitBreaker configuration. */
  final class CircuitBreakerBuilder {
    private var failureThreshold: FailureThreshold = FailureThreshold(5)
    private var recoveryTimeout: Duration = Duration.ofSeconds(30)
    private var successThreshold: Int = 1

    /** Sets the failure threshold that triggers circuit breaker to open. */
    def withFailureThreshold(threshold: Int): CircuitBreakerBuilder = {
      require(threshold > 0, "Failure threshold must be positive")
      this.failureThreshold = FailureThreshold(threshold)
      this
    }

    /** Sets the recovery timeout before attempting to close the circuit. */
    def withRecoveryTimeout(timeout: Duration): CircuitBreakerBuilder = {
      require(!timeout.isNegative, "Recovery timeout cannot be negative")
      this.recoveryTimeout = timeout
      this
    }

    /** Sets the number of successful calls required to close the circuit from half-open state. */
    def withSuccessThreshold(threshold: Int): CircuitBreakerBuilder = {
      require(threshold > 0, "Success threshold must be positive")
      this.successThreshold = threshold
      this
    }

    /** Builds the configured CircuitBreaker instance. */
    def build: CircuitBreaker =
      new CircuitBreaker(failureThreshold, recoveryTimeout, successThreshold)
  }

  object CircuitBreaker {

    /** Creates a new fluent builder for CircuitBreaker configuration. */
    def builder: CircuitBreakerBuilder = new CircuitBreakerBuilder()

    /** Creates a CircuitBreaker with default configuration. */
    def default: CircuitBreaker =
      new CircuitBreaker(FailureThreshold(5), Duration.ofSeconds(30), 1)

    /** Creates a CircuitBreaker with fast recovery for testing environments. */
    def fastRecovery: CircuitBreaker =
      new CircuitBreaker(FailureThreshold(3), Duration.ofSeconds(5), 1)
  }
}
