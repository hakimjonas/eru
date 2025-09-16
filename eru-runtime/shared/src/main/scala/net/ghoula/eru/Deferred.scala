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

    private val state = new AtomicReference[Option[A]](None)
    private val waiters = new ConcurrentLinkedQueue[Either[Nothing, A] => Unit]()

    def complete(a: A): Eru[Nothing, Boolean] = {
      if (state.get().isDefined) {
        Eru.succeed(false)
      } else {
        Eru.effect {
          if (state.compareAndSet(None, Some(a))) {
            val waitersToNotify = {
              @annotation.tailrec
              def drainWaiters(acc: List[Either[Nothing, A] => Unit]): List[Either[Nothing, A] => Unit] = {
                Option(waiters.poll()) match {
                  case Some(waiter) => drainWaiters(waiter :: acc)
                  case None => acc
                }
              }
              drainWaiters(Nil)
            }
            waitersToNotify.foreach { callback =>
              try callback(Right(a))
              catch { case _: Throwable => () }
            }
            true
          } else {
            false
          }
        }.attempt.map {
          case Result.Success(result) => result
          case Result.Failure(_) => false
        }
      }
    }

    /** Pure function to register a callback with proper race condition handling. */
    private def safeRegisterCallback(callback: Either[Nothing, A] => Unit): Eru[Nothing, Unit] = {
      def checkAndRegister: Eru[Nothing, Unit] =
        Eru.succeed(state.get()).flatMap {
          case Some(value) =>
            Eru.effect(callback(Right(value))).attempt.map(_ => ())
          case None =>
            val registerEffect = Eru.effect(waiters.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(state.get()).flatMap {
              case Some(value) =>
                Eru.effect {
                  if (waiters.remove(callback)) {
                    callback(Right(value))
                  }
                }.attempt.map(_ => ())
              case None =>
                Eru.unit
            }
            registerEffect.flatMap(_ => doubleCheck)
        }

      checkAndRegister
    }

    def await: Eru[Nothing, A] =
      state.get() match {
        case Some(value) =>
          Eru.succeed(value)
        case None =>
          runtime.suspend[Nothing, A](safeRegisterCallback).attempt.map {
            case Result.Success(value) => value
            case Result.Failure(throwable) =>
              throw new IllegalStateException("Deferred await encountered unexpected error", throwable)
          }
      }
  }
}
