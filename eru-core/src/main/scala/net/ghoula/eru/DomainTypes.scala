package net.ghoula.eru

/** Internal utilities for reducing boilerplate in data classes.
  *
  * These utilities provide common functionality for implementing equals, hashCode, and toString
  * methods across the Eru codebase, ensuring consistency and reducing duplication. They follow
  * standard Java practices for hash code generation and provide clean string representations.
  */
private[eru] object DataClassUtils {

  /** Generates hash code for multiple values using the standard prime multiplier pattern.
    *
    * This method implements the standard approach for combining hash codes from multiple values,
    * using a prime number (31) as the multiplier to reduce hash collisions. The implementation
    * follows the pattern used by generated case classes in Scala.
    *
    * @param values
    *   the values to include in the hash code calculation
    * @return
    *   the combined hash code for all provided values
    */
  def hashCodeFor(values: Any*): Int = {
    val prime = 31
    var result = 1
    values.foreach { value =>
      result = prime * result + value.hashCode()
    }
    result
  }

  /** Generates string representation in constructor format.
    *
    * This method creates a string representation that mirrors constructor syntax, making debug
    * output more readable and consistent across the codebase. The format matches what would be
    * generated for case classes.
    *
    * @param className
    *   the name of the class being represented
    * @param values
    *   the field values to include in the string representation
    * @return
    *   a formatted string in the form "ClassName(value1, value2, ...)"
    */
  def toStringFor(className: String, values: Any*): String = {
    val valueString = values.mkString(", ")
    s"$className($valueString)"
  }
}

/** Opaque domain types with validation.
  *
  *   - AttemptCount: non‑negative attempt counts
  *   - JitterFactor: a double in [0.0, 1.0]
  *   - FailureThreshold: a positive integer threshold
  */
object DomainTypes {

  /** Represents a count of retry attempts with compile-time safety.
    *
    * AttemptCount ensures retry logic cannot accidentally use negative attempt counts, preventing
    * common off-by-one errors and invalid retry states. The type enforces non-negative constraints
    * at construction time, making it impossible to represent invalid attempt counts in the type
    * system.
    *
    * This type is used throughout error handling and retry mechanisms to ensure correctness and
    * provide clear semantic meaning in function signatures.
    *
    * @example
    *   {{{ val attempts = AttemptCount(3) val nextAttempt = attempts.increment if (attempts <
    *   AttemptCount(5)) { /* retry logic */ } }}}
    */
  opaque type AttemptCount = Int

  object AttemptCount {

    /** Creates a new AttemptCount with validation.
      *
      * @param value
      *   the attempt count value must be non-negative
      * @return
      *   a validated AttemptCount instance
      * @throws IllegalArgumentException
      *   if value is negative
      */
    def apply(value: Int): AttemptCount = {
      require(value >= 0, "AttemptCount must be non-negative")
      value
    }

    extension (count: AttemptCount) {

      /** Gets the underlying integer value. */
      def value: Int = count

      /** Returns the next attempt count. */
      def increment: AttemptCount = count + 1

      /** Adds a number to this attempt count. */
      def +(other: Int): AttemptCount = count + other

      /** Compares if this count is less than another. */
      def <(other: AttemptCount): Boolean = count < other

      /** Compares if this count is greater than or equal to another. */
      def >=(other: AttemptCount): Boolean = count >= other
    }
  }

  /** Represents a jitter factor for exponential backoff with constrained range [0.0, 1.0].
    *
    * JitterFactor prevents invalid jitter values that could cause negative delays or excessive
    * randomization. The constraint ensures jitter remains within reasonable bounds for backoff
    * algorithms, preventing configuration errors that could lead to system instability.
    *
    * This type is essential for implementing robust retry policies that avoid thundering herd
    * effects through controlled randomization.
    *
    * @example
    *   {{{ val jitter = JitterFactor(0.1) // 10% jitter val delay = baseDelay * (1 + jitter.value *
    *   random.nextDouble()) }}}
    */
  opaque type JitterFactor = Double

  object JitterFactor {

    /** Creates a new JitterFactor with validation.
      *
      * @param value
      *   the jitter factor must be between 0.0 and 1.0 inclusive
      * @return
      *   a validated JitterFactor instance
      * @throws IllegalArgumentException
      *   if value is outside the valid range
      */
    def apply(value: Double): JitterFactor = {
      require(value >= 0.0 && value <= 1.0, s"JitterFactor must be between 0.0 and 1.0, got: $value")
      value
    }

    extension (factor: JitterFactor) {

      /** Gets the underlying double value. */
      def value: Double = factor
    }
  }

  /** Represents a failure threshold for circuit breakers with compile-time safety.
    *
    * FailureThreshold ensures circuit breakers cannot be configured with invalid thresholds (zero
    * or negative), preventing misconfigured circuit breakers that would never open or close
    * properly. This type provides guarantees about circuit breaker behavior at compile time.
    *
    * Circuit breakers are critical for system resilience, and this type ensures they are configured
    * correctly to provide meaningful protection against cascading failures.
    *
    * @example
    *   {{{ val threshold = FailureThreshold(5) val breaker = new CircuitBreaker(threshold,
    *   recoveryTimeout) if (threshold <= currentFailures) { /* open circuit */ } }}}
    */
  opaque type FailureThreshold = Int

  object FailureThreshold {

    /** Creates a new FailureThreshold with validation.
      *
      * @param value
      *   the failure threshold must be positive
      * @return
      *   a validated FailureThreshold instance
      * @throws IllegalArgumentException
      *   if value is not positive
      */
    def apply(value: Int): FailureThreshold = {
      require(value > 0, "FailureThreshold must be positive")
      value
    }

    extension (threshold: FailureThreshold) {

      /** Gets the underlying integer value. */
      def value: Int = threshold

      /** Compares if this threshold is less than or equal to a count. */
      def <=(other: Long): Boolean = threshold <= other
    }
  }
}
