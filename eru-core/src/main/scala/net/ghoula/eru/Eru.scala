package net.ghoula.eru

import scala.util.control.NonFatal
import scala.util.control.TailCalls.{done, tailcall, TailRec}

import net.ghoula.eru.EruObserver.*

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

  /** Represents a chain of pure map operations fused together for performance. This avoids creating
    * multiple Chain nodes for consecutive map operations.
    */
  private case MapChain[E0, From, +To](source: Eru[E0, From], f: From => To) extends Eru[E0, To]

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

  /** Represents a debugging marker around a computation with a lazily provided label. */
  private case Debug[E0, A0](source: Eru[E0, A0], label: () => String) extends Eru[E0, A0]
  private case Ensure[E0, A0](source: Eru[E0, A0], finalizer: () => Eru[Nothing, Unit]) extends Eru[E0, A0]
  private case Suspend[E0, A0](register: (Either[E0, A0] => Unit) => Eru[Nothing, Unit]) extends Eru[E0, A0]

  /** Transforms the success value of this `Eru` using a pure function. This is the Functor `map`
    * operation.
    *
    * Construction-time optimization: Uses `MapChain` to fuse consecutive map operations, avoiding
    * the creation of multiple Chain nodes and improving performance.
    *
    * @param f
    *   the function to apply to the success value.
    * @return
    *   a new `Eru` describing the transformed computation.
    */
  final def map[B](f: A => B): Eru[E, B] = {
    // Eager evaluation optimization: immediately evaluate pure chains
    this match {
      case Succeed(value) =>
        // Pure chain detected: evaluate immediately at construction time
        try {
          Succeed(f(value))
        } catch {
          case scala.util.control.NonFatal(t) => Effect(() => Left(t)).asInstanceOf[Eru[E, B]]
        }
      case MapChain(Succeed(value), g) =>
        // Pure MapChain detected: evaluate entire chain immediately
        try {
          Succeed(f(g(value)))
        } catch {
          case scala.util.control.NonFatal(t) => Effect(() => Left(t)).asInstanceOf[Eru[E, B]]
        }
      case MapChain(source, g) =>
        // Compose with existing MapChain by function composition
        MapChain(source, g.andThen(f))
      case _ =>
        // Create a new MapChain for this map operation
        MapChain(this, f)
    }
  }

  /** Chains another computation to be run after this one completes. This is the Monad `flatMap` (or
    * `bind`) operation.
    *
    * Construction-time optimization: Detects pure flatMap chains where both the source and 
    * continuation result are immediate successes, evaluating them at construction time.
    *
    * @param f
    *   the function to apply to the success value, returning the next `Eru`.
    * @return
    *   a new `Eru` describing the composed computation.
    */
  final def flatMap[E1 >: E, B](f: A => Eru[E1, B]): Eru[E1, B] = {
    // Very conservative FlatMap chain optimization: disabled for now to prevent regressions
    // The optimization was causing side effects to be executed multiple times
    // TODO: Re-enable with a more sophisticated approach that can detect truly pure continuations
    Chain(this, f)
  }

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

  /** Ensures that the provided finalizer runs after this computation, regardless of success or
    * failure.
    *
    * The finalizer is evaluated lazily and will be executed exactly once when the returned program
    * is run.
    *
    * @param finalizer
    *   the finalizer to run after this computation completes
    * @return
    *   a computation that yields the same result as this one but guarantees the finalizer runs
    */
  final def ensure[F](finalizer: => Eru[F, Unit]): Eru[E, A] =
    Ensure(this, () => finalizer.attempt.flatMap(_ => Eru.unit))

  /** Runs the provided `use` function with the acquired resource and releases it with `release`
    * afterward.
    *
    * The release action is guaranteed to run after `use`, whether `use` succeeds or fails.
    *
    * @param release
    *   the release action for the acquired resource
    * @param use
    *   the function that uses the acquired resource
    * @return
    *   a computation that uses the resource and then releases it
    */
  final def bracket[E1 >: E, F, B](release: A => Eru[F, Unit])(use: A => Eru[E1, B]): Eru[E1, B] =
    this.flatMap(a => use(a).ensure(release(a)))

  /** Adds a lazily evaluated debug label around this computation. When an observer is provided at
    * run time, a Step event with the label will be emitted before this computation executes.
    *
    * @param label
    *   the label to emit to the observer (evaluated lazily)
    * @return
    *   a computation that behaves like this one but emits a debug Step when observed
    */
  final def debug(label: => String): Eru[E, A] = Debug(this, () => label)

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

  /** Executes this computation synchronously with the provided observer, emitting lifecycle and
    * step events.
    *
    * WARNING: Unsafe — may perform side effects and may throw at the edge with the same semantics
    * as `unsafeRunSync`.
    *
    * @param observer
    *   the observer to receive events for this run
    * @return
    *   the result of executing this computation
    */
  final def unsafeRunSyncWith(observer: EruObserver): A = Eru.interpreter.runSyncWithObserver(this, observer)
}

