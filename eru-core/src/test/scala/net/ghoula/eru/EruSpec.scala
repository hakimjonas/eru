package net.ghoula.eru

import munit.FunSuite

class EruSpec extends FunSuite {

  test("Eru.succeed creates a Succeed with the given value") {
    val eru = Eru.succeed(42)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.effect creates an Effect with the given computation") {
    val eru = Eru.effect(42)
    assertEquals(eru.unsafeRunSync(), 42)
  }

  test("Eru.succeed is eager - it evaluates its argument immediately") {
    var counter = 0
    val eru = Eru.succeed {
      counter += 1
      42
    }
    assertEquals(counter, 1, "Value should be evaluated immediately for Eru.succeed")
    assertEquals(eru.unsafeRunSync(), 42)
    assertEquals(counter, 1, "Value should only be evaluated once")
  }

  test("Eru.effect is lazy - does not execute computation immediately") {
    var counter = 0
    val eru = Eru.effect {
      counter += 1
      42
    }
    assertEquals(counter, 0, "Computation should not be executed when creating Eru.effect")
    assertEquals(eru.unsafeRunSync(), 42)
    assertEquals(counter, 1, "Computation should be executed exactly once when running")
  }

  test("map transforms values lazily") {
    var mapCounter = 0
    var effectCounter = 0

    val eru = Eru.effect {
      effectCounter += 1
      10
    }.map { x =>
      mapCounter += 1
      x * 2
    }

    assertEquals(effectCounter, 0, "Original effect should not be executed when mapping")
    assertEquals(mapCounter, 0, "Map function should not be executed when mapping")

    val result = eru.unsafeRunSync()
    assertEquals(result, 20)
    assertEquals(effectCounter, 1, "Original effect should be executed exactly once")
    assertEquals(mapCounter, 1, "Map function should be executed exactly once")
  }

  test("map on Succeed transforms values correctly") {
    val eru = Eru.succeed(5).map(_ * 3)
    assertEquals(eru.unsafeRunSync(), 15)
  }

  test("flatMap chains computations lazily") {
    var firstCounter = 0
    var secondCounter = 0
    var flatMapCounter = 0

    val eru = Eru.effect {
      firstCounter += 1
      10
    }.flatMap { x =>
      flatMapCounter += 1
      Eru.effect {
        secondCounter += 1
        x * 2
      }
    }

    assertEquals(firstCounter, 0, "First computation should not be executed when chaining")
    assertEquals(secondCounter, 0, "Second computation should not be executed when chaining")
    assertEquals(flatMapCounter, 0, "FlatMap function should not be executed when chaining")

    val result = eru.unsafeRunSync()
    assertEquals(result, 20)
    assertEquals(firstCounter, 1, "First computation should be executed exactly once")
    assertEquals(secondCounter, 1, "Second computation should be executed exactly once")
    assertEquals(flatMapCounter, 1, "FlatMap function should be executed exactly once")
  }

  test("flatMap with Succeed chains correctly") {
    val eru = Eru.succeed(5).flatMap(x => Eru.effect(x * 2))
    assertEquals(eru.unsafeRunSync(), 10)
  }

  test("complex chaining with map and flatMap") {
    val eru = Eru
      .succeed(5)
      .map(_ * 2)
      .flatMap(x => Eru.succeed(x + 3))
      .map(_ * 2)

    assertEquals(eru.unsafeRunSync(), 26)
  }

  test("complex chaining with Effects") {
    val eru = Eru
      .effect(5)
      .map(_ * 2)
      .flatMap(x => Eru.effect(x + 3))
      .map(_ * 2)

    assertEquals(eru.unsafeRunSync(), 26)
  }

  test("unsafeRunSync handles nested flatMap correctly") {
    val eru = Eru
      .succeed(1)
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))
      .flatMap(x => Eru.succeed(x + 1))

    assertEquals(eru.unsafeRunSync(), 4)
  }

  test("stack safety with large number of flatMap chains") {
    val chainSize = 10000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.flatMap(x => Eru.succeed(x + 1))
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("stack safety with large number of map chains") {
    val chainSize = 10000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, _) =>
      acc.map(_ + 1)
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("stack safety with mixed map and flatMap chains") {
    val chainSize = 5000

    val eru = (1 to chainSize).foldLeft(Eru.succeed(0)) { (acc, i) =>
      if (i % 2 == 0) {
        acc.map(_ + 1)
      } else {
        acc.flatMap(x => Eru.succeed(x + 1))
      }
    }

    assertEquals(eru.unsafeRunSync(), chainSize)
  }

  test("Eru is covariant in success type") {
    val stringValue: Eru[String] = Eru.succeed("value")
    val anyValue: Eru[Any] = stringValue
    assertEquals(anyValue.unsafeRunSync(), "value")
  }

  test("map preserves type covariance") {
    val intEru: Eru[Int] = Eru.succeed(42)
    val stringEru: Eru[String] = intEru.map(_.toString)
    assertEquals(stringEru.unsafeRunSync(), "42")
  }

  test("flatMap maintains type safety") {
    val eru: Eru[String] = Eru.succeed(5).flatMap(x => Eru.succeed(x.toString))
    assertEquals(eru.unsafeRunSync(), "5")
  }

  test("effect with side effects executes correctly") {
    var sideEffectCounter = 0
    val eru = Eru.effect {
      sideEffectCounter += 1
      sideEffectCounter
    }

    assertEquals(sideEffectCounter, 0, "Side effect should not execute until run")
    val result = eru.unsafeRunSync()
    assertEquals(result, 1)
    assertEquals(sideEffectCounter, 1, "Side effect should execute exactly once")
  }

  test("multiple runs of the same Eru execute independently") {
    var counter = 0
    val eru = Eru.effect {
      counter += 1
      counter
    }

    assertEquals(eru.unsafeRunSync(), 1)
    assertEquals(eru.unsafeRunSync(), 2)
    assertEquals(eru.unsafeRunSync(), 3)
  }

  test("succeed with Unit type") {
    val eru = Eru.succeed(())
    assertEquals(eru.unsafeRunSync(), ())
  }

  test("effect with Unit type") {
    var executed = false
    val eru = Eru.effect {
      executed = true
    }

    assert(!executed, "Effect should not execute until run")
    eru.unsafeRunSync()
    assert(executed, "Effect should have executed")
  }

  test("deeply nested Chain structure executes correctly") {
    val eru = Eru
      .succeed(0)
      .flatMap(x => Eru.succeed(x + 1).flatMap(y => Eru.succeed(y + 1)))
      .flatMap(x => Eru.succeed(x + 1).flatMap(y => Eru.succeed(y + 1)))

    assertEquals(eru.unsafeRunSync(), 4)
  }

  test("exception in effect computation is thrown on unsafeRunSync") {
    val eru = Eru.effect(throw new RuntimeException("test error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("exception in map function is thrown on unsafeRunSync") {
    val eru = Eru.succeed(42).map(_ => throw new RuntimeException("map error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }

  test("exception in flatMap function is thrown on unsafeRunSync") {
    val eru = Eru.succeed(42).flatMap(_ => throw new RuntimeException("flatMap error"))

    intercept[RuntimeException] {
      eru.unsafeRunSync()
    }
  }
}
