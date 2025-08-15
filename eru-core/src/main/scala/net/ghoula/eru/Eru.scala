package net.ghoula.eru

import scala.util.control.NonFatal
import scala.util.control.TailCalls.{done, tailcall, TailRec}

/** A data type representing a pure, lazy, and composable computation that can produce a value of
  * type `A` or fail with an error of type `E`.
  *
  * `Eru[E, A]` is the heart of the Eru effect system, embodying the core principles of correctness,
  * ergonomics, and composability. It provides a pure, immutable description of computations that
  * can be composed and executed safely.
  *
  * @tparam E
  *   the type of the error value (covariant)
  * @tparam A
  *   the type of the success value (covariant)
  */
enum Eru[+E, +A] {

  /** Represents a pure, succeeding computation containing a value of type `A`. */
  private case Succeed(value: A) extends Eru[Nothing, A]

  /** Represents a pure, failing computation containing an error of type `E`. */
  private case Fail(error: E) extends Eru[E, Nothing]

  /** Represents a synchronous, side-effecting computation suspended in a thunk. */
  private case Effect(thunk: () => Either[Throwable, A]) extends Eru[Throwable, A]

  /** Represents a chained computation resulting from a `flatMap` operation. The `From` type
    * parameter is the key to the GADT, allowing us to preserve the intermediate type information
    * and avoid casting.
    */
  private case Chain[E0, From, +To](source: Eru[E0, From], f: From => Eru[E0, To]) extends Eru[E0, To]

  /** Represents an error-handling computation. */
  private case RecoverWith[E0, A0, +E2, +A1 >: A0](
    source: Eru[E0, A0],
    pf: PartialFunction[E0, Eru[E2, A1]]
  ) extends Eru[E0 | E2, A1]

  /** Represents a transformation of the error type. */
  private case MapError[E0, A0, +E2](source: Eru[E0, A0], f: E0 => E2) extends Eru[E2, A0]

  /** Represents the combination of two computations, evaluated left then right, producing a pair of
    * their results.
    */
  private case Zip[E0, E1, A0, B0](left: Eru[E0, A0], right: Eru[E1, B0]) extends Eru[E0 | E1, (A0, B0)]

  /** Represents interpreting a computation to a `Result` value without failure at the type level.
    */
  private case Attempt[E0, A0](source: Eru[E0, A0]) extends Eru[Nothing, Result[E0, A0]]

  /** Transforms the success value of this `Eru` using a pure function. This is the Functor `map`
    * operation.
    *
    * @param f
    *   the function to apply to the success value.
    * @return
    *   a new `Eru` describing the transformed computation.
    */
  final def map[B](f: A => B): Eru[E, B] = flatMap(a => Eru.succeed(f(a)))

  /** Chains another computation to be run after this one completes. This is the Monad `flatMap` (or
    * `bind`) operation.
    *
    * @param f
    *   the function to apply to the success value, returning the next `Eru`.
    * @return
    *   a new `Eru` describing the composed computation.
    */
  final def flatMap[E1 >: E, B](f: A => Eru[E1, B]): Eru[E1, B] = Chain(this, f)

  /** Transforms the error value of this `Eru` using a pure function. If this `Eru` is a success,
    * this operation has no effect.
    *
    * @param f
    *   the function to apply to the error value.
    * @return
    *   a new `Eru` with the transformed error type.
    */
  final def mapError[E2](f: E => E2): Eru[E2, A] = MapError(this, f)

  /** Combines this computation with another, producing a pair of their results.
    *
    * The resulting computation first evaluates this computation. If it succeeds, it then evaluates
    * the other computation. If either computation fails, the combined computation fails with that
    * error.
    *
    * @param that
    *   the other computation to combine with this one.
    * @tparam E2
    *   the error type of the other computation.
    * @tparam B
    *   the success type of the other computation.
    * @return
    *   an `Eru[E | E2, (A, B)]` that represents the sequential combination of both computations.
    */
  final def zip[E2, B](that: Eru[E2, B]): Eru[E | E2, (A, B)] = Zip(this, that)

  /** Provides a fallback computation to run if this one fails, regardless of the error.
    *
    * @param that
    *   the fallback computation to use if this one fails (by-name for laziness).
    * @return
    *   a new `Eru` that tries this computation first, then the fallback if it fails.
    */
  final def orElse[E2, A1 >: A](that: => Eru[E2, A1]): Eru[E | E2, A1] =
    recoverWith { case _ => that }

  /** Recovers from specific errors by transforming an error into a success value.
    *
    * @param pf
    *   the partial function to apply to a potential error for recovery.
    * @return
    *   a new `Eru` that may recover from a failure.
    */
  final def recover[A1 >: A](pf: PartialFunction[E, A1]): Eru[E, A1] =
    recoverWith(pf.andThen(Eru.succeed))

  /** Recovers from specific errors by providing an alternative computation.
    *
    * @param pf
    *   the partial function to apply to a potential error to generate a new computation.
    * @return
    *   a new `Eru` with the specified error recovery logic.
    */
  final def recoverWith[E2, A1 >: A](
    pf: PartialFunction[E, Eru[E2, A1]]
  ): Eru[E | E2, A1] = RecoverWith(this, pf)

  /** Interprets this computation into a `Result[E, A]` value without throwing, preserving laziness.
    *
    * The returned program never fails at the type level.
    *
    * @return
    *   an `Eru[Nothing, Result[E, A]]` that yields `Success(a)` or `Failure(e)` when run.
    */
  final def attempt: Eru[Nothing, Result[E, A]] = Attempt(this)

