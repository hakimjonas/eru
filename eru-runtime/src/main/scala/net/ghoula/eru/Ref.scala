package net.ghoula.eru

/** A fiber-safe, mutable reference that provides atomic read and update operations.
  *
  * Instances are created in the `eru-runtime` module, and all operations are described as `Eru`
  * programs. The current runtime is single-threaded; however, the public API is designed to remain
  * compatible with a possible future multithreaded scheduler.
  */
trait Ref[A] {

  /** Reads the current value of this reference.
    * @return
    *   an effect that yields the current value
    */
  def get: Eru[Nothing, A]

  /** Sets the current value of this reference to the provided value.
    * @param a
    *   the new value
    * @return
    *   an effect that completes when the value has been set
    */
  def set(a: A): Eru[Nothing, Unit]

  /** Updates the current value by applying the provided function.
    * @param f
    *   the function to apply atomically to the current value
    * @return
    *   an effect that yields the updated value
    */
  def update(f: A => A): Eru[Nothing, A]

  /** Atomically modifies the current value using `f` and returns an auxiliary result.
    *
    * The function `f` receives the current value and must return a pair of the new value and a
    * result of type `B`.
    *
    * @param f
    *   the modification function
    * @tparam B
    *   the type of the auxiliary result
    * @return
    *   an effect that yields the auxiliary result produced by `f`
    */
  def modify[B](f: A => (A, B)): Eru[Nothing, B]
}

object Ref {

  /** Creates a new `Ref[A]` initialized with the provided value.
    * @param initial
    *   the initial value
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the created reference
    */
  def make[A](initial: A): Eru[Nothing, Ref[A]] =
    Eru.succeed(new RuntimeRef[A](initial))

  private final class RuntimeRef[A](init: A) extends Ref[A] {
    private final val state = new java.util.concurrent.atomic.AtomicReference(init)

    def get: Eru[Nothing, A] = Eru.effect(state.get()).attempt.map {
      case Result.Success(v) => v
      case Result.Failure(_) => state.get()
    }

    def set(a: A): Eru[Nothing, Unit] = Eru.effect { state.set(a) }.attempt.flatMap(_ => Eru.unit)

    def update(f: A => A): Eru[Nothing, A] =
      Eru.effect {
        @annotation.tailrec
        def loop(): A = {
          val current = state.get()
          val next = f(current)
          if (state.compareAndSet(current, next)) next
          else loop()
        }
        loop()
      }.attempt.map {
        case Result.Success(v) => v
        case Result.Failure(_) => state.get()
      }

    def modify[B](f: A => (A, B)): Eru[Nothing, B] =
      Eru.effect {
        @annotation.tailrec
        def loop(): B = {
          val current = state.get()
          val (next, out) = f(current)
          if (state.compareAndSet(current, next)) out
          else loop()
        }
        loop()
      }.attempt.map {
        case Result.Success(v) => v
        case Result.Failure(_) => f(state.get())._2
      }
  }
}
