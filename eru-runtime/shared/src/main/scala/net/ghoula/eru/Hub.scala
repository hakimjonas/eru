package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A concurrent hub for pub/sub messaging between fibers.
  *
  * Hubs provide a way to broadcast messages from multiple publishers to multiple subscribers. Each
  * subscriber gets their own independent message queue, ensuring no message loss due to slow
  * consumers. Hubs support both bounded (with capacity limits and backpressure) and unbounded
  * variants.
  */
trait Hub[A] {

  /** Publishes a message to all current subscribers.
    *
    * For bounded hubs, this operation will suspend if any subscriber queue is at capacity until
    * space becomes available. For unbounded hubs, this operation always succeeds immediately.
    *
    * @param message
    *   the message to publish to all subscribers
    * @return
    *   an effect that succeeds when the message has been published to all subscribers
    */
  def publish(message: A): Eru[Nothing, Unit]

  /** Creates a new subscription to this hub.
    *
    * Each subscription provides an independent queue of messages published to the hub. Subscribers
    * only receive messages published after their subscription was created.
    *
    * @return
    *   an effect that yields a queue for receiving published messages
    */
  def subscribe: Eru[Nothing, Queue[A]]

  /** Returns the current number of active subscribers.
    *
    * Note: This is a snapshot and may change immediately after the call due to concurrent
    * subscription operations.
    *
    * @return
    *   an effect that yields the current subscriber count
    */
  def subscriberCount: Eru[Nothing, Int]

  /** Checks whether the hub currently has any subscribers.
    *
    * @return
    *   an effect that yields true if there are active subscribers, false otherwise
    */
  def hasSubscribers: Eru[Nothing, Boolean] = subscriberCount.map(_ > 0)

  /** For bounded hubs, returns the capacity per subscriber queue. For unbounded hubs, returns
    * Int.MaxValue.
    *
    * @return
    *   an effect that yields the queue capacity for each subscriber
    */
  def capacity: Eru[Nothing, Int]
}

object Hub {

  /** Creates a bounded hub with the specified capacity per subscriber.
    *
    * Each subscriber queue will have the specified capacity. Publishing will block if any
    * subscriber queue is full, providing natural backpressure.
    *
    * @param capacity
    *   the maximum number of messages each subscriber queue can hold
    * @tparam A
    *   the message type
    * @return
    *   an effect that yields a new bounded hub
    */
  def bounded[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Hub[A]] = {
    require(capacity > 0, "Hub capacity must be positive")
    Eru.succeed(new BoundedHub[A](capacity, runtime))
  }

  /** Creates an unbounded hub.
    *
    * Subscriber queues can grow without limit. Publishing never blocks due to capacity.
    *
    * @tparam A
    *   the message type
    * @return
    *   an effect that yields a new unbounded hub
    */
  def unbounded[A](using runtime: EruRuntime): Eru[Nothing, Hub[A]] =
    Eru.succeed(new UnboundedHub[A](runtime))

  // Implementation for bounded hubs using pure FP patterns
  private final class BoundedHub[A](queueCapacity: Int, runtime: EruRuntime) extends Hub[A] {
    import java.util.concurrent.atomic.AtomicReference
    import scala.annotation.tailrec

    // Immutable state representation
    private case class HubState(subscribers: Map[Long, Queue[A]], nextId: Long)

    private val state = new AtomicReference(HubState(Map.empty, 0L))

    def publish(message: A): Eru[Nothing, Unit] =
      getCurrentSubscribers.flatMap { queues =>
        queues.headOption.fold(Eru.unit) { _ =>
          // Publish to all subscribers, treating individual failures gracefully
          Eru.collectAllDiscard(queues.map(_.offer(message).attempt))
        }
      }

    def subscribe: Eru[Nothing, Queue[A]] =
      for {
        queue <- Queue.bounded[A](queueCapacity)(using runtime)
        _ <- addSubscriber(queue)
      } yield queue

    def subscriberCount: Eru[Nothing, Int] =
      Eru.succeed(state.get().subscribers.size)

    def capacity: Eru[Nothing, Int] =
      Eru.succeed(queueCapacity)

    private def getCurrentSubscribers: Eru[Nothing, List[Queue[A]]] =
      Eru.succeed(state.get().subscribers.values.toList)

    private def addSubscriber(queue: Queue[A]): Eru[Nothing, Unit] = {
      @tailrec
      def tryAdd(): Unit = {
        val current = state.get()
        val newState = current.copy(
          subscribers = current.subscribers + (current.nextId -> queue),
          nextId = current.nextId + 1
        )
        if (!state.compareAndSet(current, newState)) tryAdd()
      }

      Eru.succeed(tryAdd())
    }
  }

  // Implementation for unbounded hubs using pure FP patterns
  private final class UnboundedHub[A](runtime: EruRuntime) extends Hub[A] {
    import java.util.concurrent.atomic.AtomicReference
    import scala.annotation.tailrec

    // Immutable state representation
    private case class HubState(subscribers: Map[Long, Queue[A]], nextId: Long)

    private val state = new AtomicReference(HubState(Map.empty, 0L))

    def publish(message: A): Eru[Nothing, Unit] =
      getCurrentSubscribers.flatMap { queues =>
        queues.headOption.fold(Eru.unit) { _ =>
          // Publish to all subscribers, treating individual failures gracefully
          Eru.collectAllDiscard(queues.map(_.offer(message).attempt))
        }
      }

    def subscribe: Eru[Nothing, Queue[A]] =
      for {
        queue <- Queue.unbounded[A](using runtime)
        _ <- addSubscriber(queue)
      } yield queue

    def subscriberCount: Eru[Nothing, Int] =
      Eru.succeed(state.get().subscribers.size)

    def capacity: Eru[Nothing, Int] =
      Eru.succeed(Int.MaxValue)

    private def getCurrentSubscribers: Eru[Nothing, List[Queue[A]]] =
      Eru.succeed(state.get().subscribers.values.toList)

    private def addSubscriber(queue: Queue[A]): Eru[Nothing, Unit] = {
      @tailrec
      def tryAdd(): Unit = {
        val current = state.get()
        val newState = current.copy(
          subscribers = current.subscribers + (current.nextId -> queue),
          nextId = current.nextId + 1
        )
        if (!state.compareAndSet(current, newState)) tryAdd()
      }

      Eru.succeed(tryAdd())
    }
  }
}
