package net.ghoula.eru

/** An exception thrown by the `unsafeRunSync` interpreter when a computation fails.
  *
  * This exception wraps the original error value from a failed `Eru[E, A]` computation, providing
  * structured error information while maintaining type safety in the interpreter. The wrapped error
  * can be retrieved and handled appropriately by the caller.
  *
  * @param error
  *   the original error value that caused the computation to fail
  * @tparam E
  *   the type of the error
  */
final class EruException[E](val error: E) extends RuntimeException {

  /** Returns a string representation of this exception, including the wrapped error. */
  override def toString: String = s"EruException($error)"

  /** Returns the error message, using the string representation of the wrapped error. */
  override def getMessage: String = Option(error).map(_.toString).getOrElse("null")
}

object EruException {

  /** Creates a new `EruException` wrapping the given error.
    *
    * @param error
    *   the error to wrap
    * @tparam E
    *   the type of the error
    * @return
    *   a new `EruException` containing the error
    */
  def apply[E](error: E): EruException[E] = new EruException(error)
}
