package net.ghoula.eru

/** A single-assignment asynchronous variable.
  *
  * A `Deferred[A]` starts empty and can be completed exactly once with a value of type `A`.
  *
  * The current runtime exposes non-blocking operations that describe completion and inspection.
  * Suspension-based `get` will be introduced when the scheduler grows true suspension support.
  */
trait Deferred[A] {

  /** Completes this `Deferred` with the provided value if it has not been completed yet.
    *
    * @param a
    *   the value to complete the deferred with
    * @return
    *   an effect that yields `true` if this invocation completed the deferred, or `false` if it was
    *   already completed
    */
  def complete(a: A): Eru[Nothing, Boolean]

  /** Returns the current value if already completed.
    *
    * @return
    *   an effect that yields `Some(a)` if completed or `None` otherwise
    */
  def poll: Eru[Nothing, Option[A]]
}

object Deferred {

  /** Creates a new, empty `Deferred[A]`.
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the created deferred
    */
  def make[A]: Eru[Nothing, Deferred[A]] =
    Eru.effect { new RuntimeDeferred[A] }.attempt.map {
      case Result.Success(d) => d
      case Result.Failure(_) => new RuntimeDeferred[A]
    }

  private final class RuntimeDeferred[A] extends Deferred[A] {
    private val state = new java.util.concurrent.atomic.AtomicReference[Option[A]](None)

    def complete(a: A): Eru[Nothing, Boolean] =
      Eru.effect {
        state.compareAndSet(None, Some(a))
      }.attempt.map {
        case Result.Success(b) => b
        case Result.Failure(_) => state.get().isDefined
      }

    def poll: Eru[Nothing, Option[A]] = Eru.succeed(state.get())
  }
}
