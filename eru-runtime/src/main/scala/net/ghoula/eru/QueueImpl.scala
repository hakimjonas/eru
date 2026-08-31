package net.ghoula.eru

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import net.ghoula.eru.prelude.*

/** Queue state for pure functional implementation. */
private[eru] final case class QueueState[A](
  elements: scala.collection.immutable.Queue[A],
  waitingTakers: scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)],
  waitingPutters: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)],
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
  *   - Pragmatic Ergonomics: Clear method names indicate suspension behavior
  *   - Guided Correctness: put/take suspend, tryPut/tryTake don't
  *   - Runtime Observability: Predictable behavior documented clearly
  */
private[eru] final class QueueImpl[A](
  stateRef: Ref[QueueState[A]],
  capacityLimit: Option[Int],
  strategy: QueueStrategy,
  runtime: EruRuntime
) extends Queue[A] {

  private val maxCapacity = capacityLimit.getOrElse(Int.MaxValue)

  /** Enqueues `a` according to the queue strategy.
    *
    * `Dropping` silently drops when full and never suspends; `Sliding` evicts the oldest element to
    * make room and never suspends; `Blocking` suspends until space frees up. The blocking
    * registration carries an abandonment flag: if the put unwinds (timeout or interruption) the
    * flag is cleared synchronously, and completion sites skip flagged entries — so even an
    * interrupt racing a concurrent taker cannot re-enqueue this element after the put resolved.
    */
  override def put(a: A): Suspending[Nothing, Unit] = new Suspending({
    strategy match {
      case QueueStrategy.Dropping =>
        tryPut(a).eru.map(_ => ())

      case QueueStrategy.Sliding =>
        stateRef.modify { state =>
          if (state.size < maxCapacity) {
            val newState = state.copy(
              elements = state.elements.enqueue(a),
              size = state.size + 1
            )
            (newState, ())
          } else {
            state.elements.dequeueOption match {
              case Some((_, tail)) =>
                val newState = state.copy(
                  elements = tail.enqueue(a)
                )
                (newState, ())
              case None =>
                (state, ())
            }
          }
        }

      case QueueStrategy.Blocking =>
        tryPut(a).eru.flatMap { success =>
          if (success) {
            Eru.unit
          } else {
            for {
              promise <- Promise.make[Nothing, Unit](using runtime)
              active = new AtomicBoolean(true)
              registered <- stateRef.modify { state =>
                if (state.size < maxCapacity) {
                  val newState = state.copy(
                    elements = state.elements.enqueue(a),
                    size = state.size + 1
                  )
                  val (finalState, takerCompleted) = wakeNextTaker(newState)
                  (finalState, Right(((), takerCompleted)))
                } else {
                  val newState = state.copy(
                    waitingPutters = state.waitingPutters.enqueue((a, promise, active))
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
                  promise.await.eru.ensure(Eru.effectTotal { active.set(false); () })
              }
            } yield result
          }
        }
    }
  })

  /** Dequeues the head, suspending when the queue is empty.
    *
    * The registered taker carries an abandonment flag cleared synchronously on unwind (timeout or
    * interruption), so a later put cannot hand an element to a taker that no longer exists.
    */
  override def take: Suspending[Nothing, A] = new Suspending({
    tryTake.eru.flatMap {
      case Some(elem) => Eru.succeed(elem)
      case None =>
        for {
          promise <- Promise.make[Nothing, A](using runtime)
          active = new AtomicBoolean(true)
          result <- stateRef.modify { state =>
            state.elements.dequeueOption match {
              case Some((head, tail)) =>
                val newState = state.copy(
                  elements = tail,
                  size = state.size - 1
                )
                val (finalState, putterCompleted) = wakeNextPutter(newState)
                (finalState, Right((head, putterCompleted)))
              case None =>
                val newState = state.copy(
                  waitingTakers = state.waitingTakers.enqueue((promise, active))
                )
                (newState, Left(promise))
            }
          }.flatMap {
            case Right((elem, Some(putter))) =>
              putter.succeed(()).eru.map(_ => elem)
            case Right((elem, None)) =>
              Eru.succeed(elem)
            case Left(promise) =>
              promise.await.eru.ensure(Eru.effectTotal { active.set(false); () })
          }
        } yield result
    }
  })

  /** Batch-enqueues `as`; under `Sliding` the queue keeps the newest `maxCapacity` elements of
    * `existing ++ batch`, evicting the oldest — incoming elements are always preferred over older
    * ones.
    */
  override def putAll(as: Seq[A]): Suspending[Nothing, Unit] = new Suspending({
    if (as.isEmpty) {
      Eru.unit
    } else {
      strategy match {
        case QueueStrategy.Dropping =>
          tryPutAll(as).eru.map(_ => ())

        case QueueStrategy.Sliding =>
          stateRef.modify { state =>
            val combined = state.elements.enqueueAll(as)
            val excess = combined.size - maxCapacity
            val newElements =
              if (excess > 0) {
                val (_, kept) = combined.splitAt(excess)
                kept
              } else combined
            (state.copy(elements = newElements, size = newElements.size), ())
          }

        case QueueStrategy.Blocking =>
          tryPutAll(as).eru.flatMap { added =>
            val remaining = as.drop(added)
            remaining.foldLeft(Eru.unit) { (acc, a) =>
              acc.flatMap(_ => put(a).eru)
            }
          }
      }
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
        val (takerOpt, remainingTakers) = peelActiveTaker(state.waitingTakers)
        takerOpt match {
          case Some(taker) =>
            val newState = state.copy(waitingTakers = remainingTakers)
            (newState, (true, Some((taker, a))))
          case None =>
            val newState = state.copy(
              elements = state.elements.enqueue(a),
              size = state.size + 1
            )
            (newState, (true, None))
        }
      } else {
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
        takersToComplete
          .foldLeft(Eru.unit: Eru[Nothing, Unit]) { case (acc, (promise, elem)) =>
            acc.flatMap(_ => promise.succeed(elem).eru.map(_ => ()))
          }
          .map(_ => added)
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
        puttersToComplete
          .foldLeft(Eru.unit: Eru[Nothing, Unit]) { case (acc, promise) =>
            acc.flatMap(_ => promise.succeed(()).eru.map(_ => ()))
          }
          .map(_ => taken)
      }
    }
  })

  /** Enqueues `a` within `timeout`, returning whether the enqueue succeeded.
    *
    * On timeout the registration is abandoned synchronously before returning `false`, so no
    * completion site can enqueue this element even while the loser's unwind is still in flight.
    */
  override def putWithin(a: A, timeout: Duration): Immediate[Throwable, Boolean] = new Immediate({
    tryPut(a).eru.flatMap { success =>
      if (success) Eru.succeed(true)
      else {
        for {
          promise <- Promise.make[Nothing, Unit](using runtime)
          active = new AtomicBoolean(true)
          registered <- stateRef.modify { state =>
            if (state.size < maxCapacity) {
              val newState = state.copy(
                elements = state.elements.enqueue(a),
                size = state.size + 1
              )
              val (finalState, takerCompleted) = wakeNextTaker(newState)
              (finalState, Right(takerCompleted))
            } else {
              val newState = state.copy(
                waitingPutters = state.waitingPutters.enqueue((a, promise, active))
              )
              (newState, Left(()))
            }
          }
          result <- registered match {
            case Right(Some((takerPromise, elem))) =>
              takerPromise.succeed(elem).eru.map(_ => true)
            case Right(None) =>
              Eru.succeed(true)
            case Left(_) =>
              runtime
                .race(promise.await.eru.map(_ => true), runtime.sleep(timeout))
                .flatMap {
                  case Left(_) => Eru.succeed(true)
                  case Right(_) =>
                    Eru.effectTotal { active.set(false); () }.map(_ => false)
                }
          }
        } yield result
      }
    }
  })

  /** Dequeues the head within `timeout`, returning `None` on timeout.
    *
    * On timeout the registration is abandoned synchronously before returning `None`, so a later put
    * cannot hand its element to this taker.
    */
  override def takeWithin(timeout: Duration): Immediate[Throwable, Option[A]] = new Immediate({
    tryTake.eru.flatMap {
      case some @ Some(_) => Eru.succeed(some)
      case None =>
        for {
          promise <- Promise.make[Nothing, A](using runtime)
          active = new AtomicBoolean(true)
          result <- stateRef.modify { state =>
            state.elements.dequeueOption match {
              case Some((head, tail)) =>
                val newState = state.copy(
                  elements = tail,
                  size = state.size - 1
                )
                val (finalState, putterCompleted) = wakeNextPutter(newState)
                (finalState, Right((Some(head), putterCompleted)))
              case None =>
                val newState = state.copy(
                  waitingTakers = state.waitingTakers.enqueue((promise, active))
                )
                (newState, Left(()))
            }
          }.flatMap {
            case Right((Some(elem), Some(putter))) =>
              putter.succeed(()).eru.map(_ => Some(elem))
            case Right((Some(elem), None)) =>
              Eru.succeed(Some(elem))
            case Right((None, _)) =>
              Eru.succeed(None)
            case Left(_) =>
              runtime
                .race(promise.await.eru.map(a => Some(a)), runtime.sleep(timeout).map(_ => None))
                .flatMap {
                  case Left(value) => Eru.succeed(value)
                  case Right(_) =>
                    Eru.effectTotal { active.set(false); () }.map(_ => None)
                }
          }
        } yield result
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

  /** Dequeues the first non-abandoned taker, pruning abandoned entries ahead of it. */
  @annotation.tailrec
  private def peelActiveTaker(
    takers: scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)]
  ): (Option[Promise[Nothing, A]], scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)]) =
    takers.dequeueOption match {
      case Some(((promise, active), rest)) if active.get() => (Some(promise), rest)
      case Some((_, rest)) => peelActiveTaker(rest)
      case None => (None, takers)
    }

  /** Dequeues the first non-abandoned putter, pruning abandoned entries ahead of it. */
  @annotation.tailrec
  private def peelActivePutter(
    putters: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)]
  ): (
    Option[(A, Promise[Nothing, Unit])],
    scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)]
  ) =
    putters.dequeueOption match {
      case Some(((elem, promise, active), rest)) if active.get() => (Some((elem, promise)), rest)
      case Some((_, rest)) => peelActivePutter(rest)
      case None => (None, putters)
    }

  /** Dequeues one element to the first non-abandoned taker, pruning abandoned takers ahead of it.
    *
    * With no active taker the element stays put; the pruned taker queue is still kept.
    */
  private def wakeNextTaker(state: QueueState[A]): (QueueState[A], Option[(Promise[Nothing, A], A)]) = {
    val (takerOpt, remainingTakers) = peelActiveTaker(state.waitingTakers)
    state.elements.dequeueOption match {
      case Some((elem, remainingElements)) =>
        takerOpt match {
          case Some(taker) =>
            val newState = state.copy(
              elements = remainingElements,
              waitingTakers = remainingTakers,
              size = state.size - 1
            )
            (newState, Some((taker, elem)))
          case None =>
            (state.copy(waitingTakers = remainingTakers), None)
        }
      case None =>
        (state.copy(waitingTakers = remainingTakers), None)
    }
  }

  private def wakeNextPutter(state: QueueState[A]): (QueueState[A], Option[Promise[Nothing, Unit]]) = {
    if (state.size < maxCapacity) {
      val (putterOpt, remainingPutters) = peelActivePutter(state.waitingPutters)
      putterOpt match {
        case Some((elem, promise)) =>
          val newState = state.copy(
            elements = state.elements.enqueue(elem),
            waitingPutters = remainingPutters,
            size = state.size + 1
          )
          (newState, Some(promise))
        case None =>
          (state.copy(waitingPutters = remainingPutters), None)
      }
    } else {
      (state, None)
    }
  }

  /** Gives `elements` to waiting takers in FIFO order, pruning abandoned takers (whose elements are
    * kept for later takers).
    */
  private def satisfyWaitingTakers(
    elements: List[A],
    takers: scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)]
  ): (
    List[A],
    scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)],
    List[(Promise[Nothing, A], A)]
  ) = {
    @annotation.tailrec
    def loop(
      elems: List[A],
      ts: scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)],
      acc: List[(Promise[Nothing, A], A)]
    ): (
      List[A],
      scala.collection.immutable.Queue[(Promise[Nothing, A], AtomicBoolean)],
      List[(Promise[Nothing, A], A)]
    ) = {
      (elems, ts.dequeueOption) match {
        case (Nil, _) => (Nil, ts, acc.reverse)
        case (_, None) => (elems, ts, acc.reverse)
        case (elem :: restElems, Some(((taker, active), restTakers))) =>
          if (active.get()) loop(restElems, restTakers, (taker, elem) :: acc)
          else loop(elem :: restElems, restTakers, acc)
      }
    }
    loop(elements, takers, Nil)
  }

  /** Accepts waiting putters while capacity remains, pruning abandoned putters (whose elements are
    * cancelled).
    */
  private def acceptWaitingPutters(
    elements: scala.collection.immutable.Queue[A],
    putters: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)],
    capacity: Int
  ): (
    scala.collection.immutable.Queue[A],
    scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)],
    List[Promise[Nothing, Unit]]
  ) = {
    @annotation.tailrec
    def loop(
      elems: scala.collection.immutable.Queue[A],
      ps: scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)],
      acc: List[Promise[Nothing, Unit]]
    ): (
      scala.collection.immutable.Queue[A],
      scala.collection.immutable.Queue[(A, Promise[Nothing, Unit], AtomicBoolean)],
      List[Promise[Nothing, Unit]]
    ) = {
      if (elems.size >= capacity) {
        (elems, ps, acc.reverse)
      } else {
        ps.dequeueOption match {
          case None => (elems, ps, acc.reverse)
          case Some(((elem, promise, active), restPutters)) =>
            if (active.get()) loop(elems.enqueue(elem), restPutters, promise :: acc)
            else loop(elems, restPutters, acc)
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
