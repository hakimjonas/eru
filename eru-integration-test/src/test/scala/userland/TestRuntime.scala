package userland

import net.ghoula.eru.prelude.*

/** Centralized testing utility for userland tests.
  *
  * This utility provides a consistent API for running Eru computations in tests, using the proper
  * Eru execution methods. The goal is to ensure tests are isolated and use the real, correct Eru
  * runtime behavior.
  *
  * This replaces the complex backend-based isolation approach with simple use of Eru's built-in
  * execution methods, which already handle isolation correctly.
  */
object TestRuntime {

  /** Executes an Eru computation that cannot fail.
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
    computation.unsafeRunSync()
  }

  /** Executes an Eru computation that cannot fail with observer support.
    *
    * @param computation
    *   the Eru computation to execute
    * @param observer
    *   the observer to receive lifecycle events
    * @return
    *   the result of the computation
    */
  def runIsolatedWith[A](computation: Eru[Nothing, A], observer: EruObserver): A = {
    computation.runWith(observer)
  }

  /** Executes an Eru computation that may fail, returning Exit result.
    *
    * @param computation
    *   the Eru computation to execute
    * @return
    *   the Exit result of the computation
    */
  def runIsolatedExit[E, A](computation: Eru[E, A]): Exit[E, A] = {
    computation.runExit()
  }

  /** Executes an Eru computation that may fail with observer support, returning Exit result.
    *
    * @param computation
    *   the Eru computation to execute
    * @param observer
    *   the observer to receive lifecycle events
    * @return
    *   the Exit result of the computation
    */
  def runIsolatedExitWith[E, A](computation: Eru[E, A], observer: EruObserver): Exit[E, A] = {
    computation.attempt.map(Result.toExit).runWith(observer)
  }

  /** Extension methods to provide a more natural API for test execution. */
  extension [E, A](computation: Eru[E, A]) {

    /** Execute this computation with test isolation, returning Exit result.
      *
      * This method works for any Eru computation and returns the Exit result instead of throwing
      * exceptions.
      */
    @scala.annotation.targetName("runIsolatedExitExtension")
    def runIsolatedExit: Exit[E, A] = {
      computation.runExit()
    }

    /** Execute this computation with test isolation and observer, returning Exit result.
      *
      * This method works for any Eru computation and returns the Exit result instead of throwing
      * exceptions.
      */
    @scala.annotation.targetName("runIsolatedExitWithExtension")
    def runIsolatedExitWith(observer: EruObserver): Exit[E, A] = {
      computation.attempt.map(Result.toExit).runWith(observer)
    }
  }
}
