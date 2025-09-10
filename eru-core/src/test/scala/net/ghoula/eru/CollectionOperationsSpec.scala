package net.ghoula.eru

class CollectionOperationsSpec extends munit.FunSuite {

  test("foreachDiscard executes function for each element and discards results") {
    val result = Eru.foreachDiscard(1 to 5)(i => Eru.succeed(i * 2)).unsafeRunSync()
    assertEquals(result, ())
  }

  test("foreachDiscard with empty collection succeeds immediately") {
    val result = Eru.foreachDiscard(List.empty[Int])(i => Eru.succeed(i)).unsafeRunSync()
    assertEquals(result, ())
  }

  test("foreachDiscard propagates first failure") {
    val effect = Eru.foreachDiscard(1 to 5) { i =>
      if (i == 3) Eru.fail("error") else Eru.succeed(i)
    }

    val result = effect.attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("foreach executes function for each element and collects results") {
    val result = Eru.foreach(1 to 5)(i => Eru.succeed(i * 2)).unsafeRunSync()
    assertEquals(result, List(2, 4, 6, 8, 10))
  }

  test("foreach with empty collection returns empty list") {
    val result = Eru.foreach(List.empty[Int])(i => Eru.succeed(i)).unsafeRunSync()
    assertEquals(result, List.empty[Int])
  }

  test("foreach propagates first failure") {
    val effect = Eru.foreach(1 to 5) { i =>
      if (i == 3) Eru.fail("error") else Eru.succeed(i * 2)
    }

    val result = effect.attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("collectAll executes all effects and collects results") {
    val effects = (1 to 5).map(i => Eru.succeed(i * 2))
    val result = Eru.collectAll(effects).unsafeRunSync()
    assertEquals(result, List(2, 4, 6, 8, 10))
  }

  test("collectAll with empty collection returns empty list") {
    val effects = List.empty[Eru[Nothing, Int]]
    val result = Eru.collectAll(effects).unsafeRunSync()
    assertEquals(result, List.empty[Int])
  }

  test("collectAllDiscard executes all effects and discards results") {
    val effects = (1 to 5).map(i => Eru.succeed(i * 2))
    val result = Eru.collectAllDiscard(effects).unsafeRunSync()
    assertEquals(result, ())
  }

  test("foldLeft reduces collection from left to right") {
    val result = Eru.foldLeft(1 to 5)(0)((acc, i) => Eru.succeed(acc + i)).unsafeRunSync()
    assertEquals(result, 15) // 0 + 1 + 2 + 3 + 4 + 5
  }

  test("foldLeft with empty collection returns initial value") {
    val result = Eru.foldLeft(List.empty[Int])(42)((acc, i) => Eru.succeed(acc + i)).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("foldLeft propagates failure") {
    val effect = Eru.foldLeft(1 to 5)(0) { (acc, i) =>
      if (i == 3) Eru.fail("error") else Eru.succeed(acc + i)
    }

    val result = effect.attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("foldRight reduces collection from right to left") {
    val result = Eru.foldRight(1 to 5)(0)((i, acc) => Eru.succeed(i + acc)).unsafeRunSync()
    assertEquals(result, 15) // 1 + (2 + (3 + (4 + (5 + 0))))
  }

  test("foldRight with empty collection returns initial value") {
    val result = Eru.foldRight(List.empty[Int])(42)((i, acc) => Eru.succeed(i + acc)).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("collection operations work with different collection types") {
    val vector = Vector(1, 2, 3)
    val set = Set(1, 2, 3)
    val array = Array(1, 2, 3)

    val vectorResult = Eru.foreach(vector)(Eru.succeed).unsafeRunSync()
    val setResult = Eru.foreach(set)(Eru.succeed).unsafeRunSync()
    val arrayResult = Eru.foreach(array)(Eru.succeed).unsafeRunSync()

    assertEquals(vectorResult, List(1, 2, 3))
    assertEquals(setResult.toSet, Set(1, 2, 3))
    assertEquals(arrayResult, List(1, 2, 3))
  }

  test("collection operations maintain order") {
    val input = 1 to 10
    val result = Eru.foreach(input)(i => Eru.succeed(i)).unsafeRunSync()
    assertEquals(result, input.toList)
  }

  test("nested collection operations work correctly") {
    val result = Eru
      .foreach(1 to 3) { i =>
        Eru.foreach(1 to i)(j => Eru.succeed(i * j))
      }
      .unsafeRunSync()

    val expected = List(
      List(1), // i=1: [1*1]
      List(2, 4), // i=2: [2*1, 2*2]
      List(3, 6, 9) // i=3: [3*1, 3*2, 3*3]
    )
    assertEquals(result, expected)
  }
}
