package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A single-assignment asynchronous variable.
  *
  * A `Deferred[A]` starts empty and can be completed exactly once with a value of type `A`. Once
  * completed, all waiters are notified and subsequent await calls return immediately.
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

  /** Awaits completion, returning the value when available.
    *
    * This operation suspends until the deferred is completed, using the runtime's async boundary
    * support for efficient, platform-appropriate blocking semantics.
    *
    * @return
    *   an effect that yields the completed value
    */
  def await: Eru[Nothing, A]
}

object Deferred {

  /** Creates a new, empty `Deferred[A]`.
    * @tparam A
    *   the value type
    * @return
    *   an effect that yields the created deferred
    */
  def make[A](using runtime: EruRuntime): Eru[Nothing, Deferred[A]] =
    Eru.succeed(new RuntimeDeferred[A](runtime))

  private final class RuntimeDeferred[A](runtime: EruRuntime) extends Deferred[A] {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicReference

    // Immutable state representation - None means pending, Some(value) means completed
    private val state = new AtomicReference[Option[A]](None)
    // Lock-free queue of waiting callbacks - maintains FP principles with controlled effects
    private val waiters = new ConcurrentLinkedQueue[Either[Nothing, A] => Unit]()

    /** Pure function to notify all waiters of completion, expressed as an effect. */
    private def notifyAllWaiters(value: A): Eru[Nothing, Unit] = {
      @annotation.tailrec
      def drainWaiters(acc: List[Either[Nothing, A] => Unit]): List[Either[Nothing, A] => Unit] = {
        Option(waiters.poll()) match {
          case Some(waiter) => drainWaiters(waiter :: acc)
          case None => acc
        }
      }

      Eru.effect {
        val waitersToNotify = drainWaiters(Nil)
        waitersToNotify.foreach(_(Right(value)))
      }.attempt.map(_ => ())
    }

    def complete(a: A): Eru[Nothing, Boolean] = {
      val attemptCompletion = Eru.effect(state.compareAndSet(None, Some(a))).attempt.map {
        case Result.Success(result) => result
        case Result.Failure(_) => false
      }

      attemptCompletion.flatMap { wasCompleted =>
        if (wasCompleted) notifyAllWaiters(a).map(_ => true)
        else Eru.succeed(false)
      }
    }

    /** Pure function to register a callback with proper race condition handling. */
    private def safeRegisterCallback(callback: Either[Nothing, A] => Unit): Eru[Nothing, Unit] = {
      def checkAndRegister: Eru[Nothing, Unit] =
        Eru.succeed(state.get()).flatMap {
          case Some(value) =>
            // Already completed - invoke callback immediately
            Eru.effect(callback(Right(value))).attempt.map(_ => ())
          case None =>
            // Not completed - register callback and double-check
            val registerEffect = Eru.effect(waiters.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(state.get()).flatMap {
              case Some(value) =>
                // Race condition: completed after registration
                Eru.effect {
                  if (waiters.remove(callback)) {
                    callback(Right(value))
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

    def await: Eru[Nothing, A] =
      Eru.succeed(state.get()).flatMap {
        case Some(value) =>
          // Already completed - return immediately without suspension
          Eru.succeed(value)
        case None =>
          // Not completed - suspend using runtime's async boundary support
          // Convert any errors to defects since Deferred await should not have typed errors
          runtime
            .suspend[Nothing, A](safeRegisterCallback)
            .attempt
            .flatMap {
              case Result.Success(value) => Eru.succeed(value)
              case Result.Failure(throwable) => Eru.effect(throw throwable)
            }
            .attempt
            .map {
              case Result.Success(value) => value
              case Result.Failure(_) =>
                // This should never happen in a correctly implemented Deferred
                throw new IllegalStateException("Deferred await encountered unexpected error")
            }
      }
  }
}
