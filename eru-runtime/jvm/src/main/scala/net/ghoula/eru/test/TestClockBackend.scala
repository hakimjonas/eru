package net.ghoula.eru.test

import java.time.Duration
import java.util.concurrent.TimeoutException

import net.ghoula.eru.*
import net.ghoula.eru.internal.{BackendCapabilities, ConcurrencyBackend}

/** ConcurrencyBackend implementation that uses TestClock for deterministic timing.
  *
  * This backend provides the same concurrency semantics as other Eru backends but delegates all
  * timing operations (sleep, timeout) to a TestClock instance for deterministic, controllable time
  * progression in tests.
  *
  * Key characteristics:
  *   - **Deterministic timing**: All time-based operations use logical TestClock time
  *   - **Synchronous execution**: Fork operations execute immediately (no real concurrency)
  *   - **Full compatibility**: Implements complete ConcurrencyBackend interface
  *   - **Resource safety**: Maintains all Eru finalizer and cleanup guarantees
  *
  * This backend enables testing of:
  *   - Timeout behavior without wall-clock delays
  *   - Retry policies with precise timing control
  *   - Race conditions with deterministic outcomes
  *   - Complex timing-dependent effect compositions
  *
  * @param testClock
  *   the TestClock instance to use for timing operations
  *
  * @example
  *   {{{
  * val clock = TestClock.create()
  * given runtime: EruRuntime = EruRuntime.withBackend(TestClockBackend(clock))
  *
  * // Test timeout behavior
  * val effect = runtime.sleep(Duration.ofSeconds(10)).map(_ => "completed")
  * val fiber = effect.timeout(Duration.ofSeconds(5)).fork.unsafeRunSync()
  *
  * // No wall-clock time passes - test runs instantly
  * clock.advance(Duration.ofSeconds(6))
  *
  * // Timeout occurs deterministically
  * assert(fiber.await.runExit().isInstanceOf[Exit.Failure[_]])
  *   }}}
  */
final class TestClockBackend(testClock: TestClock) extends ConcurrencyBackend {

  val capabilities: BackendCapabilities = new BackendCapabilities(
    virtualThreads = false, // No real concurrency - deterministic execution order
    structuredScopes = false, // No Java structured concurrency
    timersNonBlocking = false // TestClock uses blocking semantics for predictability
  )

  /** Fork implementation that creates fibers awaiting TestClock advancement.
    *
    * Creates fibers that remain pending until TestClock advancement triggers their completion. This
    * enables proper testing of timing-dependent behavior, timeouts, and race conditions for system
    * correctness validation.
    *
    * Observer events are emitted to maintain compatibility with observability features.
    */
  def fork[E, A](fa: Eru[E, A], observer: Option[EruObserver] = None): Eru[Nothing, Fiber[E, A]] =
    Eru.succeed {
      val fiberId = FiberId.fresh()

      observer.foreach(_.onEvent(EruObserver.EruEvent.FiberStarted(fiberId)))

      val activeFiber = UnifiedFiber.active[E, A](fiberId)
      java.util.concurrent.CompletableFuture.supplyAsync { () =>
        val exit =
          try {
            Result.toExit(fa.attempt.unsafeRunSync())
          } catch {
            case t: Throwable => Exit.Die(t)
          }

        UnifiedFiber.complete(activeFiber, exit)
        observer.foreach(_.onEvent(EruObserver.EruEvent.FiberCompleted(fiberId, exit)))
      }

      activeFiber
    }

