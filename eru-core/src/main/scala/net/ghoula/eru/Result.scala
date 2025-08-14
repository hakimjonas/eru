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

/** Extension methods providing the core API for `Result[E, A]`.
  *
  * These methods follow the principle of radical ergonomics, providing a fluent and discoverable
  * API that feels like a natural extension of the Scala language.
  */
extension [E, A](result: Result[E, A]) {

  /** Transforms the success value of this `Result` using the given function.
    *
    * If this `Result` is a `Success`, applies the function to the contained value and returns a new
    * `Success` with the transformed value. If this `Result` is a `Failure`, returns the failure
    * unchanged.
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

  /** Transforms the success value of this `Result` using a function that returns another `Result`.
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
    * If this `Result` is a `Success`, applies `ifSuccess` to the contained value. If this `Result`
    * is a `Failure`, applies `ifFailure` to the contained error.
    *
    * This is the catamorphism for `Result`, providing a way to extract values from both success and
    * failure cases in a type-safe manner.
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
  def fold[B](ifFailure: E => B, ifSuccess: A => B): B = result match {
    case Result.Success(value) => ifSuccess(value)
    case Result.Failure(error) => ifFailure(error)
  }

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
