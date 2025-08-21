package net.ghoula.eru

/** A data type representing the result of a computation that may either succeed with a value of
  * type `A` or fail with an error of type `E`.
  *
  * `Result[E, A]` is the foundational data type of the Eru library, embodying the core principles
  * of correctness, ergonomics, and composability. It provides a pure, immutable representation of
  * fallible computations.
  *
  * The type parameters are covariant, allowing for flexible subtyping relationships that enhance
  * composability and usability.
  *
  * @tparam E
  *   the type of the error value (covariant)
  * @tparam A
  *   the type of the success value (covariant)
  */
enum Result[+E, +A] {

  /** Represents a successful computation containing a value of type `A`.
    *
    * @param value
    *   the successful result value
    */
  case Success(value: A)

  /** Represents a failed computation containing an error of type `E`.
    *
    * @param error
    *   the error that caused the failure
    */
  case Failure(error: E)
}

object Result {

  /** Creates a successful `Result` containing the given value.
    *
    * This is the canonical way to construct a successful `Result`. The error type is inferred as
    * `Nothing`, allowing the result to be compatible with any error type through covariance.
    *
    * @param value
    *   the value to wrap in a successful `Result`
    * @tparam A
    *   the type of the success value
    * @return
    *   a `Result[Nothing, A]` representing success
    */
  def succeed[A](value: A): Result[Nothing, A] = Success(value)

  /** Creates a failed `Result` containing the given error.
    *
    * This is the canonical way to construct a failed `Result`. The success type is inferred as
    * `Nothing`, allowing the result to be compatible with any success type through covariance.
    *
    * @param error
    *   the error to wrap in a failed `Result`
    * @tparam E
    *   the type of the error value
    * @return
    *   a `Result[E, Nothing]` representing failure
    */
  def fail[E](error: E): Result[E, Nothing] = Failure(error)
}
