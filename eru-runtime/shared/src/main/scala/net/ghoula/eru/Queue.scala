package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A concurrent queue for communication between fibers.
  *
  * Queues provide a way to safely pass values between concurrent fibers. They support both bounded
  * (with capacity limits) and unbounded variants.
  */
trait Queue[A] {

  /** Adds an element to the queue.
    *
    * For bounded queues, this operation will suspend if the queue is at capacity until space
    * becomes available. For unbounded queues, this operation always succeeds immediately.
    *
    * @param a
    *   the element to add to the queue
    * @return
    *   an effect that succeeds when the element has been added
    */
  def offer(a: A): Eru[Nothing, Unit]

  /** Removes and returns an element from the queue.
    *
    * This operation will suspend if the queue is empty until an element becomes available.
    *
    * @return
    *   an effect that yields an element from the queue
    */
  def take: Eru[Nothing, A]

  /** Attempts to remove and return an element from the queue without suspending.
    *
    * @return
    *   an effect that yields Some(element) if available, or None if the queue is empty
    */
  def poll: Eru[Nothing, Option[A]]

  /** Returns the current number of elements in the queue.
    *
    * Note: This is a snapshot and may change immediately after the call due to concurrent
    * operations.
    *
    * @return
    *   an effect that yields the current queue size
    */
  def size: Eru[Nothing, Int]

  /** Checks whether the queue is currently empty.
    *
    * @return
    *   an effect that yields true if the queue is empty, false otherwise
    */
  def isEmpty: Eru[Nothing, Boolean] = size.map(_ == 0)

  /** For bounded queues, returns the remaining capacity. For unbounded queues, returns
    * Int.MaxValue.
    *
    * @return
    *   an effect that yields the remaining capacity
    */
  def remainingCapacity: Eru[Nothing, Int]
}

object Queue {

  /** Creates a bounded queue with the specified capacity.
    *
    * @param capacity
    *   the maximum number of elements the queue can hold
    * @tparam A
    *   the element type
    * @return
    *   an effect that yields a new bounded queue
    */
  def bounded[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Queue capacity must be positive")
    Eru.succeed(new BoundedQueue[A](capacity, runtime))
  }

