package net.ghoula.eru

import java.time.Duration

/** Pure functional concurrent queue.
  *
  * This queue implementation is built entirely on Eru primitives (Ref and Promise), demonstrating
  * true compositional concurrency without any Java concurrent utilities.
  *
  * The design follows Eru's Four Pillars:
  *   - Foundational Correctness: Built purely on Eru primitives without Java utilities
  *   - Radical Ergonomics: Clear method names and comprehensive API coverage
  *   - Guided Correctness: Clear naming conventions indicate suspension behavior
  *   - Transparent Runtime: Predictable suspension behavior through documentation
  *
  * @tparam A
  *   the type of elements in the queue
  */
trait Queue[A] {

  /** Adds an element to the queue, suspending if full.
    *
    * This operation will suspend indefinitely if the queue is at capacity, waiting until space
    * becomes available. The Suspending type ensures this cannot be called with unsafeRunSync.
    *
    * @param a
    *   the element to add
    * @return
    *   a suspending effect that completes when the element is added
    */
  def put(a: A): Suspending[Nothing, Unit]

  /** Removes and returns an element, suspending if empty.
    *
    * This operation will suspend indefinitely if the queue is empty, waiting until an element
    * becomes available. The Suspending type prevents deadlocks at compile-time.
    *
    * @return
    *   a suspending effect that yields the next element
    */
  def take: Suspending[Nothing, A]

  /** Adds multiple elements, suspending if insufficient space.
    *
    * @param as
    *   the elements to add
    * @return
    *   a suspending effect that completes when all elements are added
    */
  def putAll(as: Seq[A]): Suspending[Nothing, Unit]

  /** Removes up to n elements, suspending until at least one is available.
    *
    * @param n
    *   the maximum number of elements to take
    * @return
    *   a suspending effect that yields the taken elements (at least 1, up to n)
    */
  def takeUpTo(n: Int): Suspending[Nothing, List[A]]

  /** Attempts to add an element without blocking.
    *
    * @param a
    *   the element to add
    * @return
    *   true if the element was added, false if the queue was full
    */
  def tryPut(a: A): Immediate[Nothing, Boolean]

  /** Attempts to remove an element without blocking.
    *
    * @return
    *   Some(element) if available, None if the queue was empty
    */
  def tryTake: Immediate[Nothing, Option[A]]

  /** Alias for tryTake for backward compatibility.
    *
    * @return
    *   Some(element) if available, None if the queue was empty
    */
  def poll: Immediate[Nothing, Option[A]] = tryTake

  /** Attempts to add multiple elements without blocking.
    *
    * @param as
    *   the elements to add
    * @return
    *   the number of elements successfully added
    */
  def tryPutAll(as: Seq[A]): Immediate[Nothing, Int]

  /** Attempts to remove up to n elements without blocking.
    *
    * @param n
    *   the maximum number of elements to take
    * @return
    *   the list of elements taken (may be empty)
    */
  def tryTakeUpTo(n: Int): Immediate[Nothing, List[A]]

  /** Attempts to add an element within the timeout period.
    *
    * @param a
    *   the element to add
    * @param timeout
    *   maximum time to wait
    * @return
    *   true if successful, false if timeout expired
    */
  def putWithin(a: A, timeout: Duration): Immediate[Throwable, Boolean]

  /** Attempts to remove an element within the timeout period.
    *
    * @param timeout
    *   maximum time to wait
    * @return
    *   Some(element) if available within timeout, None otherwise
    */
  def takeWithin(timeout: Duration): Immediate[Throwable, Option[A]]

  /** Attempts to add multiple elements within the timeout period.
    *
    * @param as
    *   the elements to add
    * @param timeout
    *   maximum time to wait
    * @return
    *   the number of elements successfully added before timeout
    */
  def putAllWithin(as: Seq[A], timeout: Duration): Immediate[Throwable, Int]

  /** Attempts to remove up to n elements within the timeout period.
    *
    * @param n
    *   the maximum number of elements to take
    * @param timeout
    *   maximum time to wait
    * @return
    *   the list of elements taken before timeout (may be empty)
    */
  def takeUpToWithin(n: Int, timeout: Duration): Immediate[Throwable, List[A]]

  /** Returns the current number of elements in the queue.
    *
    * Note: This is a snapshot and may change immediately due to concurrent operations.
    *
    * @return
    *   the current queue size
    */
  def size: Immediate[Nothing, Int]

  /** Checks if the queue is currently empty.
    *
    * @return
    *   true if empty, false otherwise
    */
  def isEmpty: Immediate[Nothing, Boolean]

  /** Checks if the queue is currently at capacity.
    *
    * For unbounded queues, this always returns false.
    *
    * @return
    *   true if full, false otherwise
    */
  def isFull: Immediate[Nothing, Boolean]

  /** Returns the remaining capacity of the queue.
    *
    * For unbounded queues, this returns Int.MaxValue.
    *
    * @return
    *   the number of additional elements that can be added without blocking
    */
  def remainingCapacity: Immediate[Nothing, Int]

  /** Peeks at the next element without removing it.
    *
    * @return
    *   Some(element) if available, None if empty
    */
  def peek: Immediate[Nothing, Option[A]]

  /** Returns the queue's capacity limit.
    *
    * @return
    *   Some(capacity) for bounded queues, None for unbounded
    */
  def capacity: Immediate[Nothing, Option[Int]]

}

/** Companion object for Queue. */
object Queue {

  /** Creates a bounded queue with the specified capacity.
    *
    * @param capacity
    *   the maximum number of elements
    * @return
    *   an effect that yields a new bounded queue
    */
  def bounded[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
    QueueImpl.bounded[A](capacity, runtime)

  /** Creates an unbounded queue.
    *
    * @return
    *   an effect that yields a new unbounded queue
    */
  def unbounded[A](using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
    QueueImpl.unbounded[A](runtime)

  /** Creates a dropping queue that discards new elements when full.
    *
    * @param capacity
    *   the maximum number of elements
    * @return
    *   an effect that yields a new dropping queue
    */
  def dropping[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
    QueueImpl.dropping[A](capacity, runtime)

  /** Creates a sliding queue that discards old elements when full.
    *
    * @param capacity
    *   the maximum number of elements
    * @return
    *   an effect that yields a new sliding queue
    */
  def sliding[A](capacity: Int)(using runtime: EruRuntime): Eru[Nothing, Queue[A]] =
    QueueImpl.sliding[A](capacity, runtime)
}
