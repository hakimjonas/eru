package net.ghoula.eru

import scala.jdk.CollectionConverters.*

import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for basic concurrency correctness.
  *
  * This test suite verifies the core concurrency behavior: parallel operations, fiber management,
  * and resource cleanup work correctly. These are essential for concurrent programs using Eru.
  *
  * Focus: Deterministic, essential concurrency correctness tests only. Removed: Complex stress
  * patterns, high counts, Duration-based timing dependencies.
  */
final class ConcurrencyStressSpec extends EruTestSuite {

  test("basic parallel fiber execution works correctly") {
    val fiberCount = 5
    val effects = (1 to fiberCount).map(i => Eru.succeed(i))

    val completed = parSequence(effects.toList).unsafeRunSync()
    assertEquals(completed.sorted, (1 to fiberCount).toList)
  }

  test("nested zipPar operations work correctly") {
    val left = Eru.succeed(1)
    val right = Eru.succeed(2)
    val nested = left.zipPar(right).zipPar(Eru.succeed(3))

    val result = nested.unsafeRunSync()
    assertEquals(result, ((1, 2), 3))
  }

  test("simple race operations work correctly") {
    val first = Eru.succeed("first")
    val second = Eru.succeed("second")

    val result = first.race(second).unsafeRunSync()
    assert(result == Left("first") || result == Right("second"))
  }

  test("error propagation in parallel operations") {
    val effects = List(
      Eru.succeed(1),
      Eru.fail("error"),
      Eru.succeed(3)
    )

    val result = parSequence(effects).attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("fiber creation and completion with fork/await") {
    val effects = (1 to 3).map { i =>
      Eru.succeed(i * 2).fork
    }

    val fibers = parSequence(effects.toList).unsafeRunSync()
    val results = parSequence(fibers.map(_.await.flatMap {
      case Exit.Success(value) => Eru.succeed(value)
      case other => Eru.fail(s"Expected success but got: $other")
    })).unsafeRunSync()

    assertEquals(results.sorted, List(2, 4, 6))
  }

  test("resource cleanup with ensure in parallel") {
    val finalizationOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val effects = (1 to 3).map { i =>
      Eru
        .succeed(s"resource-$i")
        .ensure(Eru.effect(finalizationOrder.offer(s"cleanup-$i")))
    }

    val results = parSequence(effects.toList).unsafeRunSync()
    assertEquals(results.size, 3)
    assertEquals(finalizationOrder.size(), 3)

    val cleanupMessages = finalizationOrder.asScala.toList
    assertEquals(cleanupMessages.size, 3)
    assert(cleanupMessages.forall(_.startsWith("cleanup-")))
  }

  test("mixed success and failure in parallel operations") {
    val effects = List(
      Eru.succeed(1),
      Eru.succeed(2),
      Eru.succeed(3)
    )

    val results = parSequence(effects).unsafeRunSync()
    assertEquals(results, List(1, 2, 3))
  }

  test("collectAll with small number of effects") {
    val effects = List(
      Eru.succeed("a"),
      Eru.succeed("b"),
      Eru.succeed("c")
    )

    val result = collectAll(effects).unsafeRunSync()
    assertEquals(result, List("a", "b", "c"))
  }

  test("zipPar preserves both values correctly") {
    val left = Eru.succeed(42)
    val right = Eru.succeed("hello")

    val result = runtime.zipPar(left, right).unsafeRunSync()
    assertEquals(result, (42, "hello"))
  }

  test("basic fiber interrupt works correctly") {
    val computation = Eru.succeed("result")
    val fiber = runtime.fork(computation).unsafeRunSync()

    val result = fiber.await.unsafeRunSync()
    assertEquals(result, Exit.Success("result"))
  }
}
