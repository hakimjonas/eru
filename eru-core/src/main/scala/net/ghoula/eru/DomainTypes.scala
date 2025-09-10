package net.ghoula.eru

/** Utilities for implementing equals, hashCode, and toString methods.
  */
private[eru] object DataClassUtils {

  /** Generates hash code for multiple values.
    *
    * @param values
    *   the values to include in the hash code calculation
    * @return
    *   the combined hash code
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
    * @param className
    *   the name of the class
    * @param values
    *   the field values
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

  /** A non-negative count of retry attempts.
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

  /** A jitter factor constrained to the range [0.0, 1.0].
    *
    * @example
    *   {{{
    * val jitter = JitterFactor(0.1)
    * val delay = baseDelay * (1 + jitter.value * random.nextDouble())
    *   }}}
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

  /** A positive failure threshold for circuit breakers.
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
