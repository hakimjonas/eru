package net.ghoula.eru.test

import java.time.Duration

import net.ghoula.eru.*
import net.ghoula.eru.internal.{ConcurrencyBackend, RuntimeBackendAdapter}
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.{TestClock, TestClockBackend}

/** Test utility providing complete isolation between test executions.
  *
  * This utility ensures that each test runs with a completely fresh backend instance, preventing
  * any state leakage between tests. The backend's cleanup() method is always called after test
  * completion, even if the test fails or throws an exception.
  *
  * Solves the critical issue where tests pass individually but fail when run as part of the larger
  * test suite due to shared global runtime state.
  */
object IsolatedTestRunner {

  /** Isolated runtime that provides the same API as EruRuntime but with fresh backend per test.
    *
    * This class wraps a fresh backend instance and provides all the standard EruRuntime methods.
    * Use this in tests instead of the global EruRuntime to avoid state interference.
    */
  class IsolatedRuntime(private val backend: ConcurrencyBackend, val testClock: TestClock) {
    def fork[E, A](fa: Eru[E, A]): Eru[Nothing, Fiber[E, A]] = backend.fork(fa)

    def sleep(duration: Duration): Eru[Nothing, Unit] = backend.sleep(duration)

    def race[E1, E2, A, B](fa: Eru[E1, A], fb: Eru[E2, B]): Eru[E1 | E2 | Throwable, Either[A, B]] =
      backend.race(fa, fb)

    /** Parallel sequence implementation that matches EruRuntime.parSequence behavior */
    def parSequence[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, List[A]] =
      effects match {
        case Nil => Eru.succeed(List.empty[A])
        case _ =>
          def forkAll(remaining: List[Eru[E, A]], acc: List[Fiber[E, A]]): Eru[Nothing, List[Fiber[E, A]]] =
            remaining match {
              case Nil => Eru.succeed(acc.reverse)
              case head :: tail =>
                fork(head).flatMap(fiber => forkAll(tail, fiber :: acc))
            }

          def awaitAll(fibers: List[Fiber[E, A]]): Eru[Nothing, List[Exit[E, A]]] =
            fibers match {
              case Nil => Eru.succeed(Nil)
              case head :: tail =>
                for {
                  exit <- head.await
                  rest <- awaitAll(tail)
                } yield exit :: rest
            }

          def processExits(exits: List[Exit[E, A]]): Eru[E | Throwable, List[A]] = {
            exits.collectFirst { case Exit.Interrupt(fiberId, cause) => (fiberId, cause) } match {
              case Some((fiberId, cause)) =>
                Eru.interruptibleBlocking {
                  throw new InterruptedException(s"ParSequence interrupted due to fiber $fiberId: $cause")
                }
              case None =>
                val firstError = exits.collectFirst {
                  case Exit.Failure(error) => Left(error)
                  case Exit.Die(throwable) => Right(throwable)
                }

                firstError match {
                  case Some(Left(error)) => Eru.fail(error)
                  case Some(Right(throwable)) => Eru.effect(throw throwable)
                  case None =>
                    val results = exits.collect { case Exit.Success(value) => value }
                    Eru.succeed(results)
                }
            }
          }

          for {
            fibers <- forkAll(effects, Nil)
            exits <- awaitAll(fibers)
            results <- processExits(exits)
          } yield results
      }

    def zipPar[E1, E2, A, B](left: Eru[E1, A], right: Eru[E2, B]): Eru[E1 | E2 | Throwable, (A, B)] =
      for {
        fiberA <- fork(left)
        fiberB <- fork(right)
        exitA <- fiberA.await
        exitB <- fiberB.await
        result <- (exitA, exitB) match {
          case (Exit.Success(a), Exit.Success(b)) =>
            Eru.succeed((a, b))
          case (Exit.Failure(error), _) =>
            Eru.fail(error)
          case (_, Exit.Failure(error)) =>
            Eru.fail(error)
          case (Exit.Die(throwable), _) =>
            Eru.effect(throw throwable)
          case (_, Exit.Die(throwable)) =>
            Eru.effect(throw throwable)
          case (Exit.Interrupt(_, _), _) | (_, Exit.Interrupt(_, _)) =>
            Eru.interruptibleBlocking {
              throw new InterruptedException("ZipPar interrupted")
            }
        }
      } yield result

    def raceAll[E, A](effects: List[Eru[E, A]]): Eru[E | Throwable, (A, Int)] = {
      def raceWithIndex(remaining: List[Eru[E, A]], currentIndex: Int): Eru[E | Throwable, (A, Int)] =
        remaining match {
          case Nil => Eru.effect(throw new IllegalStateException("raceAll: unexpected empty list"))
          case single :: Nil => single.map(a => (a, currentIndex))
          case current :: rest =>
            race(current, raceWithIndex(rest, currentIndex + 1)).flatMap {
              case Left(value) => Eru.succeed((value, currentIndex))
              case Right((value, index)) => Eru.succeed((value, index))
            }
        }

      effects match {
        case Nil =>
          Eru.effect(throw new IllegalArgumentException("raceAll: empty list of effects"))
        case single :: Nil =>
          single.map(a => (a, 0))
        case _ =>
          raceWithIndex(effects, 0)
      }
    }