  /** Executes this computation synchronously and returns the result.
    *
    * WARNING: This method is unsafe because it can perform arbitrary side effects and may throw
    * exceptions. It should only be used at the edge of your program or in testing scenarios.
    *
    * Failure semantics:
    *   - If the computation fails with a `Throwable`, that throwable is rethrown as-is.
    *   - If the computation fails with a non-`Throwable` typed error `E`, it is wrapped in an
    *     `EruException[E]`.
    *
    * @return
    *   the result of executing this computation.
    * @throws EruException
    *   if the computation fails with a typed error `E` (non-`Throwable`).
    * @throws Throwable
    *   if the computation fails with an untyped exception (a `Throwable`).
    */
  final def unsafeRunSync(): A = Eru.interpreter.runSync(this)
}

object Eru {

  /** Creates an `Eru[Nothing, A]` that succeeds with the given pure value.
    * @param value
    *   the value to wrap in a successful `Eru`.
    * @return
    *   an `Eru[Nothing, A]` that succeeds with the given value.
    */
  def succeed[A](value: A): Eru[Nothing, A] = Succeed(value)

  /** Creates an `Eru[E, Nothing]` that fails with the given error.
    * @param error
    *   the error to wrap in a failed `Eru`.
    * @return
    *   an `Eru[E, Nothing]` that fails with the given error.
    */
  def fail[E](error: E): Eru[E, Nothing] = Fail(error)

  /** Creates an `Eru[Throwable, A]` that represents a synchronous, side-effecting computation. The
    * computation is suspended lazily and will not be executed until `unsafeRunSync` is called. Any
    * `NonFatal` exception thrown during evaluation will be caught and returned as a failure.
    *
    * Fatal errors (e.g., `VirtualMachineError`) are not caught and will escape.
    *
    * @param computation
    *   the computation to suspend (by-name).
    * @return
    *   an `Eru[Throwable, A]` representing the suspended computation.
    */
  def effect[A](computation: => A): Eru[Throwable, A] =
    Effect(() =>
      try Right(computation)
      catch { case NonFatal(t) => Left(t) }
    )

  /** Creates an `Eru` from an `Either`. `Left` values become failures, `Right` values become
    * successes.
    *
    * @param either
    *   the `Either` to convert.
    * @return
    *   an `Eru[E, A]` representing the `Either`.
    */
  def fromEither[E, A](either: Either[E, A]): Eru[E, A] =
    either.fold(fail, succeed)

  /** Creates an `Eru` from a `scala.util.Try`. `Success` values become successes, `Failure`
    * exceptions become failures.
    *
    * @param t
    *   the `Try` computation to convert (by-name).
    * @return
    *   an `Eru[Throwable, A]` representing the `Try`.
    */
  def fromTry[A](t: => scala.util.Try[A]): Eru[Throwable, A] =
    effect(t.get)

  /** Creates an `Eru[E, A]` from an `Option[A]`, failing with `onNone` when `opt` is `None`.
    *
    * Both `opt` and `onNone` are evaluated lazily when the returned program is run.
    *
    * @param opt
    *   the optional value (by-name)
    * @param onNone
    *   the error to produce when `opt` is `None` (by-name)
    * @return
    *   an `Eru[E, A]` that succeeds with the contained value or fails with `onNone`
    */
  def fromOption[E, A](opt: => Option[A], onNone: => E): Eru[E, A] =
    succeed(()).flatMap { _ =>
      opt match {
        case Some(a) => succeed(a)
        case None => fail(onNone)
      }
    }

  /** A successful `Eru` containing `Unit`. */
  val unit: Eru[Nothing, Unit] = succeed(())

  /** The private, cast-free, and stack-safe interpreter for the Eru data type. */
  private object interpreter {

    /** The entry point for executing an Eru program.
      */
    def runSync[E, A](start: Eru[E, A]): A =
      run(start).result match {
        case Left(error) =>
          error match {
            case t: Throwable => throw t
            case e => throw EruException(e)
          }
        case Right(value) => value
      }

    /** The core of the interpreter. It translates an `Eru` program into a `TailRec` computation,
      * which is a description of a stack-safe, trampolined execution. This function is pure.
      */
    private def run[E, A](eru: Eru[E, A]): TailRec[Either[E, A]] = eru match {
      case Succeed(value) =>
        done(Right(value))

      case Fail(error) =>
        done(Left(error))

      case Effect(thunk) =>
        done(thunk())

      case Chain(source, f) =>
        tailcall(run(source)).flatMap {
          case Right(value) => tailcall(run(f(value)))
          case Left(error) => done(Left(error))
        }

      case RecoverWith(source, pf) =>
        tailcall(run(source)).flatMap {
          case Right(value) => done(Right(value))
          case Left(error) =>
            if (pf.isDefinedAt(error)) {
              tailcall(run(pf(error)))
            } else {
              done(Left(error))
            }
        }

      case MapError(source, f) =>
        tailcall(run(source)).map { either =>
          either.left.map(f)
        }

      case Zip(left, right) =>
        tailcall(run(left)).flatMap {
          case Right(a) =>
            tailcall(run(right)).map {
              case Right(b) => Right((a, b))
              case Left(e1) => Left(e1)
            }
          case Left(e0) => done(Left(e0))
        }

      case Attempt(source) =>
        tailcall(run(source)).map {
          case Left(e) => Right(Result.Failure(e))
          case Right(a) => Right(Result.Success(a))
        }
    }
  }
}
