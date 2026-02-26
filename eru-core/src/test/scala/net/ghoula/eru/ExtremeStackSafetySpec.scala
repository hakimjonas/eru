package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Extreme stack safety tests for optimized collection methods.
  *
  * Tests the optimized collection methods (foreach, foreachDiscard, collectAll, traverse, sequence)
  * with very large collections to ensure no stack overflow occurs even under extreme conditions.
  */
class ExtremeStackSafetySpec extends munit.FunSuite {

  test("foreach handles extremely large collections without stack overflow") {
    val extremeSize = 100000 // 100K elements
    val largeList = (1.to(extremeSize)).toList

    val result = Eru.foreach(largeList)(i => Eru.succeed(i * 2)).unsafeRunSync()

    assertEquals(result.size, extremeSize)
    assertEquals(result.head, 2) // First element: 1 * 2
    assertEquals(result.last, extremeSize * 2) // Last element
  }

  test("foreachDiscard handles extremely large collections without stack overflow") {
    val extremeSize = 100000 // 100K elements
    val largeList = (1.to(extremeSize)).toList
    var counter = 0

    val result = Eru
      .foreachDiscard(largeList) { i =>
        Eru.effect { counter += 1; i }
      }
      .unsafeRunSync()

    assertEquals(result, ())
    assertEquals(counter, extremeSize)
  }

  test("collectAll handles extremely large effect collections without stack overflow") {
    val extremeSize = 100000 // 100K elements
    val largeEffects = (1.to(extremeSize)).map(i => Eru.succeed(i * 3)).toList

    val result = Eru.collectAll(largeEffects).unsafeRunSync()

    assertEquals(result.size, extremeSize)
    assertEquals(result.head, 3) // First element: 1 * 3
    assertEquals(result.last, extremeSize * 3) // Last element
  }

  test("traverse handles extremely large collections without stack overflow") {
    val extremeSize = 100000 // 100K elements
    val largeList = (1.to(extremeSize)).toList

    val result = Eru.traverse(largeList)(i => Eru.succeed(i * 4)).unsafeRunSync()

    assertEquals(result.size, extremeSize)
    assertEquals(result.head, 4) // First element: 1 * 4
    assertEquals(result.last, extremeSize * 4) // Last element
  }

  test("sequence handles extremely large effect collections without stack overflow") {
    val extremeSize = 100000 // 100K elements
    val largeEffects = (1.to(extremeSize)).map(i => Eru.succeed(i * 5)).toList

    val result = Eru.sequence(largeEffects).unsafeRunSync()

    assertEquals(result.size, extremeSize)
    assertEquals(result.head, 5) // First element: 1 * 5
    assertEquals(result.last, extremeSize * 5) // Last element
  }

  test("nested collection operations maintain stack safety") {
    val outerSize = 1000 // 1K outer
    val innerSize = 100 // 100 inner = 100K total operations

    val result = Eru
      .foreach((1.to(outerSize)).toList) { outer =>
        Eru.traverse((1.to(innerSize)).toList)(inner => Eru.succeed(outer * 1000 + inner))
      }
      .unsafeRunSync()

    assertEquals(result.size, outerSize)
    assertEquals(result.head.size, innerSize)
    assertEquals(result.head.head, 1001) // First outer (1) * 1000 + first inner (1)
    assertEquals(result.last.last, outerSize * 1000 + innerSize) // Last element
  }

  test("mixed pattern with collectAll and traverse at extreme scale") {
    val size = 50000 // 50K elements
    val effects = (1
      .to(size))
      .map { i =>
        if (i % 2 == 0) Eru.succeed(i)
        else Eru.succeed(i * 10)
      }
      .toList

    val result = Eru
      .collectAll(effects)
      .flatMap { numbers =>
        Eru.traverse(numbers)(n => Eru.succeed(n + 1))
      }
      .unsafeRunSync()

    assertEquals(result.size, size)
    assertEquals(result.head, 11) // First: 1 * 10 + 1 = 11
    assertEquals(result(1), 3) // Second: 2 + 1 = 3
  }

  test("error propagation works correctly at extreme scale") {
    val size = 50000 // 50K elements
    val errorIndex = 25000 // Error in the middle

    val effects = (1
      .to(size))
      .map { i =>
        if (i == errorIndex) Eru.fail(s"Error at $i")
        else Eru.succeed(i)
      }
      .toList

    val result = Eru.collectAll(effects).attempt.unsafeRunSync()

    result match {
      case Result.Failure(error) => assertEquals(error, s"Error at $errorIndex")
      case Result.Success(_) => fail("Expected failure but got success")
    }
  }

  test("effectful recursive flatMap loop completes without closure accumulation") {
    // This exercises the TailCalls interpreter path (via Eru.effect), unlike Eru.succeed
    // which triggers flatMap's eager evaluation and bypasses the interpreter entirely.
    // Without the Step(f, End()) optimization, TailRec.Cont nodes accumulate and cause OOM.
    def effectfulLoop(n: Int): Eru[Throwable, Int] =
      Eru.effect(n).flatMap { current =>
        if (current <= 0) Eru.succeed(0)
        else effectfulLoop(current - 1)
      }

    val result = effectfulLoop(100_000).unsafeRunSync()
    assertEquals(result, 0)
  }

  test("extremely deep chaining with collection methods") {
    val chainDepth = 1000 // 1K chain depth

    def buildChain(depth: Int, acc: Eru[String, List[Int]]): Eru[String, List[Int]] = {
      if (depth <= 0) acc
      else {
        val nextAcc = acc.flatMap { list =>
          Eru.traverse(list)(i => Eru.succeed(i + 1))
        }
        buildChain(depth - 1, nextAcc)
      }
    }

    val result = buildChain(chainDepth, Eru.succeed(List(1, 2, 3))).unsafeRunSync()

    assertEquals(result.size, 3)
    assertEquals(result, List(chainDepth + 1, chainDepth + 2, chainDepth + 3))
  }
}
