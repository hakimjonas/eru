package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A single-assignment asynchronous variable that can complete with either success or failure.
  *
  * A `Promise[E, A]` starts empty and can be completed exactly once with either a success value of
  * type `A` or a failure value of type `E`. Once completed, all waiters are notified and subsequent
  * await calls return immediately with the completed result.
  *
  * Unlike `Deferred[A]` which only handles successful completion, `Promise[E, A]` provides full
  * typed error handling capabilities, making it suitable for coordination patterns where failures
  * need to be propagated between fibers.
  */
trait Promise[E, A] {

  /** Completes this `Promise` with a success value if it has not been completed yet.
    *
    * @param value
    *   the success value to complete the promise with
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def succeed(value: A): Eru[Nothing, Boolean]

  /** Completes this `Promise` with a failure value if it has not been completed yet.
    *
    * @param error
    *   the failure value to complete the promise with
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def fail(error: E): Eru[Nothing, Boolean]

  /** Completes this `Promise` with the result of another effect if it has not been completed yet.
    *
    * @param effect
    *   the effect whose result will be used to complete the promise
    * @return
    *   an effect that yields `true` if this invocation completed the promise, or `false` if it was
    *   already completed
    */
  def complete(effect: Eru[E, A]): Eru[Nothing, Boolean] =
    effect.attempt.flatMap {
      case Result.Success(value) => succeed(value)
      case Result.Failure(error) => fail(error)
    }

  /** Awaits completion, returning the result when available.
    *
    * This operation suspends until the promise is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   an effect that yields the completed result (success or failure)
    */
  def await: Eru[E, A]

  /** Checks whether this promise has been completed.
    *
    * @return
    *   an effect that yields `true` if the promise is completed, `false` otherwise
    */
  def isDone: Eru[Nothing, Boolean]

  /** Attempts to retrieve the current result without suspending.
    *
    * @return
    *   an effect that yields `Some(result)` if completed, or `None` if still pending
    */
  def poll: Eru[Nothing, Option[Exit[E, A]]]
}

object Promise {

  /** Creates a new, empty `Promise[E, A]`.
    *
    * @tparam E
    *   the error type
    * @tparam A
    *   the success value type
    * @return
    *   an effect that yields the created promise
    */
  def make[E, A](using runtime: EruRuntime): Eru[Nothing, Promise[E, A]] =
    Eru.succeed(new RuntimePromise[E, A](runtime))

  private final class RuntimePromise[E, A](runtime: EruRuntime) extends Promise[E, A] {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicReference

    private val completionState = new AtomicReference[Option[Exit[E, A]]](None)
    private val pendingCallbacks = new ConcurrentLinkedQueue[Either[E, A] => Unit]()

    /** Pure function to notify all waiters of completion, expressed as an effect. */
    private def notifyAllWaiters(exit: Exit[E, A]): Eru[Nothing, Unit] = {
      @annotation.tailrec
      def drainWaiters(acc: List[Either[E, A] => Unit]): List[Either[E, A] => Unit] = {
        Option(pendingCallbacks.poll()) match {
          case Some(waiter) => drainWaiters(waiter :: acc)
          case None => acc
        }
      }

      Eru.effect {
        val waitersToNotify = drainWaiters(Nil)
        val result = exit match {
          case Exit.Success(value) => Right(value)
          case Exit.Failure(error) => Left(error)
          case Exit.Die(_) | Exit.Interrupt(_, _) =>
            throw new IllegalStateException("Promise completed with unexpected exit type")
        }
        waitersToNotify.foreach(_(result))
      }.attempt.map(_ => ())
    }

    private def attemptComplete(exit: Exit[E, A]): Eru[Nothing, Boolean] = {
      val attemptCompletion = Eru.effect(completionState.compareAndSet(None, Some(exit))).attempt.map {
        case Result.Success(result) => result
        case Result.Failure(_) => false
      }

      attemptCompletion.flatMap { wasCompleted =>
        if (wasCompleted) notifyAllWaiters(exit).map(_ => true)
        else Eru.succeed(false)
      }
    }

    def succeed(value: A): Eru[Nothing, Boolean] =
      attemptComplete(Exit.Success(value))

    def fail(error: E): Eru[Nothing, Boolean] =
      attemptComplete(Exit.Failure(error))

    def isDone: Eru[Nothing, Boolean] =
      Eru.succeed(completionState.get().isDefined)

    def poll: Eru[Nothing, Option[Exit[E, A]]] =
      Eru.succeed(completionState.get())

    /** Pure function to register a callback with proper race condition handling. */
    private def safeRegisterCallback(callback: Either[E, A] => Unit): Eru[Nothing, Unit] = {
      def checkAndRegister: Eru[Nothing, Unit] =
        Eru.succeed(completionState.get()).flatMap {
          case Some(exit) =>
            val result = exit match {
              case Exit.Success(value) => Right(value)
              case Exit.Failure(error) => Left(error)
              case Exit.Die(_) | Exit.Interrupt(_, _) =>
                // This shouldn't happen - convert to defect
                throw new IllegalStateException("Promise completed with unexpected exit type")
            }
            Eru.effect(callback(result)).attempt.map(_ => ())
          case None =>
            // Not completed - register callback and double-check
            val registerEffect = Eru.effect(pendingCallbacks.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(completionState.get()).flatMap {
              case Some(exit) =>
                // Race condition: completed after registration
                Eru.effect {
                  if (pendingCallbacks.remove(callback)) {
                    val result = exit match {
                      case Exit.Success(value) => Right(value)
                      case Exit.Failure(error) => Left(error)
                      case Exit.Die(_) | Exit.Interrupt(_, _) =>
                        throw new IllegalStateException("Promise completed with unexpected exit type")
                    }
                    callback(result)
                  }
                  // If remove failed, callback will be invoked by completer
                }.attempt.map(_ => ())
              case None =>
                // Still pending - callback will be invoked by completer
                Eru.unit
            }
            registerEffect.flatMap(_ => doubleCheck)
        }

      checkAndRegister
    }

    def await: Eru[E, A] =
      Eru.succeed(completionState.get()).flatMap {
        case Some(exit) =>
          // Already completed - return immediately without suspension
          exit match {
            case Exit.Success(value) => Eru.succeed(value)
            case Exit.Failure(error) => Eru.fail(error)
            case Exit.Die(_) | Exit.Interrupt(_, _) =>
              // This should never happen in normal Promise usage
              throw new IllegalStateException("Promise completed with unexpected exit type")
          }
        case None =>
          // Not completed - suspend using runtime's async boundary support
          // Use the same error conversion pattern as Queue for consistency
          runtime
            .suspend[Nothing, Either[E, A]](callback => safeRegisterCallback(result => callback(Right(result))))
            .attempt
            .flatMap {
              case Result.Success(either) =>
                either match {
                  case Left(error) => Eru.fail(error)
                  case Right(value) => Eru.succeed(value)
                }
              case Result.Failure(_) =>
                // This should never happen in a correctly implemented Promise
                throw new IllegalStateException("Promise await encountered unexpected error")
            }
      }
  }
}
