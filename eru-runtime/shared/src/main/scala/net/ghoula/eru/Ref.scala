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

  /** Applies multiple update functions in sequence, returning the final value.
    *
    * This is more efficient than chaining multiple update calls as it uses a single effectTotal
    * operation instead of creating multiple Chain structures.
    *
    * @param fs
    *   the update functions to apply in sequence
    * @return
    *   an effect that yields the final updated value
    */
  def updateMany(fs: (A => A)*): Eru[Nothing, A]

  /** Gets the current value then updates it.
    *
    * @param f
    *   the update function
    * @return
    *   an effect that yields the value before the update
    */
  def getAndUpdate(f: A => A): Eru[Nothing, A]

  /** Updates the value and returns the new value.
    *
    * This is just an alias for update for clarity.
    *
    * @param f
    *   the update function
    * @return
    *   an effect that yields the updated value
    */
  def updateAndGet(f: A => A): Eru[Nothing, A]

  /** Applies multiple modifications in sequence, collecting all results.
    *
    * @param fs
    *   the modification functions to apply
    * @tparam B
    *   the type of the auxiliary results
    * @return
    *   an effect that yields all auxiliary results
    */
  def modifyMany[B](fs: (A => (A, B))*): Eru[Nothing, List[B]]
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
    private val state = new java.util.concurrent.atomic.AtomicReference(init)

    def get: Eru[Nothing, A] =
      Eru.effectTotal(state.get())

    def set(a: A): Eru[Nothing, Unit] =
      Eru.effectTotal { state.set(a); () }

    def update(f: A => A): Eru[Nothing, A] =
      Eru.effectTotal {
        @annotation.tailrec
        def loop(): A = {
          val current = state.get()
          val next = f(current)
          if (state.compareAndSet(current, next)) next
          else loop()
        }
        loop()
      }

    def modify[B](f: A => (A, B)): Eru[Nothing, B] =
      Eru.effectTotal {
        @annotation.tailrec
        def loop(): B = {
          val current = state.get()
          val (next, out) = f(current)
          if (state.compareAndSet(current, next)) out
          else loop()
        }
        loop()
      }

    def updateMany(fs: (A => A)*): Eru[Nothing, A] =
      if (fs.isEmpty) get
      else
        Eru.effectTotal {
          @annotation.tailrec
          def loop(): A = {
            val current = state.get()
            val next = fs.foldLeft(current)((acc, f) => f(acc))
            if (state.compareAndSet(current, next)) next
            else loop()
          }
          loop()
        }

    def getAndUpdate(f: A => A): Eru[Nothing, A] =
      Eru.effectTotal {
        @annotation.tailrec
        def loop(): A = {
          val current = state.get()
          val next = f(current)
          if (state.compareAndSet(current, next)) current
          else loop()
        }
        loop()
      }

    def updateAndGet(f: A => A): Eru[Nothing, A] =
      update(f)

    def modifyMany[B](fs: (A => (A, B))*): Eru[Nothing, List[B]] =
      if (fs.isEmpty) Eru.succeed(Nil)
      else
        Eru.effectTotal {
          @annotation.tailrec
          def loop(): List[B] = {
            val current = state.get()
            val results = scala.collection.mutable.ListBuffer.empty[B]
            val next = fs.foldLeft(current) { (acc, f) =>
              val (newAcc, b) = f(acc)
              results += b
              newAcc
            }
            if (state.compareAndSet(current, next)) results.toList
            else loop()
          }
          loop()
        }
  }
}
