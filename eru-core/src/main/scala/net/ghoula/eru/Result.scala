package net.ghoula.eru

/** The result of a computation that either succeeds with a value or fails with an error.
  *
  * @tparam E
  *   the type of the error value
  * @tparam A
  *   the type of the success value
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

  /** Creates a successful Result.
    *
    * @param value
    *   the value to wrap
    * @return
    *   a successful Result
    */
  def succeed[A](value: A): Result[Nothing, A] = Success(value)

  /** Creates a failed Result.
    *
    * @param error
    *   the error to wrap
    * @return
    *   a failed Result
    */
  def fail[E](error: E): Result[E, Nothing] = Failure(error)

  /** Transforms a Result by applying one of two functions.
    *
    * @param result
    *   the Result to transform
    * @param ifFailure
    *   function to apply if the Result is a failure
    * @param ifSuccess
    *   function to apply if the Result is a success
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
