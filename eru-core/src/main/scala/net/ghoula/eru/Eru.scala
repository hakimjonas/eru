package net.ghoula.eru

import scala.annotation.tailrec
import scala.util.control.NonFatal

import net.ghoula.eru.EruObserver.*
import net.ghoula.eru.internal.FiberSet.*
import net.ghoula.eru.internal.InterpreterResult

/** Internal exception used to preserve finalizers when InterruptedException occurs */
private class InterruptedWithFinalizers(
  val fiberId: FiberId,
  val cause: InterruptCause,
  val finalizers: List[() => Eru[Nothing, Unit]]
) extends InterruptedException(cause.toString)

/** Internal exception used to signal that the fast-path interpreter cannot handle a case. Used for
  * exception-based fallback to the safe state-machine interpreter.
  */
private class FastPathUnsupported extends scala.util.control.ControlThrowable

/** A computation that can succeed with a value of type `A` or fail with an error of type `E`.
  *
  * Computations are lazy and immutable descriptions that can be composed with combinators like
  * `map`, `flatMap`, and `recover`. They are executed using runtime methods.
  *
  * @tparam E
  *   the type of the error value
  * @tparam A
  *   the type of the success value
  */
enum Eru[+E, +A] {

  /** Represents a pure, succeeding computation containing a value of type `A`. */
  private case Succeed(value: A) extends Eru[Nothing, A]

  /** Represents a pure, failing computation containing an error of type `E`. */
  private case Fail(error: E) extends Eru[E, Nothing]

  /** Represents a synchronous, side-effecting computation suspended in a thunk. */
  private case Effect(thunk: () => Either[Throwable, A]) extends Eru[Throwable, A]

  /** Represents an infallible synchronous computation that cannot fail. */
  private case EffectTotal[A0](thunk: () => A0) extends Eru[Nothing, A0]

  /** Represents a chained computation with a continuation stack. The continuation stack is
    * represented by a GADT that maintains type safety across the chain of operations.
    */
  private case Chain[E0, From, +To](source: Eru[E0, From], cont: Eru.Continuation[E0, From, To]) extends Eru[E0, To]

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

  /** Represents a computation that runs a finalizer after it completes. */
  private case Ensure[E0, A0](source: Eru[E0, A0], finalizer: () => Eru[Nothing, Unit]) extends Eru[E0, A0]

  /** Represents an asynchronous, suspending computation. */
  private case Suspend[E0, A0](register: (Either[E0, A0] => Unit) => Eru[Nothing, Unit]) extends Eru[E0, A0]

  /** Represents forking a computation onto a separate fiber and returning a handle. */
  private case Fork[E0, A0](computation: Eru[E0, A0]) extends Eru[Nothing, EruFiber[E0, A0]]

  /** Represents awaiting the completion of a fiber. */
  private case Await[E0, A0](fiber: EruFiber[E0, A0]) extends Eru[E0, Exit[E0, A0]]

  /** Represents a synchronous, interruptible blocking computation suspended in a thunk. Unlike
    * Effect, InterruptedException thrown from the thunk is handled specially by the interpreter to
    * produce Exit.Interrupt and ensure proper finalizer execution.
    */
  private case InterruptibleBlocking[A0](thunk: () => A0) extends Eru[Nothing, A0]

  /** Represents a deferred computation scheduled to run at a future time in a new fiber.
    *
    * The interpreter delegates to a `TimerService` if available, registering the computation to
    * fire at `epochMillis`. Returns `()` immediately (fire-and-forget). When no `TimerService` is
    * present (sync kernel / tests), falls back to inline execution.
    */
  private case At[E0, A0](epochMillis: Long, computation: () => Eru[E0, A0]) extends Eru[Nothing, Unit]

  /** Represents a deferred computation scheduled to run after a relative delay in a new fiber.
    *
    * Unlike [[At]], the delay is measured on the monotonic clock: wall-clock adjustments do not
    * shorten or lengthen it. The interpreter delegates to `TimerService.scheduleAfter` when a timer
    * is available; otherwise (sync kernel / tests) it falls back to inline execution.
    */
  private case After[E0, A0](delayMillis: Long, computation: () => Eru[E0, A0]) extends Eru[Nothing, Unit]

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
    this match {
      case Succeed(value) =>
        try {
          Succeed(f(value))
        } catch {
          case scala.util.control.NonFatal(_) => MapChain(this, f)
        }
      case MapChain(Succeed(value), g) =>
        try {
          Succeed(f(g(value)))
        } catch {
          case scala.util.control.NonFatal(_) => MapChain(this, f)
        }
      case MapChain(source, g) =>
        MapChain(source, g.andThen(f))
      case _ =>
        MapChain(this, f)
    }
  }

  /** Chains another computation to be run after this one completes. This is the Monad `flatMap` (or
    * `bind`) operation.
    *
    * ⚠️ **Stack Safety**: Eru provides stack-safe `flatMap` chains, but avoid Scala recursion when
    * building these chains. Use iterative construction patterns instead.
    *
    * @param f
    *   the function to apply to the success value, returning the next `Eru`.
    * @return
    *   a new `Eru` describing the composed computation.
    */
  final def flatMap[E1 >: E, B](f: A => Eru[E1, B]): Eru[E1, B] = {
    this match {
      case Succeed(value) =>
        try {
          f(value) match {
            case s @ Succeed(_) => s
            case other => other
          }
        } catch {
          case NonFatal(ex) => Chain(this, Eru.Continuation.Step((_: A) => throw ex, Eru.Continuation.End()))
        }

      case EffectTotal(_) =>
        Chain(this, Eru.Continuation.Step(f, Eru.Continuation.End()))

      case MapChain(Succeed(sourceValue), g) =>
        try {
          val mapped = g(sourceValue)
          f(mapped) match {
            case s @ Succeed(_) => s
            case other => other
          }
        } catch {
          case NonFatal(ex) => Chain(this, Eru.Continuation.Step((_: A) => throw ex, Eru.Continuation.End()))
        }

      case Chain(source, cont) =>
        Chain(source, cont.andThen(f))

      case _ =>
        Chain(this, Eru.Continuation.Step(f, Eru.Continuation.End()))
    }
  }

  /** Transforms the error value of this `Eru` using a pure function. If this `Eru` is a success,
    * this operation has no effect.
    *
    * @param f
    *   the function to apply to the error value.
    * @return
    *   a new `Eru` with the transformed error type.
    */
  @inline final def mapError[E2](f: E => E2): Eru[E2, A] = MapError(this, f)

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
  @inline final def zip[E2, B](that: Eru[E2, B]): Eru[E | E2, (A, B)] = Zip(this, that)

  /** Provides a fallback computation to run if this one fails, regardless of the error.
    *
    * @param that
    *   the fallback computation to use if this one fails (by-name for laziness).
    * @return
    *   a new `Eru` that tries this computation first, then the fallback if it fails.
    */
  @inline final def orElse[E2, A1 >: A](that: => Eru[E2, A1]): Eru[E | E2, A1] =
    recoverWith { case _ => that }

  /** Recovers from specific errors by transforming an error into a success value.
    *
    * @param pf
    *   the partial function to apply to a potential error for recovery.
    * @return
    *   a new `Eru` that may recover from a failure.
    */
  @inline final def recover[A1 >: A](pf: PartialFunction[E, A1]): Eru[E, A1] =
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
  @inline final def attempt: Eru[Nothing, Result[E, A]] = Attempt(this)

  /** Executes a side effect on the success value without changing the result.
    *
    * Tap operations are useful for logging, debugging, or other side effects that should not affect
    * the main computation flow.
    *
    * @param f
    *   the side effect to execute on successful values
    * @tparam E1
    *   the error type; `E1 >: E` allows the side effect to widen the error channel
    * @return
    *   an effect that yields the same result but executes the side effect on success
    */
  final def tap[E1 >: E](f: A => Eru[E1, Unit]): Eru[E1, A] =
    flatMap(a => f(a).map(_ => a))

  /** Executes a side effect on the error value without changing the result.
    *
    * @param f
    *   the side effect to execute on error values
    * @return
    *   an effect that yields the same result but executes the side effect on failure
    */
  final def tapError(f: E => Eru[Nothing, Unit]): Eru[E, A] =
    this.attempt.flatMap {
      case Result.Success(value) => Eru.succeed(value)
      case Result.Failure(error) => f(error).flatMap(_ => Eru.fail(error))
    }

  /** Executes side effects on both success and error values without changing the result.
    *
    * @param onError
    *   the side effect to execute on error values
    * @param onSuccess
    *   the side effect to execute on success values
    * @tparam E1
    *   the error type; `E1 >: E` allows the side effect to widen the error channel
    * @return
    *   an effect that yields the same result but executes appropriate side effects
    */
  final def tapBoth[E1 >: E](onError: E => Eru[Nothing, Unit], onSuccess: A => Eru[E1, Unit]): Eru[E1, A] =
    this.attempt.flatMap {
      case Result.Success(value) => onSuccess(value).map(_ => value)
      case Result.Failure(error) => onError(error).flatMap(_ => Eru.fail(error))
    }

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
  @inline final def debug(label: => String): Eru[E, A] = Debug(this, () => label)

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
  final def unsafeRunSync(): A = Eru.interpreter.runSyncWithFibers(this)

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
  final def unsafeRunSyncWith(observer: EruObserver): A = Eru.interpreter.runSyncWithFibersAndObserver(this, observer)

}

