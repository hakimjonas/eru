package net.ghoula.eru

/** Exception thrown when a computation fails.
  *
  * @param error
  *   the error that caused the computation to fail
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

  /** Creates a new EruException.
    *
    * @param error
    *   the error to wrap
    */
  def apply[E](error: E): EruException[E] = new EruException(error)
}
