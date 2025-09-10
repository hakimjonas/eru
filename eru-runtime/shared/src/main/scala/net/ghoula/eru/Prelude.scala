package net.ghoula.eru

/** Unified public prelude for Eru.
  *
  * Usage: import net.ghoula.eru.prelude.*
  *
  * This prelude re-exports the complete public surface of eru-core and the runtime extensions so
  * users get a single, canonical import with no exposure of internal packages.
  *
  * @example
  *   {{{
  * import net.ghoula.eru.prelude.*
  * import java.time.Duration
  *
  * val hello: Eru[Nothing, String] = Eru.succeed("hello")
  * val value: String = hello.unsafeRunSync()
  *
  * val refProg: Eru[Nothing, Ref[Int]] = Eru.ref(0)
  * val defProg: Eru[Nothing, Deferred[String]] = Eru.deferred[String]
  * val semProg: Eru[Nothing, Semaphore] = Eru.semaphore(2)
  *
  * val a = Eru.succeed(1)
  * val b = Eru.succeed(2)
  * val par: Eru[Throwable, (Int, Int)] = a.zipPar(b)
  * val raced: Eru[Throwable, Either[Int, Int]] = a.race(b)
  * val timed = a.map(_ => 42).timeout(Duration.ofMillis(50))
  * val fallback: Eru[Throwable, Int] = a.map(_ => 42).timeoutTo(Duration.ofMillis(50), -1)
  *
  * class PrintingObserver extends EruObserver {
  *   def onEvent(e: EruEvent): Unit = println(e)
  * }
  * val observed: Int = Eru.succeed(123).runWith(new PrintingObserver)
  *
  * val exit: Exit[Nothing, Int] = Eru.succeed(1).runExit()
  * exit match {
  *   case Exit.Success(v) => println(s"ok=$v")
  *   case _ => ()
  * }
  *   }}}
  */
object prelude {
  export net.ghoula.eru.CorePrelude.*
  export net.ghoula.eru.RuntimeExtensions.*

  /** Exposes the EruRuntime companion object for runtime creation and Policy types. */
  val EruRuntime = net.ghoula.eru.EruRuntime

  /** Type alias for the runtime, re-exposed for discoverability. */
  type EruRuntime = net.ghoula.eru.EruRuntime

  /** Exposes the EruObserver companion via the unified prelude so that observer helpers (e.g.,
    * noop, console) and event types are available from the same canonical import.
    */
  val EruObserver = net.ghoula.eru.EruObserver

  /** Type alias for the runtime Ref, re-exposed for discoverability.
    * @tparam A
    *   element type stored in the reference
    */
  type Ref[A] = net.ghoula.eru.Ref[A]

  /** Type alias for the runtime Deferred, re-exposed for discoverability.
    * @tparam A
    *   value type produced when completed
    */
  type Deferred[A] = net.ghoula.eru.Deferred[A]

  /** Type alias for the runtime Semaphore, re-exposed for discoverability. */
  type Semaphore = net.ghoula.eru.Semaphore

  /** Type alias for the runtime Queue, re-exposed for discoverability.
    * @tparam A
    *   element type stored in the queue
    */
  type Queue[A] = net.ghoula.eru.Queue[A]

  /** Type alias for runtime fibers, re-exposed for discoverability.
    * @tparam E
    *   typed error of the fiber
    * @tparam A
    *   success type of the fiber
    */
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]
}