  /** Creates an unbounded queue.
    *
    * @tparam A
    *   the element type
    * @return
    *   an effect that yields a new unbounded queue
    */
  def unbounded[A](using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
    Eru.succeed(new UnboundedQueue[A](runtime))

  // Implementation for bounded queues
  private final class BoundedQueue[A](capacity: Int, runtime: EruRuntime) extends Queue[A] {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicInteger

    private val queue = new ConcurrentLinkedQueue[A]()
    private val currentSize = new AtomicInteger(0)
    private val waitingTakers = new ConcurrentLinkedQueue[Either[Nothing, A] => Unit]()
    private val waitingOfferers = new ConcurrentLinkedQueue[(A, Either[Nothing, Unit] => Unit)]()

    def offer(a: A): Eru[Nothing, Unit] = {
      def tryOffer: Eru[Nothing, Boolean] = Eru.succeed {
        val current = currentSize.get()
        if (current < capacity && currentSize.compareAndSet(current, current + 1)) {
          queue.offer(a)
          notifyWaitingTakers()
          true
        } else {
          false
        }
      }

      tryOffer.flatMap { offered =>
        if (offered) Eru.unit
        else {
          // Queue is full, need to suspend
          runtime
            .suspend[Nothing, Unit] { callback =>
              Eru.succeed {
                waitingOfferers.offer((a, callback))
                // Double-check after registering
                val current = currentSize.get()
                if (current < capacity && currentSize.compareAndSet(current, current + 1)) {
                  if (waitingOfferers.remove((a, callback))) {
                    queue.offer(a)
                    notifyWaitingTakers()
                    callback(Right(()))
                  }
                }
              }
            }
            .attempt
            .flatMap {
              case Result.Success(value) => Eru.succeed(value)
              case Result.Failure(throwable) => Eru.effect(throw throwable)
            }
            .attempt
            .map {
              case Result.Success(value) => value
              case Result.Failure(_) =>
                throw new IllegalStateException("Queue offer encountered unexpected error")
            }
        }
      }
    }

    def take: Eru[Nothing, A] = {
      def tryTake: Eru[Nothing, Option[A]] = Eru.succeed {
        Option(queue.poll()).map { element =>
          currentSize.decrementAndGet()
          notifyWaitingOfferers()
          element
        }
      }

      tryTake.flatMap {
        case Some(element) => Eru.succeed(element)
        case None =>
          // Queue is empty, need to suspend
          def safeRegisterCallback(callback: Either[Nothing, A] => Unit): Eru[Nothing, Unit] = {
            val registerEffect = Eru.effect(waitingTakers.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(Option(queue.poll())).flatMap {
              case Some(element) =>
                // Race condition: element became available after registration
                Eru.effect {
                  if (waitingTakers.remove(callback)) {
                    currentSize.decrementAndGet()
                    notifyWaitingOfferers()
                    callback(Right(element))
                  }
                  // If remove failed, callback will be invoked by offer
                }.attempt.map(_ => ())
              case None =>
                // Still empty - callback will be invoked by offer
                Eru.unit
            }
            registerEffect.flatMap(_ => doubleCheck)
          }

          runtime
            .suspend[Nothing, A](safeRegisterCallback)
            .attempt
            .map {
              case Result.Success(value) => value
              case Result.Failure(_) =>
                // Convert any defects to a runtime exception
                // This should never happen in a correctly implemented Queue
                throw new IllegalStateException("Queue take encountered unexpected error")
            }
      }
    }

    def poll: Eru[Nothing, Option[A]] = Eru.succeed {
      Option(queue.poll()).map { element =>
        currentSize.decrementAndGet()
        notifyWaitingOfferers()
        element
      }
    }

    def size: Eru[Nothing, Int] = Eru.succeed(currentSize.get())

    def remainingCapacity: Eru[Nothing, Int] = Eru.succeed(capacity - currentSize.get())

    private def notifyWaitingTakers(): Unit = {
      Option(waitingTakers.poll()).foreach { callback =>
        Option(queue.poll()) match {
          case Some(element) =>
            currentSize.decrementAndGet()
            callback(Right(element))
            notifyWaitingOfferers()
          case None =>
            waitingTakers.offer(callback) // Put it back
        }
      }
    }

    private def notifyWaitingOfferers(): Unit = {
      Option(waitingOfferers.poll()).foreach { case (element, callback) =>
        val current = currentSize.get()
        if (current < capacity && currentSize.compareAndSet(current, current + 1)) {
          queue.offer(element)
          callback(Right(()))
          notifyWaitingTakers()
        } else {
          waitingOfferers.offer((element, callback)) // Put it back
        }
      }
    }
  }

  // Implementation for unbounded queues
  private final class UnboundedQueue[A](runtime: EruRuntime) extends Queue[A] {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicInteger

    private val queue = new ConcurrentLinkedQueue[A]()
    private val currentSize = new AtomicInteger(0)
    private val waitingTakers = new ConcurrentLinkedQueue[Either[Nothing, A] => Unit]()

    def offer(a: A): Eru[Nothing, Unit] = Eru.succeed {
      queue.offer(a)
      currentSize.incrementAndGet()
      notifyWaitingTakers()
    }

    def take: Eru[Nothing, A] = {
      def tryTake: Eru[Nothing, Option[A]] = Eru.succeed {
        Option(queue.poll()).map { element =>
          currentSize.decrementAndGet()
          element
        }
      }

      tryTake.flatMap {
        case Some(element) => Eru.succeed(element)
        case None =>
          // Queue is empty, need to suspend
          def safeRegisterCallback(callback: Either[Nothing, A] => Unit): Eru[Nothing, Unit] = {
            val registerEffect = Eru.effect(waitingTakers.offer(callback)).attempt.map(_ => ())
            val doubleCheck = Eru.succeed(Option(queue.poll())).flatMap {
              case Some(element) =>
                // Race condition: element became available after registration
                Eru.effect {
                  if (waitingTakers.remove(callback)) {
                    currentSize.decrementAndGet()
                    callback(Right(element))
                  }
                  // If remove failed, callback will be invoked by offer
                }.attempt.map(_ => ())
              case None =>
                // Still empty - callback will be invoked by offer
                Eru.unit
            }
            registerEffect.flatMap(_ => doubleCheck)
          }

          runtime
            .suspend[Nothing, A](safeRegisterCallback)
            .attempt
            .map {
              case Result.Success(value) => value
              case Result.Failure(_) =>
                // Convert any defects to a runtime exception
                // This should never happen in a correctly implemented Queue
                throw new IllegalStateException("Queue take encountered unexpected error")
            }
      }
    }

    def poll: Eru[Nothing, Option[A]] = Eru.succeed {
      Option(queue.poll()).map { element =>
        currentSize.decrementAndGet()
        element
      }
    }

    def size: Eru[Nothing, Int] = Eru.succeed(currentSize.get())

    def remainingCapacity: Eru[Nothing, Int] = Eru.succeed(Int.MaxValue)

    private def notifyWaitingTakers(): Unit = {
      Option(waitingTakers.poll()).foreach { callback =>
        Option(queue.poll()) match {
          case Some(element) =>
            currentSize.decrementAndGet()
            callback(Right(element))
          case None =>
            waitingTakers.offer(callback) // Put it back
        }
      }
    }
  }
}
