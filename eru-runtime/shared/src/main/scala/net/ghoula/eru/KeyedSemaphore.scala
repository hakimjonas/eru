package net.ghoula.eru

import net.ghoula.eru.prelude.*

/** A per-key concurrency limiter backed by independent semaphores.
  *
  * Each key gets its own `Semaphore` with a fixed number of permits, created lazily on first
  * access. Operations on different keys never contend — `withPermit("a")(slow)` does not block
  * `withPermit("b")(fast)`.
  *
  * This replaces a single global `Semaphore(N)` with precise per-key throttling, e.g., per-host
  * concurrency limits in a crawler.
  *
  * @tparam K
  *   the key type (e.g., hostname)
  */
trait KeyedSemaphore[K] {

  /** Acquires a permit for `key`, runs `fa`, and releases it afterward.
    *
    * Suspends until a permit is available for the given key.
    */
  def withPermit[E, A](key: K)(fa: => Eru[E, A]): Eru[E, A]

  /** Acquires a permit for the given key. Suspends until available. */
  def acquire(key: K): Eru[Nothing, Unit]

  /** Releases a permit for the given key. */
  def release(key: K): Eru[Nothing, Unit]

  /** Returns the number of permits currently available for the given key.
    *
    * Returns `permitsPerKey` if the key has never been accessed.
    */
  def permitsAvailable(key: K): Eru[Nothing, Long]

  /** Returns the set of keys that have been accessed (have live semaphores). */
  def activeKeys: Eru[Nothing, Set[K]]
}

object KeyedSemaphore {

  /** Creates a new KeyedSemaphore where each key gets `permitsPerKey` permits.
    *
    * @param permitsPerKey
    *   number of permits per key (typically 1 for per-host throttling)
    */
  def make[K](permitsPerKey: Long)(using runtime: EruRuntime): Eru[Nothing, KeyedSemaphore[K]] =
    Eru.succeed(new RuntimeKeyedSemaphore[K](permitsPerKey, runtime))

  private final class RuntimeKeyedSemaphore[K](
    permitsPerKey: Long,
    runtime: EruRuntime
  ) extends KeyedSemaphore[K] {
    private val map = new java.util.concurrent.ConcurrentHashMap[K, Semaphore]()

    private def semaphoreFor(key: K): Semaphore =
      map.computeIfAbsent(key, _ => Semaphore.make(permitsPerKey)(using runtime).unsafeRunSync())

    def withPermit[E, A](key: K)(fa: => Eru[E, A]): Eru[E, A] =
      Eru.effectTotal(semaphoreFor(key)).flatMap(_.withPermit(fa).eru)

    def acquire(key: K): Eru[Nothing, Unit] =
      Eru.effectTotal(semaphoreFor(key)).flatMap(_.acquire.eru)

    def release(key: K): Eru[Nothing, Unit] =
      Eru.effectTotal(semaphoreFor(key)).flatMap(_.release.eru)

    def permitsAvailable(key: K): Eru[Nothing, Long] =
      Eru.effectTotal(Option(map.get(key))).flatMap {
        case Some(sem) => sem.permitsAvailable.eru
        case None => Eru.succeed(permitsPerKey)
      }

    def activeKeys: Eru[Nothing, Set[K]] =
      Eru.effectTotal {
        import scala.jdk.CollectionConverters.*
        map.keySet().asScala.toSet
      }
  }
}
