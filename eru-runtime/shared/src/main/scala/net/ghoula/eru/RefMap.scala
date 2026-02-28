package net.ghoula.eru

/** A concurrent map with independent per-key CAS semantics.
  *
  * Unlike a `Ref[Map[K, V]]` which requires a global CAS on every update, RefMap uses a
  * `ConcurrentHashMap` internally so updates to different keys never contend with each other. This
  * makes it ideal for managing large numbers of independent states (e.g., per-domain crawler state
  * for 100K domains).
  *
  * All operations are total (error type is `Nothing`) and safe to use across fibers.
  *
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
trait RefMap[K, V] {

  /** Gets the value associated with a key.
    * @return
    *   `Some(value)` if key exists, `None` otherwise
    */
  def get(key: K): Eru[Nothing, Option[V]]

  /** Puts a value for a key, returning the previous value if any.
    * @return
    *   the previous value associated with the key, or `None`
    */
  def put(key: K, value: V): Eru[Nothing, Option[V]]

  /** Atomically updates the value for a key using `f`. No-op if the key doesn't exist.
    *
    * Uses `ConcurrentHashMap.compute` for per-key atomicity — updates to key A never contend with
    * updates to key B.
    *
    * @return
    *   `Some(newValue)` if key existed, `None` otherwise
    */
  def update(key: K)(f: V => V): Eru[Nothing, Option[V]]

  /** Atomically modifies the value for a key using `f`, returning an auxiliary result.
    * @return
    *   `Some(b)` if key existed, `None` otherwise
    */
  def modify[B](key: K)(f: V => (V, B)): Eru[Nothing, Option[B]]

  /** Removes a key, returning the previous value if any. */
  def remove(key: K): Eru[Nothing, Option[V]]

  /** Gets the value for a key, or returns the default if absent. */
  def getOrElse(key: K, default: => V): Eru[Nothing, V]

  /** Atomically updates an existing key or creates it with `create` if absent.
    * @return
    *   the new value (either `create` for new keys or `f(existing)` for existing)
    */
  def updateOrCreate(key: K, create: => V)(f: V => V): Eru[Nothing, V]

  /** Returns the set of all keys currently in the map. */
  def keys: Eru[Nothing, Set[K]]

  /** Returns the number of entries in the map. */
  def size: Eru[Nothing, Int]

  /** Returns a snapshot of the map as an immutable `Map`. */
  def toMap: Eru[Nothing, Map[K, V]]

  /** Returns a zero-allocation iterable view of the values.
    *
    * Unlike `toMap`, this does not copy the map into an immutable structure. The returned
    * `Iterable` streams directly from the underlying `ConcurrentHashMap`, making it suitable for
    * hot-path aggregation (e.g., progress reporting) where allocating 100K tuples every 10 seconds
    * would be a GC bomb.
    */
  def values: Eru[Nothing, Iterable[V]]
}

object RefMap {

  /** Creates an empty RefMap. */
  def make[K, V]: Eru[Nothing, RefMap[K, V]] =
    Eru.succeed(new RuntimeRefMap[K, V]())

  /** Creates a RefMap pre-populated with the given entries. */
  def from[K, V](entries: Iterable[(K, V)]): Eru[Nothing, RefMap[K, V]] =
    Eru.effectTotal {
      val rm = new RuntimeRefMap[K, V]()
      entries.foreach { case (k, v) => rm.chm.put(k, v) }
      rm
    }

  private final class RuntimeRefMap[K, V]() extends RefMap[K, V] {
    private[RefMap] val chm = new java.util.concurrent.ConcurrentHashMap[K, V]()

    def get(key: K): Eru[Nothing, Option[V]] =
      Eru.effectTotal(Option(chm.get(key)))

    def put(key: K, value: V): Eru[Nothing, Option[V]] =
      Eru.effectTotal(Option(chm.put(key, value)))

    def update(key: K)(f: V => V): Eru[Nothing, Option[V]] =
      Eru.effectTotal {
        var result: Option[V] = None
        chm.computeIfPresent(
          key,
          (_, v) => {
            val newV = f(v)
            result = Some(newV)
            newV
          }
        )
        result
      }

    def modify[B](key: K)(f: V => (V, B)): Eru[Nothing, Option[B]] =
      Eru.effectTotal {
        var result: Option[B] = None
        chm.computeIfPresent(
          key,
          (_, v) => {
            val (newV, b) = f(v)
            result = Some(b)
            newV
          }
        )
        result
      }

    def remove(key: K): Eru[Nothing, Option[V]] =
      Eru.effectTotal(Option(chm.remove(key)))

    def getOrElse(key: K, default: => V): Eru[Nothing, V] =
      Eru.effectTotal {
        Option(chm.get(key)).getOrElse(default)
      }

    def updateOrCreate(key: K, create: => V)(f: V => V): Eru[Nothing, V] =
      Eru.effectTotal {
        chm.merge(key, create, (existing, _) => f(existing))
        chm.get(key)
      }

    def keys: Eru[Nothing, Set[K]] =
      Eru.effectTotal {
        import scala.jdk.CollectionConverters.*
        chm.keySet().asScala.toSet
      }

    def size: Eru[Nothing, Int] =
      Eru.effectTotal(chm.size())

    def toMap: Eru[Nothing, Map[K, V]] =
      Eru.effectTotal {
        import scala.jdk.CollectionConverters.*
        chm.asScala.toMap
      }

    def values: Eru[Nothing, Iterable[V]] =
      Eru.effectTotal {
        import scala.jdk.CollectionConverters.*
        chm.values().asScala
      }
  }
}
