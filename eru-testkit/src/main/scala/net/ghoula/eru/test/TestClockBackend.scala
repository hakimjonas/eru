package net.ghoula.eru.test

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeoutException}

import net.ghoula.eru.*
import net.ghoula.eru.internal.{BackendCapabilities, ConcurrencyBackend}

/** ConcurrencyBackend implementation with strictly logical timing semantics.
  *
  * Time-dependent outcomes — sleep completion, timeouts, race winners, retry delays — are decided
  * by the TestClock's logical time alone. Sleeping fibers park until the clock reaches their
  * target; there is no real-time fallback and no swallowed suspension failure. Forked fibers run on
  * the backend's dedicated virtual-thread executor purely as the execution substrate; timing never
  * reads wall time.
  *
  * Tests must advance the clock to make time-dependent fibers progress. `EruTest.withTestClock`
  * completes all pending callbacks at scope exit as the teardown safety net; a test that never
  * advances a needed fiber hangs until then (or until the suite timeout).
  *
  * Boundary rule: a race or timeout is decided at the instant the clock fires its callbacks. An
  * effect that completes strictly before an advancement wins over a deadline reached by that
  * advancement. A zero or negative timeout duration means the deadline has already passed: the
  * timeout fails immediately without running the effect.
  */
final class TestClockBackend(testClock: TestClock) extends ConcurrencyBackend {

  /** Dedicated virtual-thread executor for forked fibers.
    *
    * The testkit must not use the common ForkJoinPool: the forked test JVM caps it at 4 threads
    * (`ForkJoinPool.common.parallelism=4`), and fibers parked on logical-time sleeps would exhaust
    * it across suites, starving unrelated tests. Virtual threads park cheaply, so an unbounded
    * executor can hold any number of logically-sleeping fibers.
    */
  private val executor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

  /** Logical-timing capability flags: the execution substrate is virtual threads but timing is
    * logical, there is no Java structured concurrency, and sleep parks its thread until the clock
    * releases it.
    */
  val capabilities: BackendCapabilities = new BackendCapabilities(
    virtualThreads = false,
    structuredScopes = false,
    timersNonBlocking = false
  )

