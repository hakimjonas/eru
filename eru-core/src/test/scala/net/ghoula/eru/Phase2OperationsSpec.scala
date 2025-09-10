package net.ghoula.eru

class Phase2OperationsSpec extends munit.FunSuite {

  // =============================================================================
  // Conditional Construction Tests
  // =============================================================================

  test("when executes effect when condition is true") {
    var executed = false
    val effect = Eru.when(true)(Eru.effect { executed = true })
    effect.unsafeRunSync()
    assertEquals(executed, true)
  }

  test("when skips effect when condition is false") {
    var executed = false
    val effect = Eru.when(false)(Eru.effect { executed = true })
    effect.unsafeRunSync()
    assertEquals(executed, false)
  }

  test("unless skips effect when condition is true") {
    var executed = false
    val effect = Eru.unless(true)(Eru.effect { executed = true })
    effect.unsafeRunSync()
    assertEquals(executed, false)
  }

  test("unless executes effect when condition is false") {
    var executed = false
    val effect = Eru.unless(false)(Eru.effect { executed = true })
    effect.unsafeRunSync()
    assertEquals(executed, true)
  }

  test("cond returns first value when condition is true") {
    val result = Eru.cond(true, "yes", "no").unsafeRunSync()
    assertEquals(result, "yes")
  }

  test("cond returns second value when condition is false") {
    val result = Eru.cond(false, "yes", "no").unsafeRunSync()
    assertEquals(result, "no")
  }

  test("conditional operations propagate errors") {
    val failingEffect = Eru.fail("error")
    val whenResult = Eru.when(true)(failingEffect).attempt.unsafeRunSync()
    val unlessResult = Eru.unless(false)(failingEffect).attempt.unsafeRunSync()

    assertEquals(whenResult, Result.Failure("error"))
    assertEquals(unlessResult, Result.Failure("error"))
  }

  // =============================================================================
  // Looping Constructs Tests
  // =============================================================================

  test("iterate terminates when predicate is satisfied") {
    val result = Eru.iterate(0)(x => Eru.succeed(x + 1))(_ >= 5).unsafeRunSync()
    assertEquals(result, 5)
  }

  test("iterate executes multiple iterations") {
    var count = 0
    val result = Eru
      .iterate(0) { x =>
        count += 1
        Eru.succeed(x + 1)
      }(_ >= 3)
      .unsafeRunSync()

    assertEquals(result, 3)
    assertEquals(count, 3)
  }

