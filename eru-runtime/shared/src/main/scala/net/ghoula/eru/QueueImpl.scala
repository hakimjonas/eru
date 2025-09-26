package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Queue state for pure functional implementation. */
private[eru] final case class QueueState[A](
  elements: List[A],
  waitingTakers: List[Promise[Nothing, A]],
  waitingPutters: List[(A, Promise[Nothing, Unit])],
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

  // Strategy will be used when implementing dropping/sliding behavior
  private val _ = strategy

  override def put(a: A): Suspending[Nothing, Unit] = new Suspending({
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
                elements = state.elements :+ a,
                size = state.size + 1
              )
              val (finalState, takerCompleted) = wakeNextTaker(newState)
              (finalState, Right(((), takerCompleted)))
            } else {
              // Register as waiting putter
              val newState = state.copy(
                waitingPutters = state.waitingPutters :+ ((a, promise))
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
  })

  override def take: Suspending[Nothing, A] = new Suspending({
    tryTake.eru.flatMap {
      case Some(elem) => Eru.succeed(elem)
      case None =>
        // Must suspend - queue is empty
        for {
          promise <- Promise.make[Nothing, A](using runtime)
          result <- stateRef.modify { state =>
            state.elements match {
              case head :: tail =>
                // Element became available during registration
                val newState = state.copy(
                  elements = tail,
                  size = state.size - 1
                )
                val (finalState, putterCompleted) = wakeNextPutter(newState)
                (finalState, Right((head, putterCompleted)))
              case Nil =>
                // Register as waiting taker
                val newState = state.copy(
                  waitingTakers = state.waitingTakers :+ promise
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
        state.waitingTakers match {
          case taker :: remainingTakers =>
            // Give directly to waiting taker
            val newState = state.copy(waitingTakers = remainingTakers)
            (newState, Some((taker, a)))
          case Nil =>
            // Add to queue
            val newState = state.copy(
              elements = state.elements :+ a,
              size = state.size + 1
            )
            (newState, None)
        }
      } else {
        // Queue is full
        (state, None)
      }
    }.flatMap {
      case Some((promise, elem)) =>
        promise.succeed(elem).eru.map(_ => true)
      case None =>
        stateRef.get.map(_.elements.contains(a))
    }
  })

  override def tryTake: Immediate[Nothing, Option[A]] = new Immediate({
    stateRef.modify { state =>
      state.elements match {
        case head :: tail =>
          val newState = state.copy(
            elements = tail,
            size = state.size - 1
          )
          val (finalState, putterCompleted) = wakeNextPutter(newState)
          (finalState, (Some(head), putterCompleted))
        case Nil =>
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
        elements = state.elements ++ remainingElements,
        waitingTakers = remainingTakers,
        size = state.size + toAdd.size
      )

      (newState, (toAdd.size, takersToComplete))
    }.flatMap { case (added, takersToComplete) =>
      Eru
        .traverse(takersToComplete) { case (promise, elem) =>
          promise.succeed(elem).eru
        }
        .map(_ => added)
    }
  })

  override def tryTakeUpTo(n: Int): Immediate[Nothing, List[A]] = new Immediate({
    stateRef.modify { state =>
      val toTake = state.elements.take(n)
      val remaining = state.elements.drop(n)

      val (newElements, remainingPutters, puttersToComplete) =
        acceptWaitingPutters(remaining, state.waitingPutters, maxCapacity)

      val newState = state.copy(
        elements = newElements,
        waitingPutters = remainingPutters,
        size = newElements.size
      )

      (newState, (toTake, puttersToComplete))
    }.flatMap { case (taken, puttersToComplete) =>
      Eru.traverse(puttersToComplete)(_.succeed(()).eru).map(_ => taken)
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
    state.elements match {
      case head :: tail if state.waitingTakers.nonEmpty =>
        val taker :: remainingTakers = state.waitingTakers: @unchecked
        val newState = state.copy(
          elements = tail,
          waitingTakers = remainingTakers,
          size = state.size - 1
        )
        (newState, Some((taker, head)))
      case _ =>
        (state, None)
    }
  }

  private def wakeNextPutter(state: QueueState[A]): (QueueState[A], Option[Promise[Nothing, Unit]]) = {
    if (state.size < maxCapacity && state.waitingPutters.nonEmpty) {
      val (elem, promise) :: remainingPutters = state.waitingPutters: @unchecked
      val newState = state.copy(
        elements = state.elements :+ elem,
        waitingPutters = remainingPutters,
        size = state.size + 1
      )
      (newState, Some(promise))
    } else {
      (state, None)
    }
  }

  private def satisfyWaitingTakers(
    elements: List[A],
    takers: List[Promise[Nothing, A]]
  ): (List[A], List[Promise[Nothing, A]], List[(Promise[Nothing, A], A)]) = {
    (elements, takers) match {
      case (Nil, _) => (Nil, takers, Nil)
      case (_, Nil) => (elements, Nil, Nil)
      case (elem :: restElems, taker :: restTakers) =>
        val (finalElems, finalTakers, toComplete) = satisfyWaitingTakers(restElems, restTakers)
        (finalElems, finalTakers, (taker, elem) :: toComplete)
    }
  }

  private def acceptWaitingPutters(
    elements: List[A],
    putters: List[(A, Promise[Nothing, Unit])],
    capacity: Int
  ): (List[A], List[(A, Promise[Nothing, Unit])], List[Promise[Nothing, Unit]]) = {
    if (elements.size >= capacity || putters.isEmpty) {
      (elements, putters, Nil)
    } else {
      val (elem, promise) :: restPutters = putters: @unchecked
      val (finalElements, finalPutters, toComplete) =
        acceptWaitingPutters(elements :+ elem, restPutters, capacity)
      (finalElements, finalPutters, promise :: toComplete)
    }
  }
}

/** Factory methods for Queue. */
private[eru] object QueueImpl {
  def bounded[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(QueueState[A](Nil, Nil, Nil, 0))
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Blocking, runtime)
  }

  def unbounded[A](runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    for {
      initialState <- Eru.succeed(QueueState[A](Nil, Nil, Nil, 0))
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, None, QueueStrategy.Blocking, runtime)
  }

  def dropping[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(QueueState[A](Nil, Nil, Nil, 0))
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Dropping, runtime)
  }

  def sliding[A](capacity: Int, runtime: EruRuntime): Eru[Nothing, Queue[A]] = {
    require(capacity > 0, "Capacity must be positive")
    for {
      initialState <- Eru.succeed(QueueState[A](Nil, Nil, Nil, 0))
      stateRef <- Ref.make(initialState)
    } yield new QueueImpl[A](stateRef, Some(capacity), QueueStrategy.Sliding, runtime)
  }
}
