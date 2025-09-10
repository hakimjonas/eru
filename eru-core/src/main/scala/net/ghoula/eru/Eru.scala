package net.ghoula.eru

import scala.util.control.NonFatal
import scala.util.control.TailCalls.{TailRec, done, tailcall}

import net.ghoula.eru.EruObserver.*

/** Internal exception used to preserve finalizers when InterruptedException occurs */
private class InterruptedWithFinalizers(
  val fiberId: FiberId,
  val cause: InterruptCause,
  val finalizers: List[() => Eru[Nothing, Unit]]
) extends InterruptedException(cause.toString)

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

    /** Appends a new function to the end of this continuation stack, maintaining type safety. This
      * is the key operation that allows us to build continuation chains without casts.
      */
    @inline def andThen[E2 >: E, NewOut](g: Out => Eru[E2, NewOut]): Continuation[E2, In, NewOut] = this match {
      case End() => Step(g, End())
      case Step(f, next) => Step(f, next.andThen(g))
    }
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
    * interrupted via thread interruption. Unlike `blocking`, interruption is handled gracefully by
    * converting `InterruptedException` to `Exit.Interrupt` and ensuring proper finalizer execution.
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
    * enabling composition and recovery patterns when working with fiber results. It handles all
    * Exit cases: Success becomes succeed, Failure becomes fail, Die re-throws the exception, and
    * Interrupt fails with InterruptedException in the error channel.
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
    * // Convert fiber results back to Eru for composition
    * val fiber = EruRuntime.fork(Eru.succeed(42)).unsafeRunSync()
    * val computation = for {
    *   exit <- fiber.await
    *   result <- Eru.fromExit(exit)  // Convert Exit back to Eru
    *   doubled <- Eru.succeed(result * 2)
    * } yield doubled
    *   }}}
    */
  def fromExit[E, A](exit: Exit[E, A]): Eru[E | Throwable, A] = exit match {
    case Exit.Success(value) => Eru.succeed(value)
    case Exit.Failure(error) => Eru.fail(error)
    case Exit.Die(throwable) => Eru.effect(throw throwable)
    case Exit.Interrupt(fiberId, cause) =>
      // MATHEMATICAL CORRECTNESS: Interruptions should not be converted to the error channel.
      // Interruptions are control flow operations that should be handled through
      // native interpreter mechanisms, not converted to domain errors.
      //
      // If you need to handle interruptions, use pattern matching on the Exit directly:
      //   exit match {
      //     case Exit.Interrupt(_, _) => /* handle interruption */
      //     case other => Eru.fromExit(other)
      //   }
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
  def fromExitOrInterrupted[E, A](exit: Exit[E, A], interruptedValue: A): Eru[E | Throwable, A] = exit match {
    case Exit.Success(value) => Eru.succeed(value)
    case Exit.Failure(error) => Eru.fail(error)
    case Exit.Die(throwable) => Eru.effect(throw throwable)
    case Exit.Interrupt(_, _) => Eru.succeed(interruptedValue)
  }

  /** A successful `Eru` containing `Unit`. */
  val unit: Eru[Nothing, Unit] = succeed(())

  /** Forks a computation onto a separate logical fiber.
    *
    * Creates a new fiber that will execute the given computation concurrently. The returned effect
    * produces a fiber handle that provides operations for awaiting the result and managing the
    * fiber lifecycle.
    *
    * Finalizers from unawaited fibers are automatically executed at program completion to prevent
    * resource leaks. This operation is pure and referentially transparent - it describes the intent
    * to fork without actually performing the execution until the returned effect is evaluated.
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
    * fiber will all receive the same Exit outcome. The underlying implementation is efficient and
    * handles the cross-platform execution differences transparently.
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
  def executeWithFinalizers[E, A](computation: Eru[E, A]): (Exit[E, A], List[() => Eru[Nothing, Unit]]) =
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
      */
    // In the Eru companion object
    def executeWithFinalizers[E, A](computation: Eru[E, A]): (Exit[E, A], List[() => Eru[Nothing, Unit]]) = {
      initializeAsyncSchedulerIfNeeded()

      val outstandingFibers = collection.mutable.Set.empty[EruFiber[?, ?]]

      try {
        val (either, fins) = runFiberLoop(computation, Nil, Hooks.Noop, None, outstandingFibers).result

        val allFinalizers = outstandingFibers.foldLeft(fins) { (acc, fiber) =>
          fiber.finalizers ++ acc
        }

        val exit = either match {
          case Right(value) => Exit.Success(value)
          case Left(error) =>
            error match {
              case t: Throwable => Exit.Die(t)
              case e => Exit.Failure(e)
            }
        }
        (exit, allFinalizers)
      } catch {
        case interrupted: InterruptedWithFinalizers =>
          // CORRECT: We specifically catch our custom exception here at the boundary.
          // We now have both the finalizers from the interrupted computation and
          // any finalizers from fibers that were forked before the interruption.
          val allFinalizers = outstandingFibers.foldLeft(interrupted.finalizers) { (acc, fiber) =>
            fiber.finalizers ++ acc
          }
          val exit = Exit.Interrupt(interrupted.fiberId, interrupted.cause)
          (exit, allFinalizers)

        case NonFatal(ex) =>
          // A safety net for any other unexpected exceptions.
          val allFinalizers = outstandingFibers.foldLeft(List.empty[() => Eru[Nothing, Unit]]) { (acc, fiber) =>
            fiber.finalizers ++ acc
          }
          (Exit.Die(ex), allFinalizers)
      }
    }

    private type Finalizer = () => Eru[Nothing, Unit]

    private trait Hooks {
      def onStep(label: => String): Unit
    }
    private object Hooks {
      val Noop: Hooks = new Hooks { def onStep(label: => String): Unit = () }
      final class ObserverHooks(val scope: ScopeId, val observer: EruObserver) extends Hooks {
        def onStep(label: => String): Unit = observer.onEvent(EruEvent.Step(scope, label))
      }
    }

    /** Fiber-aware runLoop implementing Strategy A: Eager Fiber Evaluation.
      *
      * This method extends runLoop to handle Fork and Await operations using eager evaluation. Fork
      * executes the child computation immediately to completion and stores the result and
      * finalizers directly in the EruFiber. Await becomes pure structural access with no registry
      * lookups required.
      *
      * Key features:
      *   - Zero type casts: All GADT constraints preserved through direct ADT pattern matching
      *   - Eager evaluation: Fork runs child immediately in synchronous kernel
      *   - FILO finalizer ordering: Child finalizers merge in front to maintain order
      *   - Stack-safe: Uses TailRec for all recursive calls
      *   - Auto-join: Tracks outstanding fibers to prevent finalizer leakage
      */
    private def runFiberLoop[E, A](
      eru: Eru[E, A],
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: collection.mutable.Set[EruFiber[?, ?]]
    ): TailRec[(Either[E, A], List[Finalizer])] =
      eru match {
        case Succeed(value) =>
          done((Right(value), fins))

        case Fail(error) =>
          done((Left(error), fins))

        case Effect(thunk) =>
          done((thunk(), fins))

        case Chain(source, cont) =>
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers)).flatMap {
            case (Right(value), fs) =>
              tailcall(runFiberContinuation(cont, value, fs, hooks, currentFiberId, outstandingFibers))
            case (Left(error), fs) => done((Left(error), fs))
          }

        case MapChain(source, f) =>
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers)).map {
            case (Right(value), fs) => (Right(f(value)), fs)
            case (Left(error), fs) => (Left(error), fs)
          }

        case RecoverWith(source, pf) =>
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers)).flatMap {
            case (Right(value), fs) => done((Right(value), fs))
            case (Left(error), fs) =>
              if (pf.isDefinedAt(error)) {
                tailcall(runFiberLoop(pf(error), fs, hooks, currentFiberId, outstandingFibers))
              } else {
                done((Left(error), fs))
              }
          }

        case MapError(source, f) =>
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers)).map { case (either, fs) =>
            (either.left.map(f), fs)
          }

        case Zip(left, right) =>
          tailcall(runFiberLoop(left, fins, hooks, currentFiberId, outstandingFibers)).flatMap {
            case (Right(a), fsL) =>
              tailcall(runFiberLoop(right, fsL, hooks, currentFiberId, outstandingFibers)).map {
                case (Right(b), fsR) => (Right((a, b)), fsR)
                case (Left(e1), fsR) => (Left(e1), fsR)
              }
            case (Left(e0), fsL) => done((Left(e0), fsL))
          }

        case Attempt(source) =>
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers)).map {
            case (Left(e), fs) => (Right(Result.Failure(e)), fs)
            case (Right(a), fs) => (Right(Result.Success(a)), fs)
          }

        case Debug(source, label) =>
          hooks.onStep(label())
          tailcall(runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers))

        case Ensure(source, fin) =>
          // FIXED: Use TailRec with exception handling to properly collect ensure finalizers
          // even when InterruptedWithFinalizers is thrown from the source computation
          tailcall {
            try {
              runFiberLoop(source, fins, hooks, currentFiberId, outstandingFibers).result match {
                case (either, fs) => done((either, fin :: fs))
              }
            } catch {
              case interrupted: InterruptedWithFinalizers =>
                // When source computation is interrupted, merge the ensure finalizer with
                // the existing finalizers in the exception, then re-throw.
                throw new InterruptedWithFinalizers(
                  interrupted.fiberId,
                  interrupted.cause,
                  fin :: interrupted.finalizers
                )
            }
          }

        case Suspend(register) =>
          handleSuspend(cb => runFiberLoop(register(cb), fins, hooks, currentFiberId, outstandingFibers).result)

        case Fork(computation) =>
          AsyncScheduler.get match {
            case Some(scheduler) =>
              val observer = hooks match {
                case obs: Hooks.ObserverHooks => Some(obs.observer)
                case _ => None
              }

              val asyncFiber = scheduler.scheduleAsync(computation, observer)

              asyncFiber.getCompleted match {
                case Some(completedFiber) =>
                  outstandingFibers += completedFiber
                  done((Right(completedFiber), fins))
                case None =>
                  handleSuspend { callback =>
                    asyncFiber.onComplete { completedFiber =>
                      outstandingFibers += completedFiber
                      callback(Right(completedFiber))
                    }

                    asyncFiber.getCompleted match {
                      case Some(completedFiber) =>
                        outstandingFibers += completedFiber
                        callback(Right(completedFiber))
                      case None =>
                        () // Will complete via onComplete
                    }

                    (Right(()), fins)
                  }
              }

            case None =>
              val childFiberId = FiberId.fresh()

              hooks match {
                case obs: Hooks.ObserverHooks =>
                  obs.observer.onEvent(EruEvent.FiberStarted(childFiberId))
                case _ => // No observer
              }

              val (childResult, childFinalizers) = runFiberLoop(
                computation,
                Nil,
                hooks,
                Some(childFiberId),
                collection.mutable.Set.empty
              ).result

              val childExit = childResult match {
                case Right(value) => Exit.Success(value)
                case Left(error) => Exit.Failure(error)
              }

              hooks match {
                case obs: Hooks.ObserverHooks =>
                  obs.observer.onEvent(EruEvent.FiberCompleted(childFiberId, childExit))
                case _ => // No observer
              }

              val completedFiber = EruFiber.withId(childFiberId, childExit, childFinalizers)
              outstandingFibers += completedFiber
              done((Right(completedFiber), fins))
          }

        case Await(fiber) =>
          outstandingFibers -= fiber
          // When a fiber is awaited, its finalizers must be executed immediately
          // to ensure that the await operation is a true sequential barrier.
          drainFinalizers(fiber.finalizers).result
          // The parent computation continues with its own finalizers.
          done((Right(fiber.exit), fins))

        // Inside the interpreter's runFiberLoop method, in the main pattern match:
        case InterruptibleBlocking(thunk) =>
          try {
            // If the thunk succeeds, continue the loop with its successful value.
            done((Right(thunk()), fins))
          } catch {
            case _: InterruptedException =>
              // When the blocking thread is interrupted (via Thread.interrupt()),
              // we convert to our internal exception format, taking care to preserve
              // the finalizers with it. This allows the finalizers to be preserved
              // as the exception unwinds to the top-level executor.
              val fiberId = currentFiberId.getOrElse(FiberId.fresh())
              throw new InterruptedWithFinalizers(fiberId, InterruptCause.Cancelled(), fins)

            case NonFatal(ex) =>
              // For any other non-fatal error, we re-throw it.
              // It will be caught by the boundary function (e.g., executeWithFinalizers)
              // and correctly converted to an Exit.Die, which is the proper
              // representation for a defect.
              throw ex
          }
      }

    /** Fiber-aware continuation execution to match runFiberLoop */
    @inline private def runFiberContinuation[E, In, Out](
      cont: Continuation[E, In, Out],
      input: In,
      fins: List[Finalizer],
      hooks: Hooks,
      currentFiberId: Option[FiberId],
      outstandingFibers: collection.mutable.Set[EruFiber[?, ?]]
    ): TailRec[(Either[E, Out], List[Finalizer])] = {
      cont match {
        case Continuation.End() =>
          done((Right(input), fins))
        case Continuation.Step(f, next) =>
          tailcall(runFiberLoop(f(input), fins, hooks, currentFiberId, outstandingFibers)).flatMap {
            case (Right(intermediate), fs) =>
              tailcall(runFiberContinuation(next, intermediate, fs, hooks, currentFiberId, outstandingFibers))
            case (Left(error), fs) => done((Left(error), fs))
          }
      }
    }

    /** Executes all finalizers in FILO (First-In-Last-Out) order with proper nesting support.
      *
      * This method processes the accumulated finalizer list by executing each finalizer and
      * handling any nested finalizers they may produce. The execution order is critical: finalizers
      * run in reverse order of their registration (FILO), ensuring that resources are cleaned up in
      * the opposite order of their acquisition.
      *
      * Key execution characteristics:
      *   - Processes finalizers from front to back of the list (which represents FILO order)
      *   - Each finalizer execution can produce additional "inner" finalizers
      *   - Inner finalizers are prepended to the remaining finalizers, maintaining FILO semantics
      *   - Uses TailRec for stack safety when processing long finalizer chains
      *   - Finalizer failures are contained - they don't prevent other finalizers from running
      *
      * Example execution order for fins = [f3, f2, f1]:
      *   1. Execute f3 (most recently added), collect any inner finalizers
      *   2. Execute f2, collect any inner finalizers
      *   3. Execute f1 (first added), collect any inner finalizers
      *   4. Process all collected inner finalizers recursively in FILO order
      *
      * @param fins
      *   List of finalizers to execute (in FILO order)
      * @return
      *   TailRec computation that completes when all finalizers have run
      */
    private def drainFinalizers(fins: List[Finalizer]): TailRec[Unit] =
      fins match {
        case Nil => done(())
        case fin :: rest =>
          val outstandingFibers = collection.mutable.Set.empty[EruFiber[?, ?]]
          tailcall(runFiberLoop(fin(), Nil, Hooks.Noop, None, outstandingFibers)).flatMap { case (_, inner) =>
            val allInnerFinalizers = outstandingFibers.foldLeft(inner) { (acc, fiber) =>
              fiber.finalizers ++ acc
            }
            tailcall(drainFinalizers(allInnerFinalizers ++ rest))
          }
      }

    /** Helper method for handling Suspend case logic.
      *
      * This consolidates the common pattern of creating an AtomicReference callback box and waiting
      * for async completion found in both runWithStack and runWithObsStack.
      *
      * @param executeRegister
      *   function to execute the registration with the appropriate context
      * @tparam E
      *   the error type
      * @tparam A
      *   the success type
      * @return
      *   TailRec computation with the suspended result
      */
    private def handleSuspend[E, A](
      executeRegister: (Either[E, A] => Unit) => (Either[Any, Any], List[Finalizer])
    ): TailRec[(Either[E, A], List[Finalizer])] = {
      val cbBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
      val cb: Either[E, A] => Unit = ea => cbBox.set(Some(ea))
      val (_, fsAfterReg) = executeRegister(cb)
      cbBox.get match {
        case Some(result) => done((result, fsAfterReg))
        case None =>
          AsyncScheduler.get match {
            case Some(_) =>
              handleAsyncSuspend(cbBox, fsAfterReg)
            case None =>
              val ex = new IllegalStateException(
                "Eru.suspend: asynchronous registration is not supported in the synchronous kernel; the register function must invoke the callback synchronously."
              )
              drainFinalizers(fsAfterReg).result
              throw ex
          }
      }
    }

    /** Handle truly asynchronous suspension when scheduler is available */
    private def handleAsyncSuspend[E, A](
      cbBox: java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]],
      fsAfterReg: List[Finalizer]
    ): TailRec[(Either[E, A], List[Finalizer])] = {
      def waitForCallback(): TailRec[(Either[E, A], List[Finalizer])] = {
        cbBox.get match {
          case Some(result) => done((result, fsAfterReg))
          case None =>
            Thread.`yield`()
            tailcall(waitForCallback())
        }
      }
      waitForCallback()
    }

    private def runWithObsStack[E, A](
      eru: Eru[E, A],
      scope: ScopeId,
      observer: EruObserver,
      fins: List[Finalizer]
    ): TailRec[(Either[E, A], List[Finalizer])] = {
      val outstandingFibers = collection.mutable.Set.empty[EruFiber[?, ?]]
      tailcall(runFiberLoop(eru, fins, new Hooks.ObserverHooks(scope, observer), None, outstandingFibers))
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
            case e =>
              observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.TypedFailure(e)))
          }
          handleRunResult(either)
        case Right(value) =>
          observer.onEvent(EruEvent.ProgramEnd(scope, Outcome.Success))
          value
      }
    }

    /** Fiber-aware synchronous interpreter using eager evaluation with auto-join */
    def runSyncWithFibers[E, A](start: Eru[E, A]): A = {
      initializeAsyncSchedulerIfNeeded()

      val outstandingFibers = collection.mutable.Set.empty[EruFiber[?, ?]]
      val (either, fins) = runFiberLoop(start, Nil, Hooks.Noop, None, outstandingFibers).result

      val allFinalizers = outstandingFibers.foldLeft(fins) { (acc, fiber) =>
        fiber.finalizers ++ acc
      }

      drainFinalizers(allFinalizers).result
      handleRunResult(either)
    }

    /** Initialize async scheduler if it's available in the runtime */
    private def initializeAsyncSchedulerIfNeeded(): Unit = {
      if (AsyncScheduler.get.isEmpty) {
        ()
      }
    }

    /** Fiber-aware observer variant using runFiberLoop with eager evaluation and auto-join */
    def runSyncWithFibersAndObserver[E, A](start: Eru[E, A], observer: EruObserver): A = {
      initializeAsyncSchedulerIfNeeded()

      val scope = ScopeId.fresh()
      val hooks = new Hooks.ObserverHooks(scope, observer)
      val outstandingFibers = collection.mutable.Set.empty[EruFiber[?, ?]]

      observer.onEvent(EruEvent.ProgramStart(scope))
      val (either, fins) = runFiberLoop(start, Nil, hooks, None, outstandingFibers).result

      val allFinalizers = outstandingFibers.foldLeft(fins) { (acc, fiber) =>
        fiber.finalizers ++ acc
      }

      drainFinalizers(allFinalizers).result

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

  }

  private[eru] object Internals {
    enum View[+E, +A] {
      case VSucceed(value: A)
      case VFail(error: E)
      case VEffect(thunk: () => Either[Throwable, A])
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
    }

    import View.*
    def view[E, A](e: Eru[E, A]): View[E, A] = e match {
      case Succeed(value) => VSucceed(value)
      case Fail(error) => VFail(error)
      case Effect(thunk) => VEffect(thunk)
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
    }

  }
}
