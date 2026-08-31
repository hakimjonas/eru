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

  /** Default runtime for convenient concurrent operations.
    *
    * Uses EruRuntime.shared for ergonomic concurrent operations without requiring explicit runtime
    * management. Applications that need isolated runtimes can override this with their own given
    * instance.
    */
  given defaultRuntime: EruRuntime = EruRuntime.shared

  /** Type alias for the time-capability `Monotonic`, re-exposed for discoverability. The default
    * `given Monotonic` lives in `Monotonic`'s companion and resolves automatically — no
    * `import given` needed.
    */
  type Monotonic = net.ghoula.eru.time.Monotonic

  /** Type alias for the opaque monotonic instant, re-exposed for discoverability. */
  type MonotonicInstant = net.ghoula.eru.time.MonotonicInstant

  /** Exposes the `MonotonicInstant` companion (factory + extension methods). */
  val MonotonicInstant: net.ghoula.eru.time.MonotonicInstant.type = net.ghoula.eru.time.MonotonicInstant

  /** Exposes the EruRuntime companion object for runtime creation and Policy types. */
  val EruRuntime: net.ghoula.eru.EruRuntime.type = net.ghoula.eru.EruRuntime

  /** Type alias for the runtime, re-exposed for discoverability. */
  type EruRuntime = net.ghoula.eru.EruRuntime

  /** Exposes the EruObserver companion via the unified prelude so that observer helpers (e.g.,
    * noop, console) and event types are available from the same canonical import.
    */
  val EruObserver: net.ghoula.eru.EruObserver.type = net.ghoula.eru.EruObserver

  /** Type alias for the runtime Ref, re-exposed for discoverability. `A` is the element type stored
    * in the reference.
    */
  type Ref[A] = net.ghoula.eru.Ref[A]

  /** Type alias for the runtime Deferred, re-exposed for discoverability. `A` is the value type
    * produced when the deferred completes.
    */
  type Deferred[A] = net.ghoula.eru.Deferred[A]

  /** Type alias for the runtime Semaphore, re-exposed for discoverability. */
  type Semaphore = net.ghoula.eru.Semaphore

  /** Type alias for the runtime Queue, re-exposed for discoverability. `A` is the element type
    * stored in the queue.
    */
  type Queue[A] = net.ghoula.eru.Queue[A]

  /** Type alias for the runtime Hub, re-exposed for discoverability. `A` is the message type
    * published through the hub.
    */
  type Hub[A] = net.ghoula.eru.Hub[A]

  /** Type alias for the runtime Promise, re-exposed for discoverability. `E` is the error type for
    * failures and `A` the value type for successful completion.
    */
  type Promise[E, A] = net.ghoula.eru.Promise[E, A]

  /** Type alias for the runtime CountDownLatch, re-exposed for discoverability. */
  type CountDownLatch = net.ghoula.eru.CountDownLatch

  /** Type alias for the runtime CyclicBarrier, re-exposed for discoverability. */
  type CyclicBarrier = net.ghoula.eru.CyclicBarrier

  /** Concurrent map with independent per-key CAS semantics. `K` is the key type and `V` the value
    * type.
    */
  type RefMap[K, V] = net.ghoula.eru.RefMap[K, V]

  /** Exposes the RefMap companion for factory methods. */
  val RefMap: net.ghoula.eru.RefMap.type = net.ghoula.eru.RefMap

  /** Per-key concurrency limiter backed by independent semaphores. `K` is the key type (e.g.,
    * hostname).
    */
  type KeyedSemaphore[K] = net.ghoula.eru.KeyedSemaphore[K]

  /** Exposes the KeyedSemaphore companion for factory methods. */
  val KeyedSemaphore: net.ghoula.eru.KeyedSemaphore.type = net.ghoula.eru.KeyedSemaphore

  /** Computation that may suspend indefinitely (no `unsafeRunSync`). `E` is the error type and `A`
    * the success type.
    */
  type Suspending[+E, +A] = net.ghoula.eru.Suspending[E, A]

  /** Computation that completes immediately (has `unsafeRunSync`). `E` is the error type and `A`
    * the success type.
    */
  type Immediate[+E, +A] = net.ghoula.eru.Immediate[E, A]

  /** Type alias for runtime fibers, re-exposed for discoverability. `E` is the typed error of the
    * fiber and `A` its success type.
    */
  type Fiber[+E, +A] = net.ghoula.eru.Fiber[E, A]

  /** Type alias for fiber tracker, re-exposed for discoverability.
    *
    * FiberTracker enables custom fiber lifecycle management for applications that need fine-grained
    * control over fiber tracking and cleanup strategies.
    */
  type FiberTracker = net.ghoula.eru.FiberTracker

  /** Exposes the FiberTracker companion for creating fiber trackers.
    *
    * @example
    *   {{{
    * import net.ghoula.eru.prelude.*
    *
    * given runtime: EruRuntime = EruRuntime.create()
    *
    * // Create custom tracker for connection pool
    * val tracker = FiberTracker()
    * val fiber = handleConnection(socket).forkTracked(tracker)
    *   }}}
    */
  val FiberTracker: net.ghoula.eru.FiberTracker.type = net.ghoula.eru.FiberTracker
}
