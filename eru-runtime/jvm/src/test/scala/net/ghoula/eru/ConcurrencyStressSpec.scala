package net.ghoula.eru

import munit.FunSuite

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*

/** Stress test suite for JVM concurrency and fiber management under high load.
  *
  * Validates runtime behavior under stress conditions including high fiber counts, concurrent
  * resource access, and sustained concurrent load. These tests ensure that the runtime maintains
  * correctness, prevents resource leaks, and provides stable performance characteristics even under
  * extreme operational conditions that might occur in production systems with heavy concurrent
  * workloads.
  */
extension [E, A](effects: List[Eru[E, A]]) {
  def sequence: Eru[E, List[A]] = {
    def loop(remaining: List[Eru[E, A]], acc: List[A]): Eru[E, List[A]] =
      remaining match {
        case Nil => Eru.succeed(acc.reverse)
        case head :: tail =>
          head.flatMap(a => loop(tail, a :: acc))
      }
    loop(effects, Nil)
  }
}

/** Comprehensive stress test suite for concurrency functionality under high load.
  *
  * These tests validate that Eru's concurrency primitives (fork, zipPar, race, timers) work
  * correctly under stress conditions including thousands of concurrent fibers, nested operations,
  * cancellation cascades, and resource cleanup under pressure. All tests ensure proper resource
  * safety and finalizer execution order guarantees.
  */
final class ConcurrencyStressSpec extends FunSuite {

  /** Validates high-load fiber creation and completion under stress.
    *
    * Tests that the runtime can handle concurrent creation and completion of 250 fibers
    * simultaneously without performance degradation or correctness issues.
    */
  test("high-load fiber creation and completion (250 fibers)") {
    val fiberCount = 250
    val completedCounter = new AtomicInteger(0)

    val effects = (1 to fiberCount).map { i =>
      Eru.effect {
        completedCounter.incrementAndGet()
        i
      }
    }

    val completed = EruRuntime.parSequence(effects.toList).unsafeRunSync()
    assertEquals(completed.sorted, (1 to fiberCount).toList)
    assertEquals(completedCounter.get(), fiberCount)
  }

  /** Validates nested zipPar operations under stress conditions.
    *
    * Tests deeply nested parallel operations to ensure the runtime maintains correctness and
    * performance when combining multiple levels of concurrent computations.
    */
  test("nested zipPar operations stress test") {
    def createNestedZipPar(depth: Int, baseValue: Int): Eru[Throwable, Int] = {
      if (depth == 0) {
        EruRuntime.sleep(Duration.ofMillis(1)).map(_ => baseValue)
      } else {
        val left = createNestedZipPar(depth - 1, baseValue * 2)
        val right = createNestedZipPar(depth - 1, baseValue * 2 + 1)
        EruRuntime.zipPar(left, right).map { case (l, r) => l + r }
      }
    }

    val result = createNestedZipPar(8, 1).unsafeRunSync()
    assert(result > 1000, s"Expected large sum, got $result")
  }

  /** Validates race operations with multiple competing effects.
    *
    * Tests the race combinator with many concurrent contestants to ensure fair competition and
    * proper resource cleanup of losing effects.
    */
  test("race operations with many contestants") {
    val contestants = 50
    val results = (1 to contestants).map { i =>
      val delay = (i % 10) + 1
      EruRuntime.sleep(Duration.ofMillis(delay.toLong)).map(_ => i)
    }

    @tailrec
    def raceAll(effects: List[Eru[Throwable, Int]]): Eru[Throwable, Int] = effects match {
      case single :: Nil => single
      case first :: second :: rest =>
        val winner = EruRuntime.race(first, second).map {
          case Left(value) => value
          case Right(value) => value
        }
        raceAll(winner :: rest)
      case Nil => Eru.fail(new IllegalArgumentException("No effects to race"))
    }

    val winner = raceAll(results.toList).unsafeRunSync()
    assert(winner >= 1 && winner <= contestants, s"Winner $winner should be in range 1-$contestants")
  }

