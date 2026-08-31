package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Direct tests for `Eru.defer`.
  *
  * `defer` captures its argument by-name: the effect is constructed when the returned effect
  * executes, once per execution. This is the mechanism that makes `Eru.forever` construction-safe
  * and the public combinator users need for recursive definitions and deferred construction.
  * Recursive construction through `defer` is constant-depth on the JVM stack, and the run is
  * interpreter-stack-safe.
  */
class EruDeferSpec extends munit.FunSuite {

  test("defer does not construct the effect until execution") {
    var constructions = 0
    val deferred = Eru.defer {
      constructions += 1
      Eru.succeed(42)
    }

    assertEquals(constructions, 0, "the argument must not be evaluated at construction")
    assertEquals(deferred.unsafeRunSync(), 42)
    assertEquals(constructions, 1)
  }

  test("defer constructs the effect once per execution") {
    var constructions = 0
    val deferred = Eru.defer {
      constructions += 1
      Eru.succeed(constructions)
    }

    assertEquals(deferred.unsafeRunSync(), 1)
    assertEquals(deferred.unsafeRunSync(), 2)
    assertEquals(constructions, 2)
  }

  test("defer makes terminating recursion construction-safe") {
    def countdown(n: Int): Eru[Nothing, Int] =
      if (n <= 0) Eru.succeed(0)
      else Eru.defer(countdown(n - 1))

    assertEquals(countdown(100000).unsafeRunSync(), 0)
  }

  test("defer preserves failure semantics") {
    var constructions = 0
    val deferred = Eru.defer {
      constructions += 1
      Eru.fail("boom")
    }

    deferred.attempt.unsafeRunSync() match {
      case Result.Failure("boom") => ()
      case other => fail(s"Expected the typed failure, got: $other")
    }
    assertEquals(constructions, 1)
  }

  test("defer observes the context at execution time, not construction time") {
    var marker = "before"
    val deferred = Eru.defer(Eru.succeed(marker))
    marker = "after"

    assertEquals(deferred.unsafeRunSync(), "after")
  }
}
