package net.ghoula.eru

/** Exception thrown when a computation fails with a typed error.
  *
  * `unsafeRunSync` wraps a typed error `E` in this exception when the error is not a `Throwable`,
  * so the error propagates through exception-based APIs while preserving its type. Throwable
  * failures are rethrown as-is.
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
  *   case e: EruException[?] => println(s"Caught: ${e.error}")
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