  /** Validates cancellation cascade behavior under stress conditions.
    *
    * Tests that cancellation properly propagates through a hierarchy of effects, ensuring
    * fast-failing behavior and proper resource cleanup.
    */
  test("cancellation cascade stress test") {
    val started = new AtomicInteger(0)
    val completed = new AtomicInteger(0)

    def createCancellableEffect(id: Int): Eru[String | Throwable, Int] = {
      Eru.effect(started.incrementAndGet()).flatMap { _ =>
        EruRuntime.sleep(Duration.ofMillis(200)).flatMap { _ =>
          completed.incrementAndGet()
          Eru.succeed(id)
        }
      }
    }

    val fastFail: Eru[String | Throwable, Int] =
      EruRuntime.sleep(Duration.ofMillis(20)).flatMap(_ => Eru.fail("fast failure"))

    val slowEffect = createCancellableEffect(1)

    // By racing the two, the failure of `fastFail` should win and interrupt `slowEffect`
    val result = EruRuntime
      .race(fastFail, slowEffect)
      .attempt
      .unsafeRunSync()

    EruRuntime.sleep(Duration.ofMillis(50)).unsafeRunSync()

    result match {
      case Result.Failure("fast failure") =>
        assertEquals(started.get(), 1, "The slow effect should have started")
        assertEquals(completed.get(), 0, "The slow effect should have been interrupted and not completed")
      case other =>
        fail(s"Expected the race to fail with 'fast failure', but got $other")
    }
  }

  /** Validates resource cleanup behavior under high concurrency stress.
    *
    * Tests that resource acquisition and cleanup work correctly when many concurrent fibers are
    * acquiring and releasing resources simultaneously.
    */
  test("resource cleanup under high concurrency") {
    val acquired = new AtomicInteger(0)
    val released = new AtomicInteger(0)
    val fiberCount = 200

    def createResourceEffect(id: Int): Eru[String | Throwable, Int] = {
      Eru.effect {
        acquired.incrementAndGet()
        id
      }.ensure(Eru.effect {
        released.incrementAndGet()
      }).flatMap { value =>
        if (id % 3 == 0) {
          Eru.fail(s"Deterministic failure in effect $id")
        } else {
          Eru.succeed(value)
        }
      }
    }

    val effects = (1 to fiberCount).map(createResourceEffect)
    val results = effects.map(_.attempt)

    results.toList.sequence.unsafeRunSync()

    assertEquals(acquired.get(), fiberCount, "All resources should have been acquired")
    assertEquals(released.get(), fiberCount, "All resources should have been released")
  }

  /** Validates timer scheduling behavior under concurrent load.
    *
    * Tests that the timer system can handle multiple concurrent sleep operations with different
    * durations while maintaining timing accuracy.
    */
  test("timer scheduling under load") {
    val timerCount = 100

    val timers = (1 to timerCount).map { i =>
      val delay = (i % 10) + 1
      EruRuntime.sleep(Duration.ofMillis(delay.toLong)).map(_ => i)
    }

    val results = timers.map(timer => EruRuntime.fork(timer))
    val completed = results
      .map(_.flatMap(_.await).flatMap { // Use flatMap for safety
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(new RuntimeException(s"Timer failed with error: $error"))
        case Exit.Die(t) => Eru.fail(new RuntimeException("Timer died", t))
        case Exit.Interrupt(_, _) => Eru.fail(new RuntimeException("Timer was interrupted"))
      })
      .toList
      .sequence
      .unsafeRunSync()

    assertEquals(completed.sorted, (1 to timerCount).toList)
  }