    def forkWithObserver[E, A](fa: Eru[E, A], observer: EruObserver): Eru[Nothing, Fiber[E, A]] = {
      backend.fork(fa, Some(observer))
    }

    def timeout[E, A](duration: Duration)(
      effect: Eru[E, A]
    ): Eru[E | java.util.concurrent.TimeoutException | Throwable, A] =
      backend.timeout(duration)(effect)

    def suspend[E, A](register: (Either[E, A] => Unit) => Eru[Nothing, Unit]): Eru[E | Throwable, A] =
      backend.handleSuspend(register).flatMap {
        case Right(value) => Eru.succeed(value)
        case Left(error) => Eru.fail(error)
      }

    def cleanup(): Unit = backend.cleanup()
  }

  /** Creates an isolated runtime for test execution.
    *
    * The returned runtime uses a fresh TestClockBackend instance that won't interfere with other
    * tests and provides deterministic timing behavior. Remember to call cleanup() after your test
    * completes.
    */
  def createIsolatedRuntime(): IsolatedRuntime = {
    val testClock = TestClock.create()
    val backend = new TestClockBackend(testClock)
    new IsolatedRuntime(backend, testClock)
  }

  /** Executes a test function with an isolated runtime, ensuring proper cleanup.
    *
    * @param testFn
    *   function that receives an isolated runtime and returns a result
    * @return
    *   the test result
    */
  def withIsolatedRuntime[A](testFn: IsolatedRuntime => A): A = {
    val runtime = createIsolatedRuntime()
    try {
      testFn(runtime)
    } finally {
      runtime.cleanup()
    }
  }

  /** Executes an Eru computation with complete test isolation.
    *
    * Creates a fresh RuntimeBackend instance for this test execution only, ensuring no shared state
    * with other tests. The backend is properly cleaned up after execution completes.
    *
    * @param computation
    *   the Eru computation to execute
    * @return
    *   the result of the computation
    * @throws EruException
    *   if the computation fails with a typed error
    * @throws Throwable
    *   if the computation fails with an untyped exception
    */
  def runIsolated[A](computation: Eru[Nothing, A]): A = {
    val backend = RuntimeBackendAdapter.virtualThreads()
    try {
      computation.runExit() match {
        case Exit.Success(value) => value
        case Exit.Failure(error) => throw EruException(error)
        case Exit.Die(throwable) => throw throwable
        case Exit.Interrupt(_, cause) =>
          throw new InterruptedException(s"Computation was interrupted: $cause")
      }
    } finally {
      backend.cleanup()
    }
  }

  /** Executes an Eru computation with complete test isolation and observer support.
    *
    * Creates a fresh RuntimeBackend instance for this test execution only, ensuring no shared state
    * with other tests. The backend is properly cleaned up after execution completes.
    *
    * @param computation
    *   the Eru computation to execute
    * @param observer
    *   the observer to receive lifecycle events
    * @return
    *   the result of the computation
    * @throws EruException
    *   if the computation fails with a typed error
    * @throws Throwable
    *   if the computation fails with an untyped exception
    */
  def runIsolatedWith[A](computation: Eru[Nothing, A], observer: EruObserver): A = {
    val backend = RuntimeBackendAdapter.virtualThreads()
    try {
      val fiberId = FiberId.fresh()
      observer.onEvent(EruObserver.EruEvent.FiberStarted(fiberId))

      val exit = computation.runExit()
      observer.onEvent(EruObserver.EruEvent.FiberCompleted(fiberId, exit))

      exit match {
        case Exit.Success(value) => value
        case Exit.Failure(error) => throw EruException(error)
        case Exit.Die(throwable) => throw throwable
        case Exit.Interrupt(_, cause) =>
          throw new InterruptedException(s"Computation was interrupted: $cause")
      }
    } finally {
      backend.cleanup()
    }
  }

  /** Executes an Eru computation that may fail with complete test isolation.
    *
    * Creates a fresh RuntimeBackend instance for this test execution only, ensuring no shared state
    * with other tests. The backend is properly cleaned up after execution completes. Returns the
    * Exit result instead of throwing exceptions.
    *
    * @param computation
    *   the Eru computation to execute
    * @return
    *   the Exit result of the computation
    */
  def runIsolatedExit[E, A](computation: Eru[E, A]): Exit[E, A] = {
    val backend = RuntimeBackendAdapter.virtualThreads()
    try {
      computation.runExit()
    } finally {
      backend.cleanup()
    }
  }

  /** Executes an Eru computation that may fail with complete test isolation and observer.
    *
    * Creates a fresh RuntimeBackend instance for this test execution only, ensuring no shared state
    * with other tests. The backend is properly cleaned up after execution completes. Returns the
    * Exit result instead of throwing exceptions.
    *
    * @param computation
    *   the Eru computation to execute
    * @param observer
    *   the observer to receive lifecycle events
    * @return
    *   the Exit result of the computation
    */
  def runIsolatedExitWith[E, A](computation: Eru[E, A], observer: EruObserver): Exit[E, A] = {
    val backend = RuntimeBackendAdapter.virtualThreads()
    try {
      val fiberId = FiberId.fresh()
      observer.onEvent(EruObserver.EruEvent.FiberStarted(fiberId))

      val exit = computation.runExit()
      observer.onEvent(EruObserver.EruEvent.FiberCompleted(fiberId, exit))

      exit
    } finally {
      backend.cleanup()
    }
  }
}
