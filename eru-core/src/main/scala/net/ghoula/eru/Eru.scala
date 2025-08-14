package net.ghoula.eru

import scala.annotation.tailrec

/** A data type representing a pure, lazy, and composable computation that produces a value of type
  * `A`.
  *
  * `Eru[A]` is the heart of the Eru effect system, embodying the core principles of correctness,
  * ergonomics, and composability. It provides a pure, immutable description of computations that
  * can be composed and executed safely.
  *
  * @tparam A
  *   the type of the value produced by the computation (covariant)
  */
enum Eru[+A] {

  /** Represents a pure, succeeding computation containing a value of type `A`. */
  private case Succeed(value: A) extends Eru[A]

  /** Represents a synchronous, side-effecting computation suspended in a thunk. */
  private case Effect(thunk: () => A) extends Eru[A]

  /** Represents a chained computation resulting from a `flatMap` operation. The `From` type
    * parameter is the key to the GADT, allowing us to preserve the intermediate type information
    * and avoid casting.
    */
  private case Chain[From, +To](source: Eru[From], f: From => Eru[To]) extends Eru[To]

  /** Transforms the success value of this `Eru` using a pure function. This is the Functor `map`
    * operation.
    *
    * @param f
    *   the function to apply to the success value.
    * @return
    *   a new `Eru` describing the transformed computation.
    */
  final def map[B](f: A => B): Eru[B] = flatMap(a => Eru.succeed(f(a)))

  /** Chains another computation to be run after this one completes. This is the Monad `flatMap` (or
    * `bind`) operation.
    *
    * @param f
    *   the function to apply to the success value, returning the next `Eru`.
    * @return
    *   a new `Eru` describing the composed computation.
    */
  final def flatMap[B](f: A => Eru[B]): Eru[B] = Chain(this, f)

  /** Executes this computation synchronously and returns the result.
    *
    * WARNING: This method is unsafe because it can perform arbitrary side effects and may throw
    * exceptions. It should only be used at the edge of your program or in testing scenarios.
    *
    * @return
    *   the result of executing this computation.
    */
  final def unsafeRunSync(): A = Eru.unsafeRunSync(this)
}

object Eru {

  /** Creates an `Eru[A]` that succeeds with the given pure value.
    * @param value
    *   the value to wrap in a successful `Eru`.
    * @return
    *   an `Eru[A]` that succeeds with the given value.
    */
  def succeed[A](value: A): Eru[A] = Succeed(value)

  /** Creates an `Eru[A]` that represents a synchronous, side-effecting computation. The computation
    * is suspended lazily and will not be executed until `unsafeRunSync` is called.
    *
    * @param computation
    *   the computation to suspend (by-name).
    * @return
    *   an `Eru[A]` representing the suspended computation.
    */
  def effect[A](computation: => A): Eru[A] = Effect(() => computation)

  /** The private, stack-safe, and purely functional interpreter for the `Eru` data type. */
  private def unsafeRunSync[A](start: Eru[A]): A = {
    @tailrec
    def loop[X](current: Eru[X]): X = current match {
      case Succeed(value) => value
      case Effect(thunk) => thunk()
      case Chain(source, f) =>
        source match {
          case Succeed(value) => loop(f(value))
          case Effect(thunk) => loop(f(thunk()))
          case Chain(subSource, g) =>
            val composed = subSource.flatMap(x => g(x).flatMap(f))
            loop(composed)
        }
    }
    loop(start)
  }
}
