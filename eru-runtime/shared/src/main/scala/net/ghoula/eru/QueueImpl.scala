package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Queue state for pure functional implementation. */
private[eru] final case class QueueState[A](
  elements: scala.collection.immutable.Queue[A],
  waitingTakers: scala.collection.immutable.Queue[Promise[Nothing, A]],
  waitingPutters: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit])],
  size: Int
)

/** Queue strategies. */
private[eru] enum QueueStrategy {
  case Blocking
  case Dropping
  case Sliding
}

/** Pure functional queue implementation built on Eru primitives.
  *
  * This implementation follows Eru's Four Pillars:
  *   - Foundational Correctness: Built purely on Ref and Promise
  *   - Radical Ergonomics: Clear method names indicate suspension behavior
  *   - Guided Correctness: put/take suspend, tryPut/tryTake don't
  *   - Transparent Runtime: Predictable behavior documented clearly
  */
private[eru] final class QueueImpl[A](
  stateRef: Ref[QueueState[A]],
  capacityLimit: Option[Int],
  strategy: QueueStrategy,
  runtime: EruRuntime
) extends Queue[A] {

  private val maxCapacity = capacityLimit.getOrElse(Int.MaxValue)

  override def put(a: A): Suspending[Nothing, Unit] = new Suspending({
    strategy match {
      case QueueStrategy.Dropping =>
        // Dropping strategy: silently drop if full, never suspend
        tryPut(a).eru.map(_ => ())

      case QueueStrategy.Sliding =>
        // Sliding strategy: remove oldest to make room, never suspend
        stateRef.modify { state =>
          if (state.size < maxCapacity) {
            // Has space, just add
            val newState = state.copy(
              elements = state.elements.enqueue(a),
              size = state.size + 1
            )
            (newState, ())
          } else {
            // Full - drop oldest element and add new one
            state.elements.dequeueOption match {
              case Some((_, tail)) =>
                val newState = state.copy(
                  elements = tail.enqueue(a)
                  // size stays the same
                )
                (newState, ())
              case None =>
                // Shouldn't happen but handle gracefully
                (state, ())
            }
          }
        }

      case QueueStrategy.Blocking =>
        // Blocking strategy: suspend if full (original behavior)
        tryPut(a).eru.flatMap { success =>
          if (success) {
            Eru.unit
          } else {
            // Must suspend - queue is full
            for {
              promise <- Promise.make[Nothing, Unit](using runtime)
              registered <- stateRef.modify { state =>
                if (state.size < maxCapacity) {
                  // Space became available during registration
                  val newState = state.copy(
                    elements = state.elements.enqueue(a),
                    size = state.size + 1
                  )
                  val (finalState, takerCompleted) = wakeNextTaker(newState)
                  (finalState, Right(((), takerCompleted)))
                } else {
                  // Register as waiting putter
                  val newState = state.copy(
                    waitingPutters = state.waitingPutters.enqueue((a, promise))
                  )
                  (newState, Left(promise))
                }
              }
              result <- registered match {
                case Right((unit, Some((takerPromise, elem)))) =>
                  takerPromise.succeed(elem).eru.map(_ => unit)
                case Right((unit, None)) =>
                  Eru.succeed(unit)
                case Left(promise) =>
                  promise.await.eru
              }
            } yield result
          }
        }
    }
  })

  override def take: Suspending[Nothing, A] = new Suspending({
    tryTake.eru.flatMap {
      case Some(elem) => Eru.succeed(elem)
      case None =>
        // Must suspend - queue is empty
        for {
          promise <- Promise.make[Nothing, A](using runtime)
          result <- stateRef.modify { state =>
            state.elements.dequeueOption match {
              case Some((head, tail)) =>
                // Element became available during registration
                val newState = state.copy(
                  elements = tail,
                  size = state.size - 1
                )
                val (finalState, putterCompleted) = wakeNextPutter(newState)
                (finalState, Right((head, putterCompleted)))
              case None =>
                // Register as waiting taker
                val newState = state.copy(
                  waitingTakers = state.waitingTakers.enqueue(promise)
                )
                (newState, Left(promise))
            }
          }.flatMap {
            case Right((elem, Some(putter))) =>
              putter.succeed(()).eru.map(_ => elem)
            case Right((elem, None)) =>
              Eru.succeed(elem)
            case Left(promise) =>
              promise.await.eru
          }
        } yield result
    }
  })

  override def putAll(as: Seq[A]): Suspending[Nothing, Unit] = new Suspending({
    as.foldLeft(Eru.unit) { (acc, a) =>
      acc.flatMap(_ => put(a).eru)
    }
  })

  override def takeUpTo(n: Int): Suspending[Nothing, List[A]] = new Suspending({
    def loop(remaining: Int, acc: List[A]): Eru[Nothing, List[A]] = {
      if (remaining <= 0) {
        Eru.succeed(acc.reverse)
      } else if (acc.nonEmpty) {
        tryTake.eru.flatMap {
          case Some(a) => loop(remaining - 1, a :: acc)
          case None => Eru.succeed(acc.reverse)
        }
      } else {
        take.eru.flatMap(a => loop(remaining - 1, a :: acc))
      }
    }
    loop(n, Nil)
  })

  override def tryPut(a: A): Immediate[Nothing, Boolean] = new Immediate({
    stateRef.modify { state =>
      if (state.size < maxCapacity) {
        state.waitingTakers.dequeueOption match {
          case Some((taker, remainingTakers)) =>
            // Give directly to waiting taker
            val newState = state.copy(waitingTakers = remainingTakers)
            (newState, (true, Some((taker, a))))
          case None =>
            // Add to queue
            val newState = state.copy(
              elements = state.elements.enqueue(a),
              size = state.size + 1
            )
            (newState, (true, None))
        }
      } else {
        // Queue is full
        (state, (false, None))
      }
    }.flatMap { case (success, promiseToComplete) =>
      promiseToComplete match {
        case Some((promise, elem)) =>
          promise.succeed(elem).eru.map(_ => success)
        case None =>
          Eru.succeed(success)
      }
    }
  })

  override def tryTake: Immediate[Nothing, Option[A]] = new Immediate({
    stateRef.modify { state =>
      state.elements.dequeueOption match {
        case Some((head, tail)) =>
          val newState = state.copy(
            elements = tail,
            size = state.size - 1
          )
          val (finalState, putterCompleted) = wakeNextPutter(newState)
          (finalState, (Some(head), putterCompleted))
        case None =>
          (state, (None, None))
      }
    }.flatMap { case (result, putterToComplete) =>
      putterToComplete match {
        case Some(promise) => promise.succeed(()).eru.map(_ => result)
        case None => Eru.succeed(result)
      }
    }
  })

  override def tryPutAll(as: Seq[A]): Immediate[Nothing, Int] = new Immediate({
    stateRef.modify { state =>
      val available = maxCapacity - state.size
      val toAdd = as.take(available)

      val (remainingElements, remainingTakers, takersToComplete) =
        satisfyWaitingTakers(toAdd.toList, state.waitingTakers)

      val newState = state.copy(
        elements = state.elements.enqueueAll(remainingElements),
        waitingTakers = remainingTakers,
        size = state.size + toAdd.size
      )

      (newState, (toAdd.size, takersToComplete))
    }.flatMap { case (added, takersToComplete) =>
      if (takersToComplete.isEmpty) {
        Eru.succeed(added)
      } else {
        Eru.effectTotal {
          takersToComplete.foreach { case (promise, elem) =>
            promise.succeed(elem)
          }
          added
        }
      }
    }
  })

  override def tryTakeUpTo(n: Int): Immediate[Nothing, List[A]] = new Immediate({
    stateRef.modify { state =>
      val (toTake, remaining) = state.elements.splitAt(n)
      val toTakeList = toTake.toList

      val (newElements, remainingPutters, puttersToComplete) =
        acceptWaitingPutters(remaining, state.waitingPutters, maxCapacity)

      val newState = state.copy(
        elements = newElements,
        waitingPutters = remainingPutters,
        size = newElements.size
      )

      (newState, (toTakeList, puttersToComplete))
    }.flatMap { case (taken, puttersToComplete) =>
      if (puttersToComplete.isEmpty) {
        Eru.succeed(taken)
      } else {
        Eru.effectTotal {
          puttersToComplete.foreach(_.succeed(()))
          taken
        }
      }
    }
  })

  override def putWithin(a: A, timeout: Duration): Immediate[Throwable, Boolean] = new Immediate({
    tryPut(a).eru.flatMap { success =>
      if (success) {
        Eru.succeed(true)
      } else {
        val putEffect = put(a).eru.map(_ => true)
        val timeoutEffect = runtime.sleep(timeout).map(_ => false)
        runtime
          .race(putEffect, timeoutEffect)
          .map[Boolean](_.merge)
      }
    }
  })

  override def takeWithin(timeout: Duration): Immediate[Throwable, Option[A]] = new Immediate({
    tryTake.eru.flatMap {
      case some @ Some(_) => Eru.succeed(some)
      case None =>
        val takeEffect = take.eru.map(a => Some(a))
        val timeoutEffect = runtime.sleep(timeout).map(_ => None)
        runtime
          .race(takeEffect, timeoutEffect)
          .map[Option[A]](_.merge)
    }
  })

  override def putAllWithin(as: Seq[A], timeout: Duration): Immediate[Throwable, Int] = new Immediate({
    val deadline = System.nanoTime() + timeout.toNanos

    def loop(remaining: Seq[A], added: Int): Eru[Throwable, Int] = {
      if (remaining.isEmpty) {
        Eru.succeed(added)
      } else {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) {
          Eru.succeed(added)
        } else {
          val remainingTimeout = Duration.ofNanos(remainingNanos)
          putWithin(remaining.head, remainingTimeout).eru.flatMap { success =>
            if (success) loop(remaining.tail, added + 1)
            else Eru.succeed(added)
          }
        }
      }
    }

    loop(as, 0)
  })

  override def takeUpToWithin(n: Int, timeout: Duration): Immediate[Throwable, List[A]] = new Immediate({
    val deadline = System.nanoTime() + timeout.toNanos

    def loop(remaining: Int, acc: List[A]): Eru[Throwable, List[A]] = {
      if (remaining <= 0) {
        Eru.succeed(acc.reverse)
      } else {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) {
          Eru.succeed(acc.reverse)
        } else {
          val remainingTimeout = Duration.ofNanos(remainingNanos)
          takeWithin(remainingTimeout).eru.flatMap {
            case Some(elem) => loop(remaining - 1, elem :: acc)
            case None => Eru.succeed(acc.reverse)
          }
        }
      }
    }

    loop(n, Nil)
  })

  override def size: Immediate[Nothing, Int] =
    new Immediate(stateRef.get.map(_.size))

  override def isEmpty: Immediate[Nothing, Boolean] =
    new Immediate(stateRef.get.map(_.elements.isEmpty))

  override def isFull: Immediate[Nothing, Boolean] =
    new Immediate(stateRef.get.map(state => capacityLimit.exists(state.size >= _)))

  override def remainingCapacity: Immediate[Nothing, Int] =
    new Immediate(stateRef.get.map(state => maxCapacity - state.size))

  override def peek: Immediate[Nothing, Option[A]] =
    new Immediate(stateRef.get.map(_.elements.headOption))

  override def capacity: Immediate[Nothing, Option[Int]] =
    new Immediate(Eru.succeed(capacityLimit))

  // Helper methods

  private def wakeNextTaker(state: QueueState[A]): (QueueState[A], Option[(Promise[Nothing, A], A)]) = {
    (state.elements.dequeueOption, state.waitingTakers.dequeueOption) match {
      case (Some((elem, remainingElements)), Some((taker, remainingTakers))) =>
        val newState = state.copy(
          elements = remainingElements,
          waitingTakers = remainingTakers,
          size = state.size - 1
        )
        (newState, Some((taker, elem)))
      case _ =>
        (state, None)
    }
  }

  private def wakeNextPutter(state: QueueState[A]): (QueueState[A], Option[Promise[Nothing, Unit]]) = {
    if (state.size < maxCapacity) {
      state.waitingPutters.dequeueOption match {
        case Some(((elem, promise), remainingPutters)) =>
          val newState = state.copy(
            elements = state.elements.enqueue(elem),
            waitingPutters = remainingPutters,
            size = state.size + 1
          )
          (newState, Some(promise))
        case None =>
          (state, None)
      }
    } else {
      (state, None)
    }
  }

  private def satisfyWaitingTakers(
    elements: List[A],
    takers: scala.collection.immutable.Queue[Promise[Nothing, A]]
  ): (List[A], scala.collection.immutable.Queue[Promise[Nothing, A]], List[(Promise[Nothing, A], A)]) = {
    @annotation.tailrec
    def loop(
      elems: List[A],
      ts: scala.collection.immutable.Queue[Promise[Nothing, A]],
      acc: List[(Promise[Nothing, A], A)]
    ): (List[A], scala.collection.immutable.Queue[Promise[Nothing, A]], List[(Promise[Nothing, A], A)]) = {
      (elems, ts.dequeueOption) match {
        case (Nil, _) => (Nil, ts, acc.reverse)
        case (_, None) => (elems, ts, acc.reverse)
        case (elem :: restElems, Some((taker, restTakers))) =>
          loop(restElems, restTakers, (taker, elem) :: acc)
      }
    }
    loop(elements, takers, Nil)
  }

  private def acceptWaitingPutters(
    elements: scala.collection.immutable.Queue[A],
    putters: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit])],
    capacity: Int
  ): (
    scala.collection.immutable.Queue[A],
    scala.collection.immutable.Queue[(A, Promise[Nothing, Unit])],
    List[Promise[Nothing, Unit]]
  ) = {
    @annotation.tailrec
    def loop(
      elems: scala.collection.immutable.Queue[A],
      ps: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit])],
      acc: List[Promise[Nothing, Unit]]
    ): (
      scala.collection.immutable.Queue[A],
      scala.collection.immutable.Queue[(A, Promise[Nothing, Unit])],
      List[Promise[Nothing, Unit]]
    ) = {
      if (elems.size >= capacity) {
        (elems, ps, acc.reverse)
      } else {
        ps.dequeueOption match {
          case None => (elems, ps, acc.reverse)
          case Some(((elem, promise), restPutters)) =>
            loop(elems.enqueue(elem), restPutters, promise :: acc)
        }
      }
    }
    loop(elements, putters, Nil)
  }
}

/** Factory methods for Queue. */
private[eru] object QueueImpl {
  def bounded[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(
        QueueState[A](
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          0
        )
      )
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Blocking, runtime)
  }

  def unbounded[A](runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    for {
      initialState <- Eru.succeed(
        QueueState[A](
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          0
        )
      )
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, None, QueueStrategy.Blocking, runtime)
  }

  def dropping[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(
        QueueState[A](
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          0
        )
      )
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Dropping, runtime)
  }

  def sliding[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(
        QueueState[A](
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          scala.collection.immutable.Queue.empty,
          0
        )
      )
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Sliding, runtime)
  }
}
