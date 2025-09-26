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
    *   a suspending effect that succeeds when the message has been published to all subscribers
    */
  def publish(message: A): Suspending[Nothing, Unit]

  /** Creates a new subscription to this hub.
    *
    * Each subscription provides an independent queue of messages published to the hub. Subscribers
    * only receive messages published after their subscription was created.
    *
    * @return
    *   an immediate effect that yields a queue for receiving published messages
    */
  def subscribe: Immediate[Nothing, Queue[A]]

  /** Returns the current number of active subscribers.
    *
    * Note: This is a snapshot and may change immediately after the call due to concurrent
    * subscription operations.
    *
    * @return
    *   an immediate effect that yields the current subscriber count
    */
  def subscriberCount: Immediate[Nothing, Int]

  /** Checks whether the hub currently has any subscribers.
    *
    * @return
    *   an effect that yields true if there are active subscribers, false otherwise
    */
  def hasSubscribers: Immediate[Nothing, Boolean] = new Immediate(subscriberCount.eru.map(_ > 0))

  /** For bounded hubs, returns the capacity per subscriber queue. For unbounded hubs, returns
    * Int.MaxValue.
    *
    * @return
    *   an immediate effect that yields the queue capacity for each subscriber
    */
  def capacity: Immediate[Nothing, Int]
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
    for {
      stateRef <- Ref.make(HubState[A](Map.empty, 0L))
    } yield new BoundedHub[A](capacity, stateRef, runtime)
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
    for {
      stateRef <- Ref.make(HubState[A](Map.empty, 0L))
    } yield new UnboundedHub[A](stateRef, runtime)

  // Shared state representation for both hub types
  private case class HubState[A](subscribers: Map[Long, Queue[A]], nextId: Long)

  // Implementation for bounded hubs using pure FP patterns
  private final class BoundedHub[A](queueCapacity: Int, stateRef: Ref[HubState[A]], runtime: EruRuntime)
      extends Hub[A] {

    def publish(message: A): Suspending[Nothing, Unit] = new Suspending(
      getCurrentSubscribers.flatMap { queues =>
        queues.headOption.fold(Eru.unit) { _ =>
          // Publish to all subscribers, treating individual failures gracefully
          Eru.collectAllDiscard(queues.map(_.put(message).eru.attempt))
        }
      })

    def subscribe: Immediate[Nothing, Queue[A]] = new Immediate(
      for {
        queue <- Queue.bounded[A](queueCapacity)(using runtime)
        _ <- addSubscriber(queue)
      } yield queue)

    def subscriberCount: Immediate[Nothing, Int] =
      new Immediate(stateRef.get.map(_.subscribers.size))

    def capacity: Immediate[Nothing, Int] =
      new Immediate(Eru.succeed(queueCapacity))

    private def getCurrentSubscribers: Eru[Nothing, List[Queue[A]]] =
      stateRef.get.map(_.subscribers.values.toList)

    private def addSubscriber(queue: Queue[A]): Eru[Nothing, Unit] =
      stateRef.update { state =>
        state.copy(
          subscribers = state.subscribers + (state.nextId -> queue),
          nextId = state.nextId + 1
        )
      }.map(_ => ())
  }

  // Implementation for unbounded hubs using pure FP patterns
  private final class UnboundedHub[A](stateRef: Ref[HubState[A]], runtime: EruRuntime) extends Hub[A] {

    def publish(message: A): Suspending[Nothing, Unit] = new Suspending(
      getCurrentSubscribers.flatMap { queues =>
        queues.headOption.fold(Eru.unit) { _ =>
          // Publish to all subscribers, treating individual failures gracefully
          Eru.collectAllDiscard(queues.map(_.put(message).eru.attempt))
        }
      })

    def subscribe: Immediate[Nothing, Queue[A]] = new Immediate(
      for {
        queue <- Queue.unbounded[A](using runtime)
        _ <- addSubscriber(queue)
      } yield queue)

    def subscriberCount: Immediate[Nothing, Int] =
      new Immediate(stateRef.get.map(_.subscribers.size))

    def capacity: Immediate[Nothing, Int] =
      new Immediate(Eru.succeed(Int.MaxValue))

    private def getCurrentSubscribers: Eru[Nothing, List[Queue[A]]] =
      stateRef.get.map(_.subscribers.values.toList)

    private def addSubscriber(queue: Queue[A]): Eru[Nothing, Unit] =
      stateRef.update { state =>
        state.copy(
          subscribers = state.subscribers + (state.nextId -> queue),
          nextId = state.nextId + 1
        )
      }.map(_ => ())
  }
}
