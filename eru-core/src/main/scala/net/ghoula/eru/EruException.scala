package net.ghoula.eru

/** Exception thrown when a computation fails with a typed error.
  *
  * This exception serves as a bridge between Eru's typed error system and the JVM's
  * exception-based error handling, allowing typed errors to be propagated through
  * exception-based APIs while preserving type information.
  *
  * @param error
  *   the error that caused the computation to fail
  * @tparam E
  *   the type of the error
  *
  * @example
  *   {{{
  * val effect = Eru.fail("validation error")
  * try {
  *   effect.unsafeRunSync()
  * } catch {
  *   case EruException(error: String) => println(s"Caught: $error")
  * }
  *   }}}
  */
final class EruException[E](val error: E) extends RuntimeException {

  /** Returns a string representation of this exception, including the wrapped error. */
  override def toString: String = s"EruException($error)"

  /** Returns the error message, using the string representation of the wrapped error. */
  override def getMessage: String = Option(error).map(_.toString).getOrElse("null")
}

object EruException {

  /** Creates a new EruException wrapping the specified error.
    *
    * @param error
    *   the error to wrap
    * @tparam E
    *   the type of the error
    * @return
    *   a new EruException containing the error
    *
    * @example
    *   {{{
    * val exception = EruException("network timeout")
    * throw exception
    *   }}}
    */
  def apply[E](error: E): EruException[E] = new EruException(error)
}
