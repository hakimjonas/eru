package net.ghoula.eru

/** A data type representing the result of a computation that may either succeed with a value of
  * type `A` or fail with an error of type `E`.
  *
  * `Result[E, A]` provides a pure, immutable representation of fallible computations. The type
  * parameters are covariant to enable flexible subtyping relationships in common usage.
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

  /** Transforms a `Result` into a value of type `B` by applying one of two functions.
    *
    * This is the catamorphism for `Result`, providing a way to extract values from both success and
    * failure cases in a type-safe manner. This method consolidates the common pattern of matching
    * on `Result` cases found throughout the codebase.
    *
    * @param result
    *   the `Result` to transform
    * @param ifFailure
    *   the function to apply if the `Result` is a failure
    * @param ifSuccess
    *   the function to apply if the `Result` is a success
    * @tparam E
    *   the type of the error value
    * @tparam A
    *   the type of the success value
    * @tparam B
    *   the type of the result value
    * @return
    *   the result of applying the appropriate function
    */
  def fold[E, A, B](result: Result[E, A])(ifFailure: E => B, ifSuccess: A => B): B = result match {
    case Success(value) => ifSuccess(value)
    case Failure(error) => ifFailure(error)
  }

  /** Converts a `Result` to an `Eru` effect.
    *
    * This method consolidates the common pattern of converting `Result` values to `Eru` effects
    * found throughout the codebase. Success values become successful effects, and failure values
    * become failed effects.
    *
    * @param result
    *   the `Result` to convert
    * @tparam E
    *   the type of the error value
    * @tparam A
    *   the type of the success value
    * @return
    *   an `Eru[E, A]` representing the converted result
    */
  def toEru[E, A](result: Result[E, A]): Eru[E, A] = result match {
    case Success(value) => Eru.succeed(value)
    case Failure(error) => Eru.fail(error)
  }

  /** Converts a `Result` to an `Exit` value.
    *
    * This method consolidates the common pattern of converting `Result` values to `Exit` values
    * found throughout the codebase. It properly handles the distinction between typed errors and
    * throwable exceptions.
    *
    * @param result
    *   the `Result` to convert
    * @tparam E
    *   the type of the error value
    * @tparam A
    *   the type of the success value
    * @return
    *   an `Exit[E, A]` representing the converted result
    */
  def toExit[E, A](result: Result[E, A]): Exit[E, A] = result match {
    case Success(value) => Exit.Success(value)
    case Failure(err) =>
      err match {
        case throwable: Throwable => Exit.Die(throwable)
        case error => Exit.Failure(error)
      }
  }
}