object Eru {

  /** Returns true if this effect is an already-computed value (`Succeed` or `Fail`), allowing
    * runtime operations like `zipPar` to skip fiber scheduling.
    */
  private[eru] def isPureValue[E, A](eru: Eru[E, A]): Boolean = eru match {
    case Succeed(_) | Fail(_) => true
    case _ => false
  }

  /** GADT representing a stack of continuations in a flatMap chain.
    *
    * This data type maintains complete type safety by linking the output type of one function to
    * the input type of the next, eliminating the need for unsafe casts.
    */
  private[eru] enum Continuation[+E, -In, +Out] {

    /** The end of the continuation stack - identity transformation. */
    case End[A]() extends Continuation[Nothing, A, A]

    /** A step in the continuation chain, linking input type `In` through intermediate type `Mid` to
      * final output type `Out` via the remaining continuation stack.
      */
    case Step[+E1, In1, Mid1, +Out1](
      f: In1 => Eru[E1, Mid1],
      next: Continuation[E1, Mid1, Out1]
    ) extends Continuation[E1, In1, Out1]

    /** A lazily composed continuation that defers the appending operation. This allows us to build
      * deep chains without stack overflow during construction.
      */
    case Compose[+E1, In1, Mid1, +Out1](
      first: Continuation[E1, In1, Mid1],
      g: Mid1 => Eru[E1, Out1]
    ) extends Continuation[E1, In1, Out1]

    /** Composition of two continuations, used by the interpreter for right-associating continuation
      * chains during Chain decomposition. Only created internally, never by user code.
      */
    case ComposeK[+E1, In1, Mid1, +Out1](
      first: Continuation[E1, In1, Mid1],
      second: Continuation[E1, Mid1, Out1]
    ) extends Continuation[E1, In1, Out1]

    /** Appends a new function to the end of this continuation stack, maintaining type safety. This
      * is the key operation that allows us to build continuation chains without casts.
      */
    @inline def andThen[E2 >: E, NewOut](g: Out => Eru[E2, NewOut]): Continuation[E2, In, NewOut] =
      Compose(this, g)
  }

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
  private def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E, A] =
    Suspend(register)

  /** Creates an `Eru[Nothing, A]` that succeeds with the given pure value.
    *
    * ⚠️ **Stack Safety Note**: When building chains of computations, avoid recursive patterns. Use
    * iterative builders instead:
    *
    * ```scala
    * // ✅ Stack-safe approach:
    * Eru.iterate(0)(current => Eru.succeed(current + 1))(_ >= n)
    *
    * // ❌ Problematic approach:
    * def recursive(n: Int): Eru[Nothing, Int] =
    *   if (n <= 0) Eru.succeed(0)
    *   else Eru.succeed(n).flatMap(_ => recursive(n - 1))
    * ```
    *
    * @param value
    *   the value to wrap in a successful `Eru`.
    * @return
    *   an `Eru[Nothing, A]` that succeeds with the given value.
    */
  @inline def succeed[A](value: A): Eru[Nothing, A] = Succeed(value)

  /** Creates an `Eru[E, Nothing]` that fails with the given error.
    * @param error
    *   the error to wrap in a failed `Eru`.
    * @return
    *   an `Eru[E, Nothing]` that fails with the given error.
    */
  @inline def fail[E](error: E): Eru[E, Nothing] = Fail(error)

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

  /** Defers the construction of an Eru effect until the returned effect is executed.
    *
    * The by-name argument is captured unevaluated: `defer(eru)` constructs `eru` only when the
    * returned effect runs, once per execution. This is what makes recursive definitions
    * construction-safe (`Eru.forever` is built on it) and what lets you avoid paying the
    * construction cost of an effect branch that never runs. The semantics are otherwise identical
    * to running the constructed effect directly.
    *
    * @param eru
    *   the effect to defer (by-name)
    * @return
    *   an effect that will construct and run the given effect when executed
    */
  def defer[E, A](eru: => Eru[E, A]): Eru[E, A] =
    effectTotal(eru).flatMap(identity)

  /** Creates an infallible effect that cannot fail.
    *
    * This is an optimization for effects that are guaranteed not to throw exceptions, avoiding the
    * overhead of error handling. Use with caution - if the computation does throw, it will
    * propagate uncaught.
    *
    * @param computation
    *   the infallible computation to suspend (by-name)
    * @return
    *   an `Eru[Nothing, A]` representing the suspended computation
    */
  private[eru] def effectTotal[A](computation: => A): Eru[Nothing, A] =
    EffectTotal(() => computation)

  /** Executes a synchronous computation in a blocking region.
    *
    * In the synchronous kernel, this is equivalent to [[effect]]: it suspends the computation
    * lazily and captures `NonFatal` exceptions into the `Throwable` error channel. Fatal errors
    * (e.g., `VirtualMachineError`) are not caught and will escape.
    *
    * In concurrent runtimes, the scheduler may treat blocking regions specially to preserve
    * responsiveness while maintaining correctness and resource-safety guarantees.
    *
    * @param thunk
    *   the computation to suspend (by-name)
    * @return
    *   an `Eru[Throwable, A]` representing the suspended computation
    */
  @inline def blocking[A](thunk: => A): Eru[Throwable, A] = effect(thunk)

  /** Creates an `Eru` representing a synchronous, interruptible blocking computation that may be
    * interrupted via thread interruption. Unlike `blocking`, an `InterruptedException` raised while
    * evaluating the thunk is treated as interruption rather than a typed failure: in a fiber
    * context the fiber completes with `Exit.Interrupt`, and a synchronous run aborts by throwing.
    * Finalizers registered via `ensure`/`bracket` are carried through the interruption.
    *
    * This is the correct constructor for operations that can be interrupted via Java's thread
    * interruption mechanism, such as `Thread.sleep`, blocking I/O operations, or waiting on
    * synchronization primitives.
    *
    * @param thunk
    *   the interruptible computation to suspend (by-name)
    * @return
    *   an `Eru[Nothing, A]` representing the interruptible computation
    */
  def interruptibleBlocking[A](thunk: => A): Eru[Nothing, A] =
    InterruptibleBlocking(() => thunk)

  /** Creates an `Eru` from an `Either`. `Left` values become failures, `Right` values become
    * successes.
    *
    * @param either
    *   the `Either` to convert.
    * @return
    *   an `Eru[E, A]` representing the `Either`.
    */
  @inline def fromEither[E, A](either: Either[E, A]): Eru[E, A] =
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
    suspend[E, A] { cb =>
      opt match {
        case Some(a) => cb(Right(a))
        case None => cb(Left(onNone))
      }
      Eru.unit
    }

  /** Converts an Exit to an Eru, preserving error information.
    *
    * This method provides a convenient way to convert Exit outcomes back into Eru computations,
    * enabling composition and recovery patterns when working with fiber results. It handles most
    * Exit cases: Success becomes succeed, Failure becomes fail, and Die re-throws the exception.
    *
    * '''Mathematical Correctness:''' Interruptions cannot be converted to the error channel as they
    * represent control flow operations that should be handled through native interpreter
    * mechanisms, not converted to domain errors. This method throws an IllegalArgumentException
    * when encountering an Exit.Interrupt.
    *
    * If you need to handle interruptions, use pattern matching on the Exit directly:
    * {{{
    * exit match {
    *   case Exit.Interrupt(_, _) => /* handle interruption */
    *   case other => Eru.fromExit(other)
    * }
    * }}}
    *
    * @param exit
    *   the Exit outcome to convert to an Eru
    * @tparam E
    *   the error type of the Exit
    * @tparam A
    *   the success type of the Exit
    * @return
    *   an Eru that represents the same outcome as the Exit, with error type widened to include
    *   Throwable
    *
    * @example
    *   {{{
    * // Convert an Exit outcome back to an Eru for composition.
    * val exit: Exit[String, Int] = Exit.Success(42)
    * val computation = for {
    *   result <- Eru.fromExit(exit)
    *   doubled <- Eru.succeed(result * 2)
    * } yield doubled
    *   }}}
    */
  def fromExit[E, A](exit: Exit[E, A]): Eru[E | Throwable, A] = exit match {
    case Exit.Success(value) => Eru.succeed(value)
    case Exit.Failure(error) => Eru.fail(error)
    case Exit.Die(throwable) => Eru.effect(throw throwable)
    case Exit.Interrupt(fiberId, cause) =>
      Eru.effect(
        throw new IllegalArgumentException(
          s"fromExit cannot handle Exit.Interrupt($fiberId, $cause). " +
            "Interruptions should be handled through pattern matching on Exit, not converted to the error channel."
        )
      )
  }

  /** Converts an Exit to an Eru, handling interruptions by converting them to a default value.
    *
    * This is a test-helper function for cases where interruptions should be treated as a specific
    * result rather than propagated as interruptions. This function is intended for test scenarios
    * where fiber interruption due to cancellation should be treated as a success case.
    *
    * @param exit
    *   the Exit to convert
    * @param interruptedValue
    *   the value to use if the Exit is an interruption
    * @return
    *   an Eru effect representing the Exit outcome
    */
  private[eru] def fromExitOrInterrupted[E, A](exit: Exit[E, A], interruptedValue: A): Eru[E | Throwable, A] =
    exit match {
      case Exit.Success(value) => Eru.succeed(value)
      case Exit.Failure(error) => Eru.fail(error)
      case Exit.Die(throwable) => Eru.effect(throw throwable)
      case Exit.Interrupt(_, _) => Eru.succeed(interruptedValue)
    }

  /** Executes an effectful function for each element in a collection, discarding results.
    *
    * Elements are processed sequentially, left to right, stopping at the first error.
    *
    * @param as
    *   the collection of elements to process
    * @param f
    *   the function to apply to each element
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @tparam B
    *   the result type (discarded)
    * @return
    *   an effect that executes the function for each element and succeeds with Unit
    */
  def foreachDiscard[E, A, B](as: Iterable[A])(f: A => Eru[E, B]): Eru[E, Unit] = {
    as.foldLeft(succeed(()))((accEru, element) => accEru.flatMap(_ => f(element).map(_ => ())))
  }

  /** Executes an effectful function for each element in a collection, collecting results.
    *
    * Elements are processed sequentially, left to right, stopping at the first error. Results are
    * returned in input order.
    *
    * @param as
    *   the collection of elements to process
    * @param f
    *   the function to apply to each element
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @tparam B
    *   the result type
    * @return
    *   an effect that executes the function for each element and collects results
    */
  def foreach[E, A, B](as: Iterable[A])(f: A => Eru[E, B]): Eru[E, List[B]] = {
    as.foldLeft(succeed(List.empty[B])) { (accEru, element) =>
      accEru.flatMap { acc =>
        f(element).map(result => result :: acc)
      }
    }.map(_.reverse)
  }

  /** Collects all effects in a collection, executing them sequentially.
    *
    * @param as
    *   the collection of effects to execute
    * @tparam E
    *   the error type
    * @tparam A
    *   the result type
    * @return
    *   an effect that executes all effects and collects results
    */
  def collectAll[E, A](as: Iterable[Eru[E, A]]): Eru[E, List[A]] = {
    as.foldLeft(succeed(List.empty[A])) { (accEru, effect) =>
      accEru.flatMap { acc =>
        effect.map(result => result :: acc)
      }
    }.map(_.reverse)
  }

  /** Collects all effects in a collection, executing them sequentially and discarding results.
    *
    * @param as
    *   the collection of effects to execute
    * @tparam E
    *   the error type
    * @tparam A
    *   the result type
    * @return
    *   an effect that executes all effects and succeeds with Unit
    */
  def collectAllDiscard[E, A](as: Iterable[Eru[E, A]]): Eru[E, Unit] =
    foreachDiscard(as)(identity)

  /** Reduces a collection of elements using an effectful function from left to right.
    *
    * @param as
    *   the collection to reduce
    * @param zero
    *   the initial accumulator value
    * @param f
    *   the reduction function
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @tparam S
    *   the accumulator type
    * @return
    *   an effect that reduces the collection to a single value
    */
  def foldLeft[E, A, S](as: Iterable[A])(zero: S)(f: (S, A) => Eru[E, S]): Eru[E, S] = {
    @tailrec def peel(remaining: List[A], acc: S): Either[(List[A], Eru[E, S]), Eru[E, S]] =
      remaining match {
        case Nil => Right(succeed(acc))
        case head :: tail =>
          f(acc, head) match {
            case Succeed(next) => peel(tail, next)
            case effectful => Left((tail, effectful))
          }
      }

    def build(remaining: List[A], acc: S): Eru[E, S] =
      peel(remaining, acc) match {
        case Right(result) => result
        case Left((tail, effectful)) => effectful.flatMap(next => build(tail, next))
      }

    build(as.toList, zero)
  }

  /** Reduces a collection of elements using an effectful function from right to left.
    *
    * @param as
    *   the collection to reduce
    * @param zero
    *   the initial accumulator value
    * @param f
    *   the reduction function
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @tparam S
    *   the accumulator type
    * @return
    *   an effect that reduces the collection to a single value
    */
  def foldRight[E, A, S](as: Iterable[A])(zero: S)(f: (A, S) => Eru[E, S]): Eru[E, S] =
    foldLeft(as.toList.reverse)(zero)((acc, a) => f(a, acc))

  /** Conditionally executes an effect when the condition is true.
    *
    * @param condition
    *   the boolean condition to evaluate
    * @param effect
    *   the effect to execute when condition is true
    * @tparam E
    *   the error type
    * @return
    *   an effect that executes the given effect if condition is true, otherwise succeeds with Unit
    */
  def when[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit] =
    if (condition) effect else unit

  /** Conditionally executes an effect when the condition is false.
    *
    * @param condition
    *   the boolean condition to evaluate
    * @param effect
    *   the effect to execute when condition is false
    * @tparam E
    *   the error type
    * @return
    *   an effect that executes the given effect if condition is false, otherwise succeeds with Unit
    */
  def unless[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit] =
    if (condition) unit else effect

  /** Conditionally returns one of two values based on a boolean condition.
    *
    * @param condition
    *   the boolean condition to evaluate
    * @param onTrue
    *   the value to return when condition is true
    * @param onFalse
    *   the value to return when condition is false
    * @tparam A
    *   the result type
    * @return
    *   an effect that succeeds with onTrue if condition is true, otherwise onFalse
    */
  def cond[A](condition: Boolean, onTrue: A, onFalse: A): Eru[Nothing, A] =
    if (condition) succeed(onTrue) else succeed(onFalse)

  /** Iterates an effect starting with an initial value until a predicate is satisfied.
    *
    * Construction peels the pure prefix in constant stack: while `f` yields `Succeed`, it advances
    * statically (the same eager fusion as a `flatMap` fold), stopping at the first effectful step
    * and continuing from it via a deferred node — so construction never recurses on the JVM stack.
    *
    * @param initial
    *   the initial value to start iteration with
    * @param f
    *   the function to apply in each iteration
    * @param predicate
    *   the predicate to check for termination (checked on the result)
    * @tparam E
    *   the error type
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the final value when predicate is satisfied
    */
  def iterate[E, A](initial: A)(f: A => Eru[E, A])(predicate: A => Boolean): Eru[E, A] = {
    @tailrec def peel(current: A): Either[Eru[E, A], Eru[E, A]] =
      if (predicate(current)) Right(succeed(current))
      else
        f(current) match {
          case Succeed(next) => peel(next)
          case effectful => Left(effectful)
        }

    def build(current: A): Eru[E, A] =
      peel(current) match {
        case Right(result) => result
        case Left(effectful) => effectful.flatMap(next => build(next))
      }

    build(initial)
  }

  /** Repeats an effect forever, never returning normally.
    *
    * @param effect
    *   the effect to repeat infinitely
    * @tparam E
    *   the error type
    * @return
    *   an effect that never completes normally
    */
  def forever[E](effect: Eru[E, Unit]): Eru[E, Nothing] =
    effect.flatMap(_ => defer(forever(effect)))

  /** Repeats an effect a specified number of times.
    *
    * @param n
    *   the number of times to repeat the effect
    * @param effect
    *   the effect to repeat
    * @tparam E
    *   the error type
    * @tparam A
    *   the result type
    * @return
    *   an effect that succeeds with Unit after n repetitions
    */
  def repeatN[E, A](n: Int)(effect: Eru[E, A]): Eru[E, Unit] = {
    @tailrec def peel(remaining: Int): Either[Eru[E, A], Eru[E, Unit]] =
      if (remaining <= 0) Right(unit)
      else
        effect match {
          case Succeed(_) => peel(remaining - 1)
          case effectful => Left(effectful)
        }

    def build(remaining: Int): Eru[E, Unit] =
      peel(remaining) match {
        case Right(result) => result
        case Left(effectful) => effectful.flatMap(_ => build(remaining - 1))
      }

    build(n)
  }

  /** Repeats an effect until a predicate is satisfied on the result.
    *
    * @param effect
    *   the effect to repeat
    * @param predicate
    *   the predicate to check for termination
    * @tparam E
    *   the error type
    * @tparam A
    *   the result type
    * @return
    *   an effect that yields the final result when predicate is satisfied
    */
  def repeatUntil[E, A](effect: Eru[E, A])(predicate: A => Boolean): Eru[E, A] = {
    def loop: Eru[E, A] = effect.flatMap { result =>
      if (predicate(result)) succeed(result) else loop
    }
    loop
  }

  /** Filters effects in a collection, collecting only successful results that satisfy a predicate.
    *
    * @param as
    *   the collection of effects to filter
    * @param predicate
    *   the predicate to apply to successful results
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @return
    *   an effect that yields a list of values that satisfy the predicate
    */
  def filter[E, A](as: Iterable[Eru[E, A]])(predicate: A => Boolean): Eru[E, List[A]] = {
    def loop(remaining: List[Eru[E, A]], acc: List[A]): Eru[E, List[A]] = remaining match {
      case Nil => succeed(acc.reverse)
      case head :: tail =>
        head.flatMap { value =>
          if (predicate(value)) loop(tail, value :: acc)
          else loop(tail, acc)
        }
    }
    loop(as.toList, Nil)
  }

  /** Partitions a collection by applying an effectful predicate to each element.
    *
    * @param as
    *   the collection to partition
    * @param f
    *   the effectful predicate function
    * @tparam E
    *   the error type
    * @tparam A
    *   the element type
    * @return
    *   an effect that yields a pair of lists: (satisfied, not satisfied)
    */
  def partition[E, A](as: Iterable[A])(f: A => Eru[E, Boolean]): Eru[E, (List[A], List[A])] = {
    def loop(remaining: List[A], trueAcc: List[A], falseAcc: List[A]): Eru[E, (List[A], List[A])] =
      remaining match {
        case Nil => succeed((trueAcc.reverse, falseAcc.reverse))
        case head :: tail =>
          f(head).flatMap { result =>
            if (result) loop(tail, head :: trueAcc, falseAcc)
            else loop(tail, trueAcc, head :: falseAcc)
          }
      }
    loop(as.toList, Nil, Nil)
  }

  /** Repeats an effect exactly N times, starting from an initial value.
    *
    * This is a more constrained version of `iterate` that's useful when you know the exact number
    * of iterations needed. The implementation uses iterative Eru chain building to ensure stack
    * safety even with large iteration counts.
    *
    * @param start
    *   the initial value
    * @param n
    *   the number of iterations (must be >= 0)
    * @param step
    *   the function to apply at each step
    * @tparam E
    *   the error type
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the result after exactly n iterations
    * @example
    *   {{{
    * // Build a computation that increments a value 1000 times safely
    * val result = Eru.iterateN(0, 1000)(x => Eru.succeed(x + 1))
    * // result.unsafeRunSync() == 1000
    *   }}}
    */
  def iterateN[E, A](start: A, n: Int)(step: A => Eru[E, A]): Eru[String | E, A] = {
    if (n < 0) {
      fail(s"iterateN requires n >= 0, got: $n")
    } else if (n == 0) {
      succeed(start)
    } else {
      (1 to n).foldLeft(succeed(start)) { (accEru, _) =>
        accEru.flatMap(step)
      }
    }
  }

  /** Builds a list by repeatedly applying a function until it returns None.
    *
    * This is similar to `List.unfold` but works with effects. The implementation uses an iterative
    * approach with a bounded iteration count to ensure stack safety. The function will generate
    * elements until either the generator function returns None or the maximum element limit is
    * reached.
    *
    * @param seed
    *   the initial value
    * @param f
    *   function that returns Some(element, nextSeed) to continue or None to stop
    * @tparam E
    *   the error type
    * @tparam A
    *   the seed type
    * @tparam B
    *   the element type
    * @return
    *   an effect that yields the accumulated list
    *
    * Iteration is stack-safe and bounded: at most 15,000 elements are produced. Iterations beyond
    * the bound are truncated.
    *
    * @example
    *   {{{
    * // Generate Fibonacci numbers up to 100
    * val fibs = Eru.unfold((0, 1)) { case (a, b) =>
    *   if (a > 100) Eru.succeed(None)
    *   else Eru.succeed(Some((a, (b, a + b))))
    * }
    * // Generates: List(0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89)
    *   }}}
    */
  def unfold[E, A, B](seed: A)(f: A => Eru[E, Option[(B, A)]]): Eru[E, List[B]] = {
    val maxElements = 15000

    (0 until maxElements)
      .foldLeft(succeed((seed, List.empty[B], false))) { (accEru, _) =>
        accEru.flatMap { case (currentSeed, acc, done) =>
          if (done) succeed((currentSeed, acc, done))
          else {
            f(currentSeed).map {
              case None => (currentSeed, acc, true)
              case Some((element, nextSeed)) => (nextSeed, element :: acc, false)
            }
          }
        }
      }
      .map { case (_, acc, _) => acc.reverse }
  }

  /** Sequences a list of effects into a single effect that produces a list of results.
    *
    * All effects are executed sequentially in order. The implementation uses an iterative approach
    * with foldLeft to ensure stack safety even with large collections. If any effect fails, the
    * entire sequence fails immediately (fail-fast semantics).
    *
    * @param effects
    *   the list of effects to sequence
    * @tparam E
    *   the error type
    * @tparam A
    *   the result type
    * @return
    *   an effect that yields a list of all results
    * @example
    *   {{{
    * // Sequence multiple database queries safely
    * val queries = List(
    *   fetchUser(1), fetchUser(2), fetchUser(3)
    * )
    * val users = Eru.sequence(queries) // Executes all queries in order
    *   }}}
    */
  def sequence[E, A](effects: List[Eru[E, A]]): Eru[E, List[A]] = {
    effects
      .foldLeft(succeed(List.empty[A])) { (accEru, effect) =>
        accEru.flatMap { acc =>
          effect.map(result => result :: acc)
        }
      }
      .map(_.reverse)
  }

  /** Maps each element through an effectful function and sequences the results.
    *
    * This is equivalent to `sequence(inputs.map(f))` but more efficient as it avoids creating
    * intermediate collections. The implementation uses an iterative approach with foldLeft to
    * ensure stack safety even with large input collections. Processing occurs sequentially, and the
    * function fails fast on the first error encountered.
    *
    * @param inputs
    *   the list of inputs to process
    * @param f
    *   the effectful function to apply to each input
    * @tparam A
    *   the input type
    * @tparam E
    *   the error type
    * @tparam B
    *   the output type
    * @return
    *   an effect that yields a list of all results
    * @example
    *   {{{
    * // Process user IDs into user profiles safely
    * val userIds = List(1, 2, 3, 4, 5)
    * val profiles = Eru.traverse(userIds)(id => fetchUserProfile(id))
    * // More efficient than: Eru.sequence(userIds.map(fetchUserProfile))
    *   }}}
    */
  def traverse[A, E, B](inputs: List[A])(f: A => Eru[E, B]): Eru[E, List[B]] =
    inputs
      .foldLeft(succeed(List.empty[B])) { (accEru, input) =>
        accEru.flatMap { acc =>
          f(input).map(result => result :: acc)
        }
      }
      .map(_.reverse)

  /** A successful `Eru` containing `Unit`. */
  val unit: Eru[Nothing, Unit] = succeed(())

  /** Forks a computation onto a separate logical fiber.
    *
    * Creates a new fiber with its own identity for the given computation. The returned effect
    * produces a fiber handle that provides operations for awaiting the result and managing the
    * fiber lifecycle.
    *
    * In the synchronous kernel the child is evaluated eagerly and inline; in concurrent runtimes
    * (eru-runtime) forked fibers run on virtual threads. Finalizers from un-awaited fibers are
    * executed at program completion to prevent resource leaks. This operation is pure and
    * referentially transparent - it describes the intent to fork without actually performing the
    * execution until the returned effect is evaluated.
    *
    * @param computation
    *   the computation to execute on a separate fiber
    * @tparam E
    *   the error type of the computation
    * @tparam A
    *   the success type of the computation
    * @return
    *   an effect that produces a fiber handle when executed
    */
  @scala.annotation.targetName("forkEru")
  def fork[E, A](computation: Eru[E, A]): Eru[Nothing, EruFiber[E, A]] = Fork(computation)

  /** Creates an Eru that awaits the given fiber.
    *
    * This operation waits for the fiber to complete and returns its Exit outcome, which contains
    * either the successful result, a typed error, or information about how the fiber terminated
    * (such as being interrupted).
    *
    * The await operation is pure and referentially transparent - multiple await calls on the same
    * fiber will all receive the same Exit outcome.
    *
    * @param fiber
    *   the fiber to await
    * @tparam E
    *   the error type of the fiber's computation
    * @tparam A
    *   the success type of the fiber's computation
    * @return
    *   an effect that yields the fiber's exit outcome when executed
    */
  def await[E, A](fiber: EruFiber[E, A]): Eru[E, Exit[E, A]] = Await(fiber)

  /** Schedules an effect to run in a new fiber at the given absolute time.
    *
    * When a timer service is available, this returns immediately after scheduling
    * (fire-and-forget): the computation is executed at (or shortly after) `epochMillis`. When no
    * timer service is available (synchronous kernel), the computation is executed inline before
    * this effect completes.
    *
    * @param epochMillis
    *   target execution time in milliseconds since epoch
    * @param effect
    *   the computation to run at the scheduled time
    * @return
    *   an effect that completes after scheduling
    */
  def at[E, A](epochMillis: Long)(effect: => Eru[E, A]): Eru[Nothing, Unit] =
    At(epochMillis, () => effect)

  /** Schedules an effect to run in a new fiber after the given delay.
    *
    * The delay is measured on the monotonic clock: wall-clock adjustments (NTP corrections, manual
    * changes) neither shorten nor lengthen it. When a timer service is available the effect runs
    * at-or-after the delay (fire-and-forget); in the synchronous kernel it is executed inline
    * before this effect completes. Use [[at]] for absolute, wall-addressed scheduling.
    *
    * @param delay
    *   the duration to wait before running the effect
    * @param effect
    *   the computation to run after the delay
    * @return
    *   an effect that completes after scheduling
    */
  def after[E, A](delay: java.time.Duration)(effect: => Eru[E, A]): Eru[Nothing, Unit] =
    After(math.max(0L, (delay.toNanos + 999_999L) / 1_000_000L), () => effect)

  /** Executes an Eru computation and captures both its result and accumulated finalizers.
    *
    * This method provides a public API for runtime backends to execute computations while
    * preserving finalizer information for proper integration with concurrent execution models. It
    * enables scheduler implementations to maintain correct FILO finalizer semantics across fiber
    * boundaries.
    *
    * The method executes the computation synchronously and returns both the Exit outcome and all
    * finalizers that were accumulated during execution. This allows concurrent backends to store
    * finalizers alongside fiber results for later execution in the correct order.
    *
    * @param computation
    *   the computation to execute
    * @tparam E
    *   the error type of the computation
    * @tparam A
    *   the success type of the computation
    * @return
    *   a tuple containing the Exit outcome and list of finalizers
    */
  private[eru] def executeWithFinalizers[E, A](computation: Eru[E, A]): (Exit[E, A], List[() => Eru[Nothing, Unit]]) =
    interpreter.executeWithFinalizers(computation)

  /** The private, cast-free, and stack-safe interpreter for the Eru data type. */
  private object interpreter {

    /** Helper method for consistent error handling in both runSync variants.
      *
      * This consolidates the common pattern of handling Either[E, A] results by throwing
      * appropriate exceptions, avoiding code duplication between runSync and runSyncWithObserver.
      */
    @inline private def handleRunResult[E, A](either: Either[E, A]): A = either match {
      case Left(error) =>
        error match {
          case t: Throwable => throw t
          case e => throw EruException(e)
        }
      case Right(value) => value
    }

    /** Executes an Eru computation and captures both its result and accumulated finalizers.
      *
      * This method provides the implementation for the public API that runtime backends use to
      * execute computations while preserving finalizer information. It now uses the unified
      * fiber-aware interpreter to ensure consistent behavior with unsafeRunSync.
      *
      * The exception handling specifically catches InterruptedWithFinalizers to properly merge
      * finalizers from both the interrupted computation and any fibers that were forked before the
      * interruption, ensuring proper resource cleanup.
      */
    def executeWithFinalizers[E, A](computation: Eru[E, A]): (Exit[E, A], List[() => Eru[Nothing, Unit]]) = {
      val fibers = newFiberSet
      try {
        val ir = runFiberSafe(EvalState.Eval(computation, Continuation.End()), Nil, Hooks.Noop, None, fibers)
        val exit = ir.exit match {
          case Exit.Failure(t: Throwable) => Exit.Die(t)
          case other => other
        }
        (exit, fibers.drainFinalizers(ir.finalizers))
      } catch {
        case interrupted: InterruptedWithFinalizers =>
          (Exit.Interrupt(interrupted.fiberId, interrupted.cause), fibers.drainFinalizers(interrupted.finalizers))
        case _: InterruptedException =>
          (Exit.Interrupt(FiberId.fresh(), InterruptCause.Cancelled()), fibers.drainFinalizers(Nil))
        case NonFatal(ex) =>
          (Exit.Die(ex), fibers.drainFinalizers(Nil))
      }
    }

    private type Finalizer = () => Eru[Nothing, Unit]

    private trait Hooks {
      def onStep(label: => String): Unit
    }
    private object Hooks {
      val Noop: Hooks = _ => ()
      final class ObserverHooks(val scope: ScopeId, val observer: EruObserver) extends Hooks {
        def onStep(label: => String): Unit = observer.onEvent(EruEvent.Step(scope, label))
      }
    }

    /** State machine for the interpreter loop.
      *
      * `Mid` is existential (hidden by the GADT). The method's type parameters `[E, Out]` stay
      * fixed across all tail-recursive iterations.
      */
    private enum EvalState[E, Out] {
      case Eval[E0, Mid, Out0](
        eru: Eru[E0, Mid],
        cont: Continuation[E0, Mid, Out0]
      ) extends EvalState[E0, Out0]

      case ApplySuccess[E0, Mid, Out0](
        value: Mid,
        cont: Continuation[E0, Mid, Out0]
      ) extends EvalState[E0, Out0]

      case ApplyFailure[E0, Mid, Out0](
        error: E0,
        cont: Continuation[E0, Mid, Out0]
      ) extends EvalState[E0, Out0]
    }

    /** Sub-evaluate an Eru to completion with End() continuation. */
    private def evalSub[E, A](
      eru: Eru[E, A],
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: FiberSet
    ): (Either[E, A], List[Finalizer]) = {
      val ir = runFiberSafe(EvalState.Eval(eru, Continuation.End()), fins, hooks, currentFiberId, outstandingFibers)
      val either = ir.exit match {
        case Exit.Success(value) => Right(value)
        case Exit.Failure(error) => Left(error)
        case Exit.Interrupt(fid, cause) => throw new InterruptedWithFinalizers(fid, cause, ir.finalizers)
        case Exit.Die(_) => throw new AssertionError("unreachable: Die not produced internally")
      }
      (either, ir.finalizers)
    }

    /** Sub-evaluate source inside an Ensure node, handling InterruptedWithFinalizers. */
    private def evalWithEnsure[E, A](
      source: Eru[E, A],
      fin: Finalizer,
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: FiberSet
    ): (Either[E, A], List[Finalizer]) = {
      try {
        val (result, sourceFins) = evalSub(source, fins, hooks, currentFiberId, outstandingFibers)
        (result, fin :: sourceFins)
      } catch {
        case interrupted: InterruptedWithFinalizers =>
          throw new InterruptedWithFinalizers(
            interrupted.fiberId,
            interrupted.cause,
            fin :: interrupted.finalizers
          )
      }
    }

    /** Evaluate an interruptible blocking thunk, converting InterruptedException to fiber
      * interruption.
      */
    private def evalInterruptible[A](
      thunk: () => A,
      currentFiberId: Option[FiberId],
      fins: List[Finalizer]
    ): A = {
      try {
        thunk()
      } catch {
        case _: InterruptedException =>
          val fiberId = currentFiberId.getOrElse(FiberId.fresh())
          throw new InterruptedWithFinalizers(fiberId, InterruptCause.Cancelled(), fins)
      }
    }

    /** Handle Suspend registration and poll/wait for the callback result. Type parameters E, A are
      * inferred from register's type signature.
      */
    private def evalSuspend[E, A](
      register: (Either[E, A] => Unit) => Eru[Nothing, Unit],
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: FiberSet
    ): (Either[E, A], List[Finalizer]) = {
      val cbBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
      val cb: Either[E, A] => Unit = ea => cbBox.set(Some(ea))
      val (_, fsAfterReg) = evalSub(register(cb), fins, hooks, currentFiberId, outstandingFibers)
      pollOrWait(cbBox, fsAfterReg)
    }

    /** Poll cbBox; if result ready return it, else spin-wait (async scheduler) or throw (sync). */
    private def pollOrWait[E, A](
      cbBox: java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]],
      fsAfterReg: List[Finalizer]
    ): (Either[E, A], List[Finalizer]) = {
      cbBox.get match {
        case Some(result) => (result, fsAfterReg)
        case None =>
          val ex = new IllegalStateException(
            "Eru.suspend: asynchronous registration is not supported in the synchronous kernel; the register function must invoke the callback synchronously."
          )
          drainFinalizers(fsAfterReg)
          throw ex
      }
    }

    /** Fast-path interpreter for simple effect chains that avoids allocations.
      *
      * This interpreter handles common effect patterns using direct @tailrec recursion instead of
      * the state machine, eliminating allocation overhead for simple cases. When it encounters an
      * unsupported case, it throws FastPathUnsupported to trigger fallback to the safe
      * state-machine interpreter.
      *
      * Supported cases:
      *   - Terminal operations: Succeed, Fail, Effect, EffectTotal
      *   - Map fusion: MapChain
      *   - Error handling: MapError, Attempt
      *
      * Unsupported cases (triggers fallback):
      *   - Chains (flatMap) and complex continuations (Compose)
      *   - Advanced operations (Fork, Await, Suspend, Ensure, Debug, etc.)
      *   - Any case requiring hooks or finalizers
      *
      * @param eru
      *   the effect to interpret
      * @param fins
      *   accumulated finalizers (must be empty for fast path)
      * @return
      *   the result and any finalizers produced
      * @throws FastPathUnsupported
      *   when encountering an unsupported case
      */
    private def runFast[E, A](
      eru: Eru[E, A],
      fins: List[Finalizer]
    ): (Either[E, A], List[Finalizer]) = {
      if (fins.nonEmpty) {
        throw new FastPathUnsupported()
      }

      eru match {
        case Succeed(value) => (Right(value), fins)

        case Fail(error) => (Left(error), fins)

        case Effect(thunk) => (thunk(), fins)

        case EffectTotal(thunk) => (Right(thunk()), fins)

        case MapChain(source, f) =>
          try {
            val (sourceResult, sourceFins) = runFast(source, fins)
            sourceResult match {
              case Right(value) => (Right(f(value)), sourceFins)
              case Left(error) => (Left(error), sourceFins)
            }
          } catch {
            case _: FastPathUnsupported =>
              throw new FastPathUnsupported()
          }

        case MapError(source, f) =>
          try {
            val (sourceResult, sourceFins) = runFast(source, fins)
            sourceResult match {
              case Right(value) => (Right(value), sourceFins)
              case Left(error) => (Left(f(error)), sourceFins)
            }
          } catch {
            case _: FastPathUnsupported =>
              throw new FastPathUnsupported()
          }

        case Attempt(source) =>
          try {
            val (sourceResult, sourceFins) = runFast(source, fins)
            sourceResult match {
              case Left(e) => (Right(Result.Failure(e)), sourceFins)
              case Right(a) => (Right(Result.Success(a)), sourceFins)
            }
          } catch {
            case _: FastPathUnsupported =>
              throw new FastPathUnsupported()
          }

        case _ =>
          throw new FastPathUnsupported()
      }
    }

    /** Cold-path Eru interpreter for cases outside the hot loop.
      *
      * Handles 12 cold Eru case classes as a separate method to keep runFiberSafe's bytecode small
      * enough for C2 to compile without path-merging-induced ZGC barrier elision. Returns the next
      * (EvalState, finalizers) pair for runFiberSafe to tail-recurse on.
      */
    private def stepColdEru[E, Mid, Out](
      eru: Eru[E, Mid],
      cont: Continuation[E, Mid, Out],
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: FiberSet
    ): (EvalState[E, Out], List[Finalizer]) =
      eru match {
        case Debug(source, label) =>
          hooks.onStep(label())
          (EvalState.Eval(source, cont), fins)

        case MapChain(source, f) =>
          val (sourceResult, sourceFins) = evalSub(source, fins, hooks, currentFiberId, outstandingFibers)
          sourceResult match {
            case Right(value) => (EvalState.ApplySuccess(f(value), cont), sourceFins)
            case Left(error) => (EvalState.ApplyFailure(error, cont), sourceFins)
          }

        case RecoverWith(source, pf) =>
          val (sourceResult, sourceFins) = evalSub(source, fins, hooks, currentFiberId, outstandingFibers)
          sourceResult match {
            case Right(value) =>
              (EvalState.ApplySuccess(value, cont), sourceFins)
            case Left(error) =>
              if (pf.isDefinedAt(error))
                (EvalState.Eval(pf(error), cont), sourceFins)
              else
                (EvalState.ApplyFailure(error, cont), sourceFins)
          }

        case MapError(source, f) =>
          val (sourceResult, sourceFins) = evalSub(source, fins, hooks, currentFiberId, outstandingFibers)
          sourceResult match {
            case Right(value) => (EvalState.ApplySuccess(value, cont), sourceFins)
            case Left(error) => (EvalState.ApplyFailure(f(error), cont), sourceFins)
          }

        case Zip(left, right) =>
          val (leftResult, leftFins) = evalSub(left, fins, hooks, currentFiberId, outstandingFibers)
          leftResult match {
            case Right(a) =>
              val (rightResult, rightFins) = evalSub(right, leftFins, hooks, currentFiberId, outstandingFibers)
              rightResult match {
                case Right(b) => (EvalState.ApplySuccess((a, b), cont), rightFins)
                case Left(e) => (EvalState.ApplyFailure(e, cont), rightFins)
              }
            case Left(e) =>
              (EvalState.ApplyFailure(e, cont), leftFins)
          }

        case Attempt(source) =>
          val (sourceResult, sourceFins) = evalSub(source, fins, hooks, currentFiberId, outstandingFibers)
          sourceResult match {
            case Right(a) => (EvalState.ApplySuccess(Result.Success(a), cont), sourceFins)
            case Left(e) => (EvalState.ApplySuccess(Result.Failure(e), cont), sourceFins)
          }

        case Ensure(source, fin) =>
          val (result, sourceFins) = evalWithEnsure(source, fin, fins, hooks, currentFiberId, outstandingFibers)
          result match {
            case Right(value) => (EvalState.ApplySuccess(value, cont), sourceFins)
            case Left(error) => (EvalState.ApplyFailure(error, cont), sourceFins)
          }

        case Suspend(register) =>
          val (result, fsSuspend) = evalSuspend(register, fins, hooks, currentFiberId, outstandingFibers)
          result match {
            case Right(value) => (EvalState.ApplySuccess(value, cont), fsSuspend)
            case Left(error) => (EvalState.ApplyFailure(error, cont), fsSuspend)
          }

        case Fork(computation) =>
          val childFiberId = FiberId.fresh()
          hooks match {
            case obs: Hooks.ObserverHooks =>
              obs.observer.onEvent(EruEvent.FiberStarted(childFiberId))
            case _ =>
          }
          val (childResult, childFinalizers) = evalSub(
            computation,
            Nil,
            hooks,
            Some(childFiberId),
            newFiberSet
          )
          val childExit = childResult match {
            case Right(value) => Exit.Success(value)
            case Left(error) => Exit.Failure(error)
          }
          hooks match {
            case obs: Hooks.ObserverHooks =>
              obs.observer.onEvent(EruEvent.FiberCompleted(childFiberId, childExit))
            case _ =>
          }
          val completedFiber = EruFiber.withId(childFiberId, childExit, childFinalizers)
          if completedFiber.finalizers.nonEmpty then outstandingFibers.add(completedFiber)
          (EvalState.ApplySuccess(completedFiber, cont), fins)

        case Await(fiber) =>
          outstandingFibers.remove(fiber)
          drainFinalizers(fiber.finalizers)
          (EvalState.ApplySuccess(fiber.exit, cont), fins)

        case InterruptibleBlocking(thunk) =>
          val value = evalInterruptible(thunk, currentFiberId, fins)
          (EvalState.ApplySuccess(value, cont), fins)

        case At(epochMillis, computation) =>
          TimerService.get match {
            case Some(timer) =>
              val task: Runnable = () => {
                try {
                  val (_, finalizers) = Eru.executeWithFinalizers(computation())
                  finalizers.foreach(f =>
                    try f().unsafeRunSync()
                    catch { case _: Exception => () }
                  )
                } catch { case _: Throwable => () }
              }
              val now = System.currentTimeMillis()
              val _ = timer.schedule(if epochMillis <= now then now else epochMillis, task)
              (EvalState.ApplySuccess((), cont), fins)
            case None =>
              val childId = FiberId.fresh()
              evalSub(computation(), Nil, hooks, Some(childId), outstandingFibers)
              (EvalState.ApplySuccess((), cont), fins)
          }

        case After(delayMillis, computation) =>
          TimerService.get match {
            case Some(timer) =>
              val task: Runnable = () => {
                try {
                  val (_, finalizers) = Eru.executeWithFinalizers(computation())
                  finalizers.foreach(f =>
                    try f().unsafeRunSync()
                    catch { case _: Exception => () }
                  )
                } catch { case _: Throwable => () }
              }
              val _ = timer.scheduleAfter(delayMillis, task)
              (EvalState.ApplySuccess((), cont), fins)
            case None =>
              val childId = FiberId.fresh()
              evalSub(computation(), Nil, hooks, Some(childId), outstandingFibers)
              (EvalState.ApplySuccess((), cont), fins)
          }

        case _ => throw new AssertionError("unreachable: hot case in stepColdEru")
      }

    /** Stack-safe, cast-free state machine interpreter using sealed enum pattern matching.
      *
      * A single @tailrec method. EvalState has 3 cases and Continuation has 4 cases — all concrete
      * types known at compile time. Pattern matching on sealed enum compiles to static tableswitch,
      * eliminating megamorphic dispatch from lambda-based trampolining.
      *
      * Key features:
      *   - Zero lambdas in the trampoline: pure data ADT, no Function1 allocations
      *   - Monomorphic allocation: no vtable lookup, no speculative inlining, no deoptimization
      *   - Zero casts: Continuation GADT preserves type safety through all phases
      *   - Eager evaluation: Fork runs child immediately in synchronous kernel
      *   - FILO finalizer ordering: Child finalizers merge in front to maintain order
      *   - Auto-join: Tracks outstanding fibers to prevent finalizer leakage
      */
    @tailrec
    private def runFiberSafe[E, A](
      state: EvalState[E, A],
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: FiberSet
    ): InterpreterResult[E, A] = {
      state match {
        case EvalState.Eval(eru, cont) =>
          eru match {
            case Succeed(value) =>
              runFiberSafe(EvalState.ApplySuccess(value, cont), fins, hooks, currentFiberId, outstandingFibers)

            case Fail(error) =>
              runFiberSafe(EvalState.ApplyFailure(error, cont), fins, hooks, currentFiberId, outstandingFibers)

            case Effect(thunk) =>
              val result =
                try thunk()
                catch
                  case _: InterruptedException =>
                    throw new InterruptedWithFinalizers(
                      currentFiberId.getOrElse(FiberId.fresh()),
                      InterruptCause.Cancelled(),
                      fins
                    )
              result match {
                case Right(value) =>
                  runFiberSafe(EvalState.ApplySuccess(value, cont), fins, hooks, currentFiberId, outstandingFibers)
                case Left(error) =>
                  runFiberSafe(EvalState.ApplyFailure(error, cont), fins, hooks, currentFiberId, outstandingFibers)
              }

            case EffectTotal(thunk) =>
              val value =
                try thunk()
                catch
                  case _: InterruptedException =>
                    throw new InterruptedWithFinalizers(
                      currentFiberId.getOrElse(FiberId.fresh()),
                      InterruptCause.Cancelled(),
                      fins
                    )
              runFiberSafe(EvalState.ApplySuccess(value, cont), fins, hooks, currentFiberId, outstandingFibers)

            case Chain(source, chainCont) =>
              chainCont match {
                case Continuation.Step(f, _: Continuation.End[?]) =>
                  runFiberSafe(
                    EvalState.Eval(source, Continuation.Step(f, cont)),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )
                case _ =>
                  runFiberSafe(
                    EvalState.Eval(source, Continuation.ComposeK(chainCont, cont)),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )
              }

            case cold =>
              val (nextState, nextFins) = stepColdEru(cold, cont, fins, hooks, currentFiberId, outstandingFibers)
              runFiberSafe(nextState, nextFins, hooks, currentFiberId, outstandingFibers)
          }

        case EvalState.ApplySuccess(value, cont) =>
          cont match {
            case _: Continuation.End[?] =>
              InterpreterResult(Exit.Success(value), fins)

            case Continuation.Step(f, next) =>
              next match {
                case _: Continuation.End[?] =>
                  runFiberSafe(
                    EvalState.Eval(f(value), Continuation.End()),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )
                case _ =>
                  runFiberSafe(EvalState.Eval(f(value), next), fins, hooks, currentFiberId, outstandingFibers)
              }

            case Continuation.Compose(first, g) =>
              runFiberSafe(
                EvalState.ApplySuccess(value, Continuation.ComposeK(first, Continuation.Step(g, Continuation.End()))),
                fins,
                hooks,
                currentFiberId,
                outstandingFibers
              )

            case Continuation.ComposeK(first, second) =>
              first match {
                case _: Continuation.End[?] =>
                  runFiberSafe(EvalState.ApplySuccess(value, second), fins, hooks, currentFiberId, outstandingFibers)

                case Continuation.Step(f, next) =>
                  next match {
                    case _: Continuation.End[?] =>
                      runFiberSafe(EvalState.Eval(f(value), second), fins, hooks, currentFiberId, outstandingFibers)
                    case _ =>
                      runFiberSafe(
                        EvalState.Eval(f(value), Continuation.ComposeK(next, second)),
                        fins,
                        hooks,
                        currentFiberId,
                        outstandingFibers
                      )
                  }

                case Continuation.Compose(inner, g) =>
                  runFiberSafe(
                    EvalState.ApplySuccess(value, Continuation.ComposeK(inner, Continuation.Step(g, second))),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )

                case Continuation.ComposeK(f1, f2) =>
                  runFiberSafe(
                    EvalState.ApplySuccess(value, Continuation.ComposeK(f1, Continuation.ComposeK(f2, second))),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )
              }
          }

        case EvalState.ApplyFailure(error, cont) =>
          cont match {
            case _: Continuation.End[?] =>
              InterpreterResult(Exit.Failure(error), fins)

            case Continuation.Step(_, next) =>
              runFiberSafe(EvalState.ApplyFailure(error, next), fins, hooks, currentFiberId, outstandingFibers)

            case Continuation.Compose(first, g) =>
              runFiberSafe(
                EvalState.ApplyFailure(error, Continuation.ComposeK(first, Continuation.Step(g, Continuation.End()))),
                fins,
                hooks,
                currentFiberId,
                outstandingFibers
              )

            case Continuation.ComposeK(first, second) =>
              first match {
                case _: Continuation.End[?] =>
                  runFiberSafe(EvalState.ApplyFailure(error, second), fins, hooks, currentFiberId, outstandingFibers)

                case Continuation.Step(_, _) =>
                  runFiberSafe(EvalState.ApplyFailure(error, second), fins, hooks, currentFiberId, outstandingFibers)

                case Continuation.Compose(inner, g) =>
                  runFiberSafe(
                    EvalState.ApplyFailure(error, Continuation.ComposeK(inner, Continuation.Step(g, second))),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )

                case Continuation.ComposeK(f1, f2) =>
                  runFiberSafe(
                    EvalState.ApplyFailure(error, Continuation.ComposeK(f1, Continuation.ComposeK(f2, second))),
                    fins,
                    hooks,
                    currentFiberId,
                    outstandingFibers
                  )
              }
          }
      }
    }

    /** Executes all finalizers in FILO (First-In-Last-Out) order with proper nesting support.
      *
      * Uses @tailrec for stack safety. Each finalizer is sub-evaluated via runFiberSafe (a
      * different method), so the recursive drainFinalizers call remains in tail position.
      */
    @tailrec
    private def drainFinalizers(fins: List[Finalizer]): Unit =
      fins match {
        case Nil => ()
        case fin :: rest =>
          val fibers = newFiberSet
          val ir = runFiberSafe(EvalState.Eval(fin(), Continuation.End()), Nil, Hooks.Noop, None, fibers)
          drainFinalizers(fibers.drainFinalizers(ir.finalizers) ::: rest)
      }

    private def runWithObsStack[E, A](
      eru: Eru[E, A],
      scope: ScopeId,
      observer: EruObserver,
      fins: List[Finalizer]
    ): (Either[E, A], List[Finalizer]) = {
      val fibers = newFiberSet
      val ir = runFiberSafe(
        EvalState.Eval(eru, Continuation.End()),
        fins,
        new Hooks.ObserverHooks(scope, observer),
        None,
        fibers
      )
      val either = ir.exit match {
        case Exit.Success(value) => Right(value)
        case Exit.Failure(error) => Left(error)
        case Exit.Interrupt(fid, cause) => throw new InterruptedWithFinalizers(fid, cause, ir.finalizers)
        case Exit.Die(_) => throw new AssertionError("unreachable: Die not produced internally")
      }
      (either, ir.finalizers)
    }

    /** Observer-aware interpreter variant */
    def runSyncWithObserver[E, A](start: Eru[E, A], observer: EruObserver): A = {
      val scope = ScopeId.fresh()
      observer.onEvent(EruEvent.ProgramStart(scope))
      val (either, fins) = runWithObsStack(start, scope, observer, Nil)
      drainFinalizers(fins)
      either match {
        case Left(error) =>
          error match {
            case t: Throwable =>
              observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Defect(t)))
            case e =>
              observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.TypedFailure(e)))
          }
          handleRunResult(either)
        case Right(value) =>
          observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Success))
          value
      }
    }

    /** Fiber-aware synchronous interpreter using eager evaluation with auto-join.
      *
      * When an interrupt surfaces mid-evaluation, the finalizers carried by the exception (plus any
      * outstanding fiber finalizers) are drained before the interrupt is rethrown.
      */
    def runSyncWithFibers[E, A](start: Eru[E, A]): A = {

      val fibers = newFiberSet

      try {
        val (either, fins) = runFast(start, Nil)
        drainFinalizers(fins)
        handleRunResult(either)
      } catch {
        case _: FastPathUnsupported =>
          try {
            val ir = runFiberSafe(EvalState.Eval(start, Continuation.End()), Nil, Hooks.Noop, None, fibers)
            drainFinalizers(fibers.drainFinalizers(ir.finalizers))
            val either = ir.exit match {
              case Exit.Success(value) => Right(value)
              case Exit.Failure(error) => Left(error)
              case Exit.Interrupt(fid, cause) => throw new InterruptedWithFinalizers(fid, cause, Nil)
              case Exit.Die(_) => throw new AssertionError("unreachable: Die not produced internally")
            }
            handleRunResult(either)
          } catch {
            case interrupted: InterruptedWithFinalizers =>
              drainFinalizers(fibers.drainFinalizers(interrupted.finalizers))
              throw interrupted
          }
      }
    }

    /** Fiber-aware observer variant using runFiberSafe with eager evaluation and auto-join */
    def runSyncWithFibersAndObserver[E, A](start: Eru[E, A], observer: EruObserver): A = {

      val scope = ScopeId.fresh()
      val hooks = new Hooks.ObserverHooks(scope, observer)
      val fibers = newFiberSet

      observer.onEvent(EruEvent.ProgramStart(scope))
      try {
        val ir = runFiberSafe(EvalState.Eval(start, Continuation.End()), Nil, hooks, None, fibers)

        drainFinalizers(fibers.drainFinalizers(ir.finalizers))

        ir.exit match {
          case Exit.Success(value) =>
            observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Success))
            value
          case Exit.Failure(error) =>
            error match {
              case t: Throwable =>
                observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Defect(t)))
                throw t
              case e =>
                observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.TypedFailure(e)))
                throw EruException(e)
            }
          case Exit.Die(_) =>
            throw new AssertionError("unreachable: Die not produced internally")
          case Exit.Interrupt(fid, cause) =>
            throw new InterruptedWithFinalizers(fid, cause, Nil)
        }
      } catch {
        case interrupted: InterruptedWithFinalizers =>
          drainFinalizers(fibers.drainFinalizers(interrupted.finalizers))
          observer.onEvent(
            EruEvent.ProgramEnd(scope, Outcome.Defect(new InterruptedException(interrupted.cause.toString)))
          )
          throw interrupted
      }
    }

  }

  private[eru] object Internals {
    enum View[+E, +A] {
      case VSucceed(value: A)
      case VFail(error: E)
      case VEffect(thunk: () => Either[Throwable, A])
      case VEffectTotal(thunk: () => A)
      case VChain[E0, From, To](source: Eru[E0, From], cont: Continuation[E0, From, To]) extends View[E0, To]
      case VMapChain[E0, From, To](source: Eru[E0, From], f: From => To) extends View[E0, To]
      case VRecoverWith[E0, A0, E2, A1 >: A0](source: Eru[E0, A0], pf: PartialFunction[E0, Eru[E2, A1]])
          extends View[E0 | E2, A1]
      case VMapError[E0, A0, E2](source: Eru[E0, A0], f: E0 => E2) extends View[E2, A0]
      case VZip[E0, E1, A0, B0](left: Eru[E0, A0], right: Eru[E1, B0]) extends View[E0 | E1, (A0, B0)]
      case VAttempt[E0, A0](source: Eru[E0, A0]) extends View[Nothing, Result[E0, A0]]
      case VDebug[E0, A0](source: Eru[E0, A0], label: () => String) extends View[E0, A0]
      case VEnsure[E0, A0](source: Eru[E0, A0], finalizer: () => Eru[Nothing, Unit]) extends View[E0, A0]
      case VSuspend[E0, A0](register: (Either[E0, A0] => Unit) => Eru[Nothing, Unit]) extends View[E0, A0]
      case VFork[E0, A0](computation: Eru[E0, A0]) extends View[Nothing, EruFiber[E0, A0]]
      case VAwait[E0, A0](fiber: EruFiber[E0, A0]) extends View[E0, Exit[E0, A0]]
      case VInterruptibleBlocking[A0](thunk: () => A0) extends View[Nothing, A0]
      case VAt[E0, A0](epochMillis: Long, computation: () => Eru[E0, A0]) extends View[Nothing, Unit]
      case VAfter[E0, A0](delayMillis: Long, computation: () => Eru[E0, A0]) extends View[Nothing, Unit]
    }

    import View.*
    def view[E, A](e: Eru[E, A]): View[E, A] = e match {
      case Succeed(value) => VSucceed(value)
      case Fail(error) => VFail(error)
      case Effect(thunk) => VEffect(thunk)
      case EffectTotal(thunk) => VEffectTotal(thunk)
      case Chain(source, cont) => VChain(source, cont)
      case MapChain(source, f) => VMapChain(source, f)
      case RecoverWith(source, pf) => VRecoverWith(source, pf)
      case MapError(source, f) => VMapError(source, f)
      case Zip(left, right) => VZip(left, right)
      case Attempt(source) => VAttempt(source)
      case Debug(source, label) => VDebug(source, label)
      case Ensure(source, finalizer) => VEnsure(source, finalizer)
      case Suspend(register) => VSuspend(register)
      case Fork(computation) => VFork(computation)
      case Await(fiber) => VAwait(fiber)
      case InterruptibleBlocking(thunk) => VInterruptibleBlocking(thunk)
      case At(epochMillis, computation) => VAt(epochMillis, computation)
      case After(delayMillis, computation) => VAfter(delayMillis, computation)
    }

  }

  /** Checks if the current fiber has been interrupted and yields control if needed.
    *
    * This is a cooperative cancellation point for CPU-bound operations. Insert this in long-running
    * loops to allow the fiber to be interrupted.
    *
    * @return
    *   an effect that checks for interruption
    *
    * @example
    *   {{{
    * def cpuIntensive(n: Int): Eru[Nothing, Int] =
    *   Eru.iterate(0) { i =>
    *     for {
    *       _ <- if (i % 1000 == 0) Eru.yieldIfInterrupted else Eru.unit
    *       next <- Eru.succeed(i + 1)
    *     } yield next
    *   }(_ >= n)
    *   }}}
    */
  def yieldIfInterrupted: Eru[Nothing, Unit] =
    Eru.interruptibleBlocking {
      if (Thread.currentThread().isInterrupted) {
        throw new InterruptedException("Cooperative cancellation")
      }
    }.attempt.map(_ => ())

  /** Checks a condition and yields if interrupted.
    *
    * Combines a condition check with cooperative cancellation. Useful for long-running loops that
    * need both a termination condition and cancellation. An interrupted thread surfaces as
    * `Exit.Interrupt` at the run boundary rather than through the typed error channel.
    *
    * @param shouldContinue
    *   the condition to check
    * @return
    *   an effect that succeeds if `shouldContinue` is true and fails with `"Condition false"` in
    *   the typed error channel otherwise; interruption surfaces as `Exit.Interrupt` at the run
    *   boundary, not through the error channel
    */
  def checkAndYield(shouldContinue: => Boolean): Eru[String, Unit] =
    for {
      _ <- yieldIfInterrupted
      _ <- if (shouldContinue) Eru.unit else Eru.fail("Condition false")
    } yield ()
}