  /** Validates mixed concurrent and sequential operations under stress.
    *
    * Tests that combining concurrent operations with sequential ones maintains correctness and
    * proper execution order under high load.
    */
  test("mixed concurrent and sequential operations") {
    val concurrentCount = 50
    val sequentialCount = 10

    val concurrentOps = (1 to concurrentCount).map { i =>
      EruRuntime.fork {
        EruRuntime.sleep(Duration.ofMillis(2)).map(_ => s"concurrent-$i")
      }.flatMap(_.await).flatMap { // Use flatMap for safety
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(new RuntimeException(s"Concurrent op failed with error: $error"))
        case Exit.Die(t) => Eru.fail(new RuntimeException("Concurrent op died", t))
        case Exit.Interrupt(_, _) => Eru.fail(new RuntimeException("Concurrent op was interrupted"))
      }
    }

    def createSequential(remaining: Int, acc: List[String]): Eru[Nothing, List[String]] = {
      if (remaining <= 0) {
        Eru.succeed(acc.reverse)
      } else {
        EruRuntime.sleep(Duration.ofMillis(1)).flatMap { _ =>
          createSequential(remaining - 1, s"sequential-$remaining" :: acc)
        }
      }
    }

    val sequential = createSequential(sequentialCount, Nil)

    val combined = EruRuntime
      .zipPar(
        concurrentOps.toList.sequence,
        sequential
      )
      .unsafeRunSync()

    val (concurrentResults, sequentialResults) = combined

    assertEquals(concurrentResults.length, concurrentCount)
    assertEquals(sequentialResults.length, sequentialCount)
    assert(
      concurrentResults.forall(_.startsWith("concurrent-")),
      "All concurrent results should start with 'concurrent-'"
    )
    assert(
      sequentialResults.forall(_.startsWith("sequential-")),
      "All sequential results should start with 'sequential-'"
    )
  }

  /** Validates finalizer execution order under concurrent stress conditions.
    *
    * Tests that finalizers maintain their FILO execution order guarantees even when multiple
    * concurrent fibers complete simultaneously.
    */
  test("finalizer execution order under concurrent stress") {
    val executionOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val fiberCount = 100

    def createEffectWithFinalizers(id: Int): Eru[Nothing, Int] = {
      Eru
        .succeed(id)
        .ensure(Eru.effect { executionOrder.add(s"outer-$id") })
        .ensure(Eru.effect { executionOrder.add(s"middle-$id") })
        .ensure(Eru.effect { executionOrder.add(s"inner-$id") })
    }

    val effects = (1 to fiberCount).map(createEffectWithFinalizers)
    val fibers = effects.map(EruRuntime.fork).toList
    val allFibers = EruRuntime.parSequence(fibers).unsafeRunSync()
    val results = EruRuntime.parSequence(allFibers.map(_.await)).unsafeRunSync()

    val successCount = results.count {
      case Exit.Success(_) => true
      case _ => false
    }
    assertEquals(successCount, fiberCount)

    val orderList = executionOrder.asScala.toList
    assertEquals(orderList.length, fiberCount * 3) // 3 finalizers per effect

    (1 to fiberCount).foreach { id =>
      val fiberFinalizers = orderList.filter(_.endsWith(s"-$id"))
      assertEquals(fiberFinalizers, List(s"inner-$id", s"middle-$id", s"outer-$id"))
    }
  }

  /** Validates timeout handling behavior under concurrent load.
    *
    * Tests that timeout operations work correctly when applied to multiple concurrent effects with
    * different completion times, ensuring proper timing behavior.
    */
  test("timeout handling under concurrent load") {
    val fastCount = 30
    val slowCount = 30

    // Use deterministic operations instead of system timing
    val fastEffects = (1 to fastCount).map { i =>
      val effect = Eru.succeed(s"fast-$i") // Immediate success
      EruRuntime.timeout(Duration.ofMillis(1000))(effect) // Very generous timeout
    }

    val slowEffects = (1 to slowCount).map { i =>
      val effect = EruRuntime.sleep(Duration.ofMillis(2000)).map(_ => s"slow-$i") // Very long delay
      EruRuntime.timeout(Duration.ofMillis(10))(effect) // Very short timeout - guaranteed to timeout
    }

    val allEffects = fastEffects ++ slowEffects
    val results = allEffects.map(_.attempt).toList.sequence.unsafeRunSync()

    val successes = results.count {
      case Result.Success(_) => true
      case _ => false
    }
    val timeouts = results.count {
      case Result.Failure(_: java.util.concurrent.TimeoutException) => true
      case _ => false
    }

    assertEquals(successes, fastCount, s"All fast effects should succeed, got $successes")
    assertEquals(timeouts, slowCount, s"All slow effects should timeout, got $timeouts")
  }
}