  /** Forks the effect onto the backend's virtual-thread executor. Fiber timing remains logical. */
  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]] =
    Eru.succeed {
      val fiberId = FiberId.fresh()
      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(fiberId)))
      val activeFiber = UnifiedFiber.active[E, A](fiberId)
      executor.submit(new Runnable {
        def run(): Unit = {
          UnifiedFiber.setThread(activeFiber, Thread.currentThread())
          val exit =
            try Result.toExit(fa.attempt.unsafeRunSync())
            catch { case t: Throwable => Exit.Die(t) }
          UnifiedFiber.complete(activeFiber, exit)
          observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(fiberId, exit)))
        }
      })
      activeFiber
    }

  /** Sleeps until the TestClock reaches `now + duration`.
    *
    * Schedules the completion on the clock and parks the calling thread until the clock fires it.
    * The clock never advancing means the sleep never completes — there is no wall-clock fallback.
    * Interruption while parked unwinds as fiber interruption.
    */
  def sleep(duration: Duration): Eru[Nothing, Unit] = {
    if (duration.isNegative || duration.isZero) {
      Eru.unit
    } else {
      Eru.interruptibleBlocking {
        val t = Thread.currentThread()
        val fired = new java.util.concurrent.atomic.AtomicBoolean(false)
        val target = testClock.currentTime.plus(duration)
        val cancel = testClock.scheduleCancellable(
          target,
          () => {
            fired.set(true)
            java.util.concurrent.locks.LockSupport.unpark(t)
          }
        )
        try
          while (!fired.get() && !t.isInterrupted) {
            java.util.concurrent.locks.LockSupport.park()
          }
        finally if (!fired.get()) cancel()
        if (t.isInterrupted) {
          val _ = Thread.interrupted()
          throw new InterruptedException("sleep interrupted")
        }
      }
    }
  }

  /** Races two effects: the first to complete in logical time wins, the loser is interrupted.
    *
    * Pure values win immediately (same fast paths as the runtime backend). Otherwise both effects
    * are forked; each records its result on completion, and the first recording decides the winner.
    * The loser's thread is then interrupted; the race does not wait for its finalizers.
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
    if (Eru.isPureValue(fa)) {
      fa.map(Left.apply)
    } else if (Eru.isPureValue(fb)) {
      fb.map(Right.apply)
    } else {
      Eru.interruptibleBlocking {
        val latch = new CountDownLatch(1)
        val winnerBox = new AtomicReference[Option[Either[Result[E1, A], Result[E2, B]]]](None)

        def recordA(r: Result[E1, A]): Unit =
          if winnerBox.compareAndSet(None, Some(Left(r))) then latch.countDown()

        def recordB(r: Result[E2, B]): Unit =
          if winnerBox.compareAndSet(None, Some(Right(r))) then latch.countDown()

        def runAndRecordA(effect: Eru[E1, A]): Eru[E1, A] =
          effect.attempt.flatMap { r =>
            Eru.effect { recordA(r); () }.attempt
              .map(_ => ())
              .flatMap(_ =>
                r match {
                  case Result.Success(a) => Eru.succeed(a)
                  case Result.Failure(e) => Eru.fail(e)
                }
              )
          }

        def runAndRecordB(effect: Eru[E2, B]): Eru[E2, B] =
          effect.attempt.flatMap { r =>
            Eru.effect { recordB(r); () }.attempt
              .map(_ => ())
              .flatMap(_ =>
                r match {
                  case Result.Success(b) => Eru.succeed(b)
                  case Result.Failure(e) => Eru.fail(e)
                }
              )
          }

        val fiberA = fork(runAndRecordA(fa)).unsafeRunSync()
        val fiberB = fork(runAndRecordB(fb)).unsafeRunSync()

        latch.await()

        winnerBox.get() match {
          case Some(Left(_)) =>
            val _ = fiberB.interrupt(InterruptCause.Cancelled()).attempt.unsafeRunSync()
          case Some(Right(_)) =>
            val _ = fiberA.interrupt(InterruptCause.Cancelled()).attempt.unsafeRunSync()
          case None => ()
        }

        winnerBox.get().getOrElse(throw new IllegalStateException("race completed without a winner"))
      }.flatMap {
        case Left(Result.Success(a)) => Eru.succeed(Left(a))
        case Left(Result.Failure(e)) => Eru.fail(e)
        case Right(Result.Success(b)) => Eru.succeed(Right(b))
        case Right(Result.Failure(e)) => Eru.fail(e)
      }
    }

  /** Timeout as a logical race against a clock-driven deadline. */
  def timeout[E, A](duration: Duration)(
    fa: Eru[E, A]
  ): Eru[E | TimeoutException | Throwable, A] =
    if (duration.isNegative || duration.isZero) {
      Eru.fail(new TimeoutException(s"Operation timed out after $duration"))
    } else {
      race(fa, sleep(duration)).flatMap {
        case Left(a) => Eru.succeed(a)
        case Right(_) => Eru.fail(new TimeoutException(s"Operation timed out after $duration"))
      }
    }

  /** Retries typed failures; backoff delays are logical (each sleep parks until the clock advances
    * past it).
    */
  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import EruRuntime.Policy.*

    def delay(i: Int): Option[Duration] = policy match {
      case NoDelay(n) => if (i < n) Some(Duration.ZERO) else None
      case Exponential(base, maxRet) => if (i < maxRet) Some(base.multipliedBy(1L << i)) else None
    }

    def loop(i: Int): Eru[E, A] =
      fa.recoverWith {
        case t: Throwable => Eru.fail(t)
        case e =>
          delay(i) match {
            case Some(d) => sleep(d).flatMap(_ => loop(i + 1))
            case None => Eru.fail(e)
          }
      }

    loop(0)
  }

  /** Suspends until the async source invokes the callback.
    *
    * Parks the calling thread on a latch with no wall-clock timeout: the source is responsible for
    * completion (for clock-driven sources, the clock is). A registration failure is reported as a
    * `Left` on the success channel, matching the historical shape of this backend.
    */
  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    Eru.interruptibleBlocking {
      val resultBox = new AtomicReference[Option[Either[E, A]]](None)
      val completionLatch = new CountDownLatch(1)

      val callback: Either[E, A] => Unit =
        ea => if resultBox.compareAndSet(None, Some(ea)) then completionLatch.countDown()

      val registrationExit = register(callback).attempt.unsafeRunSync()

      registrationExit match {
        case Result.Failure(t) => throw t
        case Result.Success(_) => ()
      }

      resultBox.get() match {
        case Some(result) => result
        case None =>
          completionLatch.await()
          resultBox.get().getOrElse(throw new IllegalStateException("suspend completed without a result"))
      }
    }.attempt.flatMap {
      case Result.Success(result) => Eru.succeed(result)
      case Result.Failure(t) => Eru.succeed(Left(t))
    }

  /** No clock state to release; shuts down the fiber executor. Scheduled callbacks are owned by the
    * TestClock, not the backend.
    */
  override def cleanup(): Unit = executor.shutdown()
}

/** Factory object for creating TestClockBackend instances. */
object TestClockBackend {

  /** Creates a new TestClockBackend with the provided TestClock.
    *
    * @param testClock
    *   the TestClock to use for timing operations
    * @return
    *   a new TestClockBackend instance
    */
  def apply(testClock: TestClock): TestClockBackend = new TestClockBackend(testClock)

  /** Creates a new TestClockBackend with a fresh TestClock starting at current time.
    *
    * @return
    *   a new TestClockBackend with a fresh TestClock
    */
  def create(): TestClockBackend = new TestClockBackend(TestClock.create())

  /** Creates a new TestClockBackend with a TestClock starting at the specified time.
    *
    * @param startTime
    *   the initial logical time for the TestClock
    * @return
    *   a new TestClockBackend with configured TestClock
    */
  def create(startTime: java.time.Instant): TestClockBackend =
    new TestClockBackend(TestClock.create(startTime))
}
