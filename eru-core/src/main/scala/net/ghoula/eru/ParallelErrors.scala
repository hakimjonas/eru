package net.ghoula.eru

/** Container for multiple errors from parallel operations.
  *
  * When multiple computations fail in parallel, this type preserves all error information rather
  * than discarding failures. The first error is treated as primary for compatibility, but all
  * errors are accessible.
  *
  * @param first
  *   the first error encountered (primary)
  * @param rest
  *   additional errors from other parallel computations
  * @tparam E
  *   the base error type
  */
final case class ParallelErrors[+E](first: E, rest: List[E]) {

  /** All errors including the first. */
  def all: List[E] = first :: rest

  /** Total number of errors. */
  def size: Int = 1 + rest.size

  /** Map a function over all errors. */
  def map[E2](f: E => E2): ParallelErrors[E2] =
    ParallelErrors(f(first), rest.map(f))

  /** Convert to a single error using a combining function. */
  def reduce[E2 >: E](f: (E2, E2) => E2): E2 =
    rest.foldLeft[E2](first)(f)
}

object ParallelErrors {

  /** Create from a non-empty list of errors. */
  def fromList[E](errors: List[E]): Option[ParallelErrors[E]] = errors match {
    case Nil => None
    case head :: tail => Some(ParallelErrors(head, tail))
  }

  /** Combine multiple errors, handling the ParallelErrors type. */
  def combine[E](errors: List[E]): Option[E | ParallelErrors[E]] = errors match {
    case Nil => None
    case single :: Nil => Some(single)
    case first :: rest => Some(ParallelErrors(first, rest))
  }
}