object Eru {

  /** Creates an asynchronous, suspending effect by registering a callback with an external source.
    *
    * The provided `register` function receives a callback that must be invoked exactly once by the
    * asynchronous source when the result is ready. The registration itself is described by
    * `Eru[Nothing, Unit]` to remain pure; it will be evaluated by the runtime.
    *
    * @param register
    *   a function that, given a resume callback, returns an effect describing how to register that
    *   callback with the asynchronous source
    * @tparam E
    *   the typed error of the asynchronous computation
    * @tparam A
    *   the success type of the asynchronous computation
    * @return
    *   an effect that suspends until the callback is invoked
    */
  def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E, A] =
    Suspend(register)

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

  /** Executes a synchronous computation in a blocking region.
    *
    * In the synchronous kernel (0.2.x), this is equivalent to [[effect]]: it suspends the
    * computation lazily and captures `NonFatal` exceptions into the `Throwable` error channel.
    * Fatal errors (e.g., `VirtualMachineError`) are not caught and will escape.
    *
    * In the asynchronous runtime (0.3.x), the runtime may treat blocking regions specially to avoid
    * starving the scheduler while maintaining correctness and resource-safety guarantees.
    *
    * @param thunk
    *   the computation to suspend (by-name)
    * @return
    *   an `Eru[Throwable, A]` representing the suspended computation
    */
  def blocking[A](thunk: => A): Eru[Throwable, A] = effect(thunk)

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
    def runSync[E, A](start: Eru[E, A]): A = {
      val (either, fins) = runWithStack(start, Nil).result
      drainFinalizers(fins).result
      either match {
        case Left(error) =>
          error match {
            case t: Throwable => throw t
            case e => throw EruException(e)
          }
        case Right(value) => value
      }
    }

    private type Finalizer = () => Eru[Nothing, Unit]

    private def runWithStack[E, A](eru: Eru[E, A], fins: List[Finalizer]): TailRec[(Either[E, A], List[Finalizer])] =
      eru match {
        case Succeed(value) =>
          done((Right(value), fins))

        case Fail(error) =>
          done((Left(error), fins))

        case Effect(thunk) =>
          done((thunk(), fins))

        case Chain(source, f) =>
          tailcall(runWithStack(source, fins)).flatMap {
            case (Right(value), fs) => tailcall(runWithStack(f(value), fs))
            case (Left(error), fs) => done((Left(error), fs))
          }

        case MapChain(source, f) =>
          tailcall(runWithStack(source, fins)).map {
            case (Right(value), fs) => (Right(f(value)), fs)
            case (Left(error), fs) => (Left(error), fs)
          }

        case RecoverWith(source, pf) =>
          tailcall(runWithStack(source, fins)).flatMap {
            case (Right(value), fs) => done((Right(value), fs))
            case (Left(error), fs) =>
              if (pf.isDefinedAt(error)) {
                tailcall(runWithStack(pf(error), fs))
              } else {
                done((Left(error), fs))
              }
          }

        case MapError(source, f) =>
          tailcall(runWithStack(source, fins)).map { case (either, fs) => (either.left.map(f), fs) }

        case Zip(left, right) =>
          tailcall(runWithStack(left, fins)).flatMap {
            case (Right(a), fsL) =>
              tailcall(runWithStack(right, fsL)).map {
                case (Right(b), fsR) => (Right((a, b)), fsR)
                case (Left(e1), fsR) => (Left(e1), fsR)
              }
            case (Left(e0), fsL) => done((Left(e0), fsL))
          }

        case Attempt(source) =>
          tailcall(runWithStack(source, fins)).map {
            case (Left(e), fs) => (Right(Result.Failure(e)), fs)
            case (Right(a), fs) => (Right(Result.Success(a)), fs)
          }

        case Debug(source, _) =>
          tailcall(runWithStack(source, fins))

        case Ensure(source, fin) =>
          tailcall(runWithStack(source, fins)).map { case (either, fs) => (either, fin :: fs) }
        case Suspend(register) =>
          val cbBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
          val cb: Either[E, A] => Unit = ea => cbBox.set(Some(ea))
          val (_, fsAfterReg) = runWithStack(register(cb), fins).result
          while (cbBox.get.isEmpty) {
            try java.lang.Thread.sleep(0)
            catch { case _: InterruptedException => () }
          }
          done((cbBox.get.get, fsAfterReg))
      }

    private def drainFinalizers(fins: List[Finalizer]): TailRec[Unit] =
      fins match {
        case Nil => done(())
        case fin :: rest =>
          tailcall(runWithStack(fin(), Nil)).flatMap { case (_, inner) =>
            tailcall(drainFinalizers(inner ++ rest))
          }
      }

    private def runWithObsStack[E, A](
      eru: Eru[E, A],
      scope: ScopeId,
      observer: EruObserver,
      fins: List[Finalizer]
    ): TailRec[(Either[E, A], List[Finalizer])] =
      eru match {
        case Succeed(value) =>
          done((Right(value), fins))
        case Fail(error) =>
          done((Left(error), fins))
        case Effect(thunk) =>
          done((thunk(), fins))
        case Chain(source, f) =>
          tailcall(runWithObsStack(source, scope, observer, fins)).flatMap {
            case (Right(value), fs) => tailcall(runWithObsStack(f(value), scope, observer, fs))
            case (Left(error), fs) => done((Left(error), fs))
          }

        case MapChain(source, f) =>
          tailcall(runWithObsStack(source, scope, observer, fins)).map {
            case (Right(value), fs) => (Right(f(value)), fs)
            case (Left(error), fs) => (Left(error), fs)
          }
        case RecoverWith(source, pf) =>
          tailcall(runWithObsStack(source, scope, observer, fins)).flatMap {
            case (Right(value), fs) => done((Right(value), fs))
            case (Left(error), fs) =>
              if (pf.isDefinedAt(error)) {
                tailcall(runWithObsStack(pf(error), scope, observer, fs))
              } else {
                done((Left(error), fs))
              }
          }
        case MapError(source, f) =>
          tailcall(runWithObsStack(source, scope, observer, fins)).map { case (either, fs) => (either.left.map(f), fs) }
        case Zip(left, right) =>
          tailcall(runWithObsStack(left, scope, observer, fins)).flatMap {
            case (Right(a), fsL) =>
              tailcall(runWithObsStack(right, scope, observer, fsL)).map {
                case (Right(b), fsR) => (Right((a, b)), fsR)
                case (Left(e1), fsR) => (Left(e1), fsR)
              }
            case (Left(e0), fsL) => done((Left(e0), fsL))
          }
        case Attempt(source) =>
          tailcall(runWithObsStack(source, scope, observer, fins)).map {
            case (Left(e), fs) => (Right(Result.Failure(e)), fs)
            case (Right(a), fs) => (Right(Result.Success(a)), fs)
          }
        case Debug(source, label) =>
          observer.onEvent(EruEvent.Step(scope, label()))
          tailcall(runWithObsStack(source, scope, observer, fins))
        case Ensure(source, fin) =>
          tailcall(runWithObsStack(source, scope, observer, fin :: fins))
        case Suspend(register) =>
          val cbBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
          val cb: Either[E, A] => Unit = ea => cbBox.set(Some(ea))
          val (_, fsAfterReg) = runWithObsStack(register(cb), scope, observer, fins).result
          while (cbBox.get.isEmpty) {
            try java.lang.Thread.sleep(0)
            catch { case _: InterruptedException => () }
          }
          done((cbBox.get.get, fsAfterReg))
      }

    /** Observer-aware interpreter variant */
    def runSyncWithObserver[E, A](start: Eru[E, A], observer: EruObserver): A = {
      val scope = ScopeId.fresh()
      observer.onEvent(EruEvent.ProgramStart(scope))
      val (either, fins) = runWithObsStack(start, scope, observer, Nil).result
      drainFinalizers(fins).result
      either match {
        case Left(error) =>
          error match {
            case t: Throwable =>
              observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Defect(t)))
              throw t
            case e =>
              observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.TypedFailure(e)))
              throw EruException(e)
          }
        case Right(value) =>
          observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Success))
          value
      }
    }

  }

  // Internal, package-private view of the Eru ADT for the runtime stepper.
  private[eru] object Internals {
    enum View[+E, +A] {
      case VSucceed(value: A)
      case VFail(error: E)
      case VEffect(thunk: () => Either[Throwable, A])
      case VChain[E0, From, To](source: Eru[E0, From], f: From => Eru[E0, To]) extends View[E0, To]
      case VMapChain[E0, From, To](source: Eru[E0, From], f: From => To) extends View[E0, To]
      case VRecoverWith[E0, A0, E2, A1 >: A0](source: Eru[E0, A0], pf: PartialFunction[E0, Eru[E2, A1]])
          extends View[E0 | E2, A1]
      case VMapError[E0, A0, E2](source: Eru[E0, A0], f: E0 => E2) extends View[E2, A0]
      case VZip[E0, E1, A0, B0](left: Eru[E0, A0], right: Eru[E1, B0]) extends View[E0 | E1, (A0, B0)]
      case VAttempt[E0, A0](source: Eru[E0, A0]) extends View[Nothing, Result[E0, A0]]
      case VDebug[E0, A0](source: Eru[E0, A0], label: () => String) extends View[E0, A0]
      case VEnsure[E0, A0](source: Eru[E0, A0], finalizer: () => Eru[Nothing, Unit]) extends View[E0, A0]
      case VSuspend[E0, A0](register: (Either[E0, A0] => Unit) => Eru[Nothing, Unit]) extends View[E0, A0]
    }

    import View.*
    def view[E, A](e: Eru[E, A]): View[E, A] = e match {
      case Succeed(value) => VSucceed(value)
      case Fail(error) => VFail(error)
      case Effect(thunk) => VEffect(thunk)
      case Chain(source, f) => VChain(source, f)
      case MapChain(source, f) => VMapChain(source, f)
      case RecoverWith(source, pf) => VRecoverWith(source, pf)
      case MapError(source, f) => VMapError(source, f)
      case Zip(left, right) => VZip(left, right)
      case Attempt(source) => VAttempt(source)
      case Debug(source, label) => VDebug(source, label)
      case Ensure(source, finalizer) => VEnsure(source, finalizer)
      case Suspend(register) => VSuspend(register)
    }
  }
}