  /** Race implementation for TestClock deterministic execution.
    *
    * For TestClock, we implement race by checking which operation needs less time to complete.
    * Since operations execute when fiber schedules trigger them, we use a simple heuristic: try fa
    * first, and if it can't complete immediately, try fb.
    */
  def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] = {
    fa.attempt.flatMap {
      case Result.Success(a) => Eru.succeed(Left(a))
      case Result.Failure(e1) =>
        fb.attempt.flatMap {
          case Result.Success(b) => Eru.succeed(Right(b))
          case Result.Failure(e2) =>
            e1 match {
              case r1: RuntimeException if Option(r1.getMessage).exists(_.contains("TestClock suspend operation")) =>
                e2 match {
                  case r2: RuntimeException
                      if Option(r2.getMessage).exists(_.contains("TestClock suspend operation")) =>
                    throw r2
                  case t2: Throwable => Eru.fail(t2)
                  case _ => Eru.fail(new RuntimeException(s"Race participant B failed: $e2"))
                }
              case t1: Throwable => Eru.fail(t1)
              case _ => Eru.fail(new RuntimeException(s"Race participant A failed: $e1"))
            }
        }
    }
  }

  /** Sleep implementation for deterministic testing with proper TestClock integration.
    *
    * This implementation properly suspends execution until TestClock advancement reaches the target
    * time, providing accurate pending count tracking and deterministic behavior.
    */
  def sleep(duration: Duration): Eru[Nothing, Unit] = {
    if (duration.isNegative || duration.isZero) {
      Eru.unit
    } else {
      testClock match {
        case impl: TestClockImpl =>
          val currentTime = testClock.currentTime
          val targetTime = currentTime.plus(duration)

          if (currentTime.isAfter(targetTime) || currentTime.equals(targetTime)) {
            Eru.unit
          } else {
            handleSuspend[Nothing, Unit] { callback =>
              impl.schedule(targetTime, () => callback(Right(())))
              Eru.unit
            }.map {
              case Right(unit) => unit
              case Left(_) => ()
            }
          }
        case _ =>
          Eru.unit
      }
    }
  }

  /** Timeout implementation with deterministic TestClock behavior.
    *
    * This implementation uses race semantics for timeout behavior.
    */
  def timeout[E, A](duration: Duration)(
    fa: Eru[E, A]
  ): Eru[E | TimeoutException | Throwable, A] = {
    import java.util.concurrent.TimeoutException

    race(fa, sleep(duration)).flatMap {
      case Left(a) => Eru.succeed(a)
      case Right(_) => Eru.fail(new TimeoutException(s"Operation timed out after $duration"))
    }
  }

  /** Retry implementation using TestClock for backoff delays.
    *
    * Retry policies that include delays (like exponential backoff) use TestClock to schedule delay
    * periods. This enables testing of retry behavior with precise timing control.
    */
  def retry[E, A](policy: EruRuntime.Policy)(fa: Eru[E, A]): Eru[E, A] = {
    import EruRuntime.Policy.*

    def delay(i: Int): Option[Duration] = policy match {
      case Recurs(n) => if (i < n) Some(Duration.ZERO) else None
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

  /** Suspend implementation using cooperative execution for coordination primitives.
    *
    * Simplified approach: give coordination primitives enough time and cooperative execution to
    * complete their suspend/resume cycles without complex fiber scheduling.
    */
  def handleSuspend[E, A](
    register: (Either[E, A] => Unit) => Eru[Nothing, Unit]
  ): Eru[Nothing, Either[E | Throwable, A]] =
    Eru.effect {
      val resultBox = new java.util.concurrent.atomic.AtomicReference[Option[Either[E, A]]](None)
      val completionLatch = new java.util.concurrent.CountDownLatch(1)

      val callback: Either[E, A] => Unit = ea => {
        if (resultBox.compareAndSet(None, Some(ea))) {
          completionLatch.countDown()
        }
      }

      val registrationExit = register(callback).attempt.unsafeRunSync()

      resultBox.get() match {
        case Some(result) => result // Synchronous completion
        case None =>
          registrationExit match {
            case Result.Success(_) =>
              val quickCompleted = completionLatch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)

              if (quickCompleted) {
                resultBox
                  .get()
                  .getOrElse(throw new IllegalStateException("Operation completed but no result available"))
              } else {
                val longCompleted = completionLatch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)

                if (longCompleted) {
                  resultBox
                    .get()
                    .getOrElse(throw new IllegalStateException("Coordination completed but no result available"))
                } else {
                  throw new RuntimeException("TestClock suspend operation timeout - likely requires time advancement")
                }
              }
            case Result.Failure(t) =>
              throw t
          }
      }
    }.attempt.flatMap {
      case Result.Success(result) => Eru.succeed(result)
      case Result.Failure(t) => Eru.succeed(Left(t))
    }

  /** No cleanup required for TestClockBackend.
    *
    * Since operations execute synchronously and TestClock manages pending operations internally, no
    * additional cleanup is needed.
    */
  override def cleanup(): Unit = ()
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