  test("iterate propagates errors") {
    val effect = Eru.iterate(0) { x =>
      if (x == 2) Eru.fail("error") else Eru.succeed(x + 1)
    }(_ >= 5)

    val result = effect.attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("repeatN executes effect exactly n times") {
    var count = 0
    val effect = Eru.repeatN(5)(Eru.effect { count += 1 })
    effect.unsafeRunSync()
    assertEquals(count, 5)
  }

  test("repeatN with zero repetitions does nothing") {
    var count = 0
    val effect = Eru.repeatN(0)(Eru.effect { count += 1 })
    effect.unsafeRunSync()
    assertEquals(count, 0)
  }

  test("repeatN propagates errors") {
    var count = 0
    val effect = Eru.repeatN(5)(Eru.effect {
      count += 1
      if (count == 3) throw new RuntimeException("error")
    })

    val result = effect.attempt.unsafeRunSync()
    assertEquals(count, 3)
    result match {
      case Result.Failure(_) => // expected
      case Result.Success(_) => fail("Expected failure but got success")
    }
  }

  test("repeatUntil repeats until predicate is satisfied") {
    var count = 0
    val result = Eru
      .repeatUntil(Eru.effect {
        count += 1
        count
      })(_ >= 5)
      .unsafeRunSync()

    assertEquals(result, 5)
    assertEquals(count, 5)
  }

  test("repeatUntil propagates errors") {
    var count = 0
    val effect = Eru.repeatUntil(Eru.effect {
      count += 1
      if (count == 3) throw new RuntimeException("error")
      count
    })(_ >= 10)

    val result = effect.attempt.unsafeRunSync()
    assertEquals(count, 3)
    result match {
      case Result.Failure(_) => // expected
      case Result.Success(_) => fail("Expected failure but got success")
    }
  }

  // =============================================================================
  // Tap Operations Tests
  // =============================================================================

  test("tap executes side effect on success without changing result") {
    var sideEffect = ""
    val result = Eru
      .succeed(42)
      .tap(x => Eru.effect { sideEffect = s"value: $x" })
      .unsafeRunSync()

    assertEquals(result, 42)
    assertEquals(sideEffect, "value: 42")
  }

  test("tap does not execute on failure") {
    var sideEffect = ""
    val result = Eru
      .fail("error")
      .tap(_ => Eru.effect { sideEffect = "executed" })
      .attempt
      .unsafeRunSync()

    assertEquals(result, Result.Failure("error"))
    assertEquals(sideEffect, "")
  }

  test("tap propagates errors from side effect") {
    val result = Eru
      .succeed(42)
      .tap(_ => Eru.fail("tap error"))
      .attempt
      .unsafeRunSync()

    assertEquals(result, Result.Failure("tap error"))
  }

  test("tapError executes side effect on failure without changing result") {
    var sideEffect = ""
    val result = Eru
      .fail("error")
      .tapError(e => Eru.succeed { sideEffect = s"error: $e" })
      .attempt
      .unsafeRunSync()

    assertEquals(result, Result.Failure("error"))
    assertEquals(sideEffect, "error: error")
  }

  test("tapError does not execute on success") {
    var sideEffect = ""
    val result = Eru
      .succeed(42)
      .tapError(_ => Eru.succeed { sideEffect = "executed" })
      .unsafeRunSync()

    assertEquals(result, 42)
    assertEquals(sideEffect, "")
  }

  test("tapBoth executes appropriate side effect") {
    var successEffect = ""
    var errorEffect = ""

    val successResult = Eru
      .succeed(42)
      .tapBoth(
        e => Eru.succeed { errorEffect = s"error: $e" },
        x => Eru.succeed { successEffect = s"success: $x" }
      )
      .unsafeRunSync()

    val errorResult = Eru
      .fail("error")
      .tapBoth(
        e => Eru.succeed { errorEffect = s"error: $e" },
        x => Eru.succeed { successEffect = s"success: $x" }
      )
      .attempt
      .unsafeRunSync()

    assertEquals(successResult, 42)
    assertEquals(successEffect, "success: 42")
    assertEquals(errorResult, Result.Failure("error"))
    assertEquals(errorEffect, "error: error")
  }

  // =============================================================================
  // Advanced Collection Operations Tests
  // =============================================================================

  test("filter collects only values satisfying predicate") {
    val effects = List(Eru.succeed(1), Eru.succeed(2), Eru.succeed(3), Eru.succeed(4))
    val result = Eru.filter(effects)(_ % 2 == 0).unsafeRunSync()
    assertEquals(result, List(2, 4))
  }

  test("filter with empty collection returns empty list") {
    val effects = List.empty[Eru[Nothing, Int]]
    val result = Eru.filter(effects)(_ > 0).unsafeRunSync()
    assertEquals(result, List.empty[Int])
  }

  test("filter propagates first failure") {
    val effects = List(Eru.succeed(1), Eru.fail("error"), Eru.succeed(3))
    val result = Eru.filter(effects)(_ > 0).attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  test("partition splits collection based on effectful predicate") {
    val items = List(1, 2, 3, 4, 5)
    val result = Eru.partition(items)(x => Eru.succeed(x % 2 == 0)).unsafeRunSync()
    assertEquals(result, (List(2, 4), List(1, 3, 5)))
  }

  test("partition with empty collection returns empty lists") {
    val items = List.empty[Int]
    val result = Eru.partition(items)(x => Eru.succeed(x > 0)).unsafeRunSync()
    assertEquals(result, (List.empty[Int], List.empty[Int]))
  }

  test("partition propagates errors from predicate") {
    val items = List(1, 2, 3)
    val effect = Eru.partition(items) { x =>
      if (x == 2) Eru.fail("error") else Eru.succeed(x % 2 == 0)
    }

    val result = effect.attempt.unsafeRunSync()
    assertEquals(result, Result.Failure("error"))
  }

  // =============================================================================
  // Integration Tests
  // =============================================================================

  test("Phase 2 operations compose correctly") {
    var log = List.empty[String]

    val result = Eru
      .when(true) {
        Eru.repeatN(3) {
          Eru
            .succeed("item")
            .tap(x => Eru.effect { log = s"processed: $x" :: log })
        }
      }
      .unsafeRunSync()

    assertEquals(result, ())
    assertEquals(log.length, 3)
    assert(log.forall(_.startsWith("processed:")))
  }

  test("nested looping with conditional execution") {
    val result = Eru
      .iterate(0) { acc =>
        Eru.foldLeft(1 to 3)(acc) { (currentAcc, i) =>
          Eru
            .when(i % 2 == 1)(Eru.unit)
            .map(_ => currentAcc + i)
        }
      }(_ >= 10)
      .unsafeRunSync()

    // Each iteration adds 1 + 3 = 4, so we need 3 iterations to get >= 10
    // 0 + 4 + 4 + 4 = 12
    assertEquals(result, 12)
  }

  test("error handling across Phase 2 operations") {
    val result = Eru.repeatUntil {
      Eru.foreach(1 to 3) { i =>
        Eru
          .when(i == 2)(Eru.fail("deliberate error"))
          .map(_ => i)
      }
    }(_ => false).attempt.unsafeRunSync()

    assertEquals(result, Result.Failure("deliberate error"))
  }
}
