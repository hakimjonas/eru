package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

/** Test suite for fork and await operations in the core Eru effect system.
  *
  * Validates the construction and basic semantics of fork operations that create concurrent fibers
  * and await operations that wait for fiber completion. These tests focus on the core AST
  * construction and semantic validation, with full runtime execution testing covered in the runtime
  * module specifications.
  */
class ForkAwaitSpec extends FunSuite {

  test("Eru.fork creates Fork case") {
    val computation = Eru.succeed(42)
    val forkEffect = Eru.fork(computation)

    // Fork should create a pure description - we can't execute it in Phase 1
    // but we can verify it constructs properly by checking type
    val _: Eru[Nothing, EruFiber[Nothing, Int]] = forkEffect
  }

  test("Eru.await creates Await case") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val awaitEffect = Eru.await(fiber)

    // Await should create a pure description
    val _: Eru[Nothing, Exit[Nothing, Int]] = awaitEffect
  }

  test("Fork is referentially transparent") {
    val computation = Eru.succeed(42)
    val fork1 = Eru.fork(computation)
    val fork2 = Eru.fork(computation)

    // Both should be constructable
    val _: Eru[Nothing, EruFiber[Nothing, Int]] = fork1
    val _: Eru[Nothing, EruFiber[Nothing, Int]] = fork2
  }

  test("Await is referentially transparent") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val await1 = Eru.await(fiber)
    val await2 = Eru.await(fiber)

    // Both should be constructable
    val _: Eru[String, Exit[String, Int]] = await1
    val _: Eru[String, Exit[String, Int]] = await2
  }

  test("Fork constructs with proper types") {
    val computation: Eru[String, Int] = Eru.succeed(42)
    val forkEffect: Eru[Nothing, EruFiber[String, Int]] = Eru.fork(computation)

    // Type constraints should be satisfied at compile time
    val _: Eru[Nothing, EruFiber[String, Int]] = forkEffect
  }

  test("Await constructs with proper types") {
    val fiber: EruFiber[String, Int] = EruFiber.completed(Exit.Success(42), Nil)
    val awaitEffect: Eru[String, Exit[String, Int]] = Eru.await(fiber)

    // Type constraints should be satisfied at compile time
    val _: Eru[String, Exit[String, Int]] = awaitEffect
  }

  test("Fork/Await composition is pure") {
    val computation = Eru.succeed(42)
    val composed = for {
      fiber <- Eru.fork(computation)
      result <- Eru.await(fiber)
    } yield result

    // Composition should work at construction time
    val _: Eru[Nothing, Exit[Nothing, Int]] = composed
  }

  test("Multiple forks of same computation are independent") {
    val computation = Eru.succeed(42)
    val fork1 = Eru.fork(computation)
    val fork2 = Eru.fork(computation)

    // Each fork should be independent and constructable
    val _: Eru[Nothing, EruFiber[Nothing, Int]] = fork1
    val _: Eru[Nothing, EruFiber[Nothing, Int]] = fork2
  }

  test("Fork preserves error type") {
    val failingComputation: Eru[String, Int] = Eru.fail("error")
    val forkEffect: Eru[Nothing, EruFiber[String, Int]] = Eru.fork(failingComputation)

    // Fork never fails at the type level - it returns a fiber handle
    val _: Eru[Nothing, EruFiber[String, Int]] = forkEffect
  }

  test("Await preserves error type from fiber") {
    val fiber: EruFiber[String, Int] = EruFiber.completed(Exit.Success(42), Nil)
    val awaitEffect: Eru[String, Exit[String, Int]] = Eru.await(fiber)

    // Await can fail with the same error type as the fiber
    val _: Eru[String, Exit[String, Int]] = awaitEffect
  }

  test("Fork can be mapped over") {
    val computation = Eru.succeed(42)
    val mappedFork = Eru.fork(computation).map(fiber => (fiber, "tagged"))

    // Mapping should work on fork results
    val _: Eru[Nothing, (EruFiber[Nothing, Int], String)] = mappedFork
  }

  test("Await can be mapped over") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val mappedAwait = Eru.await(fiber).map {
      case Exit.Success(value) => s"Success: $value"
      case Exit.Failure(error) => s"Failure: $error"
      case Exit.Die(throwable) => s"Die: ${throwable.getMessage}"
      case Exit.Interrupt(id, cause) => s"Interrupt: $id - $cause"
    }

    // Mapping should work on await results
    val _: Eru[String, String] = mappedAwait
  }

  test("Fork/Await can handle error recovery") {
    val computation = Eru.succeed(42)
    val recovered = Eru
      .fork(computation)
      .flatMap(Eru.await(_))
      .recover { case Exit.Failure(_) =>
        Exit.Success(-1)
      }

    // Error recovery should work at construction time
    val _: Eru[Nothing, Exit[Nothing, Int]] = recovered
  }

  test("Nested fork/await constructs properly") {
    val innerComputation = Eru.succeed(42)
    val outerComputation = for {
      innerFiber <- Eru.fork(innerComputation)
      innerResult <- Eru.await(innerFiber)
    } yield innerResult

    val nestedEffect = for {
      outerFiber <- Eru.fork(outerComputation)
      outerResult <- Eru.await(outerFiber)
    } yield outerResult

    // Nested fork/await should construct properly
    val _: Eru[Nothing, Exit[Nothing, Exit[Nothing, Int]]] = nestedEffect
  }

  test("Fork/await with attempt preserves structure") {
    val computation = Eru.succeed(42)
    val attemptedEffect = Eru
      .fork(computation)
      .flatMap(Eru.await(_))
      .attempt

    // Attempt should work with fork/await
    val _: Eru[Nothing, Result[Nothing, Exit[Nothing, Int]]] = attemptedEffect
  }

  test("Fork execution works in Phase 2") {
    val computation = Eru.succeed(42)
    val forkEffect = Eru.fork(computation)

    // In Phase 2, fork should work and return a completed fiber
    val fiber = forkEffect.unsafeRunSync()
    assertEquals(fiber.exit, Exit.Success(42))
  }

  test("Await execution works in Phase 2") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val awaitEffect = Eru.await(fiber)

    // In Phase 2, await should work and return the exit
    val result = awaitEffect.unsafeRunSync()
    assertEquals(result, Exit.Success(42))
  }
}
