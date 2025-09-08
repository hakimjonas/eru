package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Property-based tests for fiber operations.
  *
  * Tests that fork/await operations are referentially transparent and preserve fundamental
  * properties like monad laws in the unified fiber runtime.
  */
class FiberPropertySpec extends FunSuite {

  test("fork/await is referentially transparent") {
    val computation = Eru.succeed(42)

    // Direct execution
    val directResult = computation.unsafeRunSync()

    // Fork/await execution
    val fiberResult = for {
      fiber <- EruRuntime.fork(computation)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val forkedResult = fiberResult.unsafeRunSync()

    assertEquals(directResult, forkedResult)
  }

  test("fork preserves referential transparency for pure values") {
    val value = "pure value"
    val pureEffect = Eru.succeed(value)

    // Multiple forks of the same pure computation should behave identically
    val fiber1 = EruRuntime.fork(pureEffect).unsafeRunSync()
    val fiber2 = EruRuntime.fork(pureEffect).unsafeRunSync()
    val fiber3 = EruRuntime.fork(pureEffect).unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()
    val exit3 = fiber3.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit2, exit3)
    assertEquals(exit1, Exit.Success(value))
  }

  test("fork preserves referential transparency for failures") {
    val error = "test error"
    val failingEffect = Eru.fail(error)

    val fiber1 = EruRuntime.fork(failingEffect).unsafeRunSync()
    val fiber2 = EruRuntime.fork(failingEffect).unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit1, Exit.Failure(error))
  }

  test("fork/await preserves monad left identity law") {
    // Left identity: pure(a).flatMap(f) == f(a)
    val a = 42
    val f: Int => Eru[Nothing, String] = x => Eru.succeed(s"value: $x")

    // Direct application
    val direct = f(a).unsafeRunSync()

    // Through fiber
    val throughFiber = for {
      fiber <- EruRuntime.fork(Eru.succeed(a))
      exit <- fiber.await
      value <- Eru.fromExit(exit)
      result <- f(value)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
  }

  test("fork/await preserves monad right identity law") {
    // Right identity: m.flatMap(pure) == m
    val m = Eru.succeed("test value")

    // Direct execution
    val direct = m.unsafeRunSync()

    // Through fiber with identity
    val throughFiber = for {
      fiber <- EruRuntime.fork(m)
      exit <- fiber.await
      result <- Eru.fromExit(exit).flatMap(Eru.succeed)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
  }

  test("fork/await preserves monad associativity law") {
    // Associativity: (m.flatMap(f)).flatMap(g) == m.flatMap(x => f(x).flatMap(g))
    val m = Eru.succeed(10)
    val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x * 2)
    val g: Int => Eru[Nothing, String] = x => Eru.succeed(s"result: $x")

    // Left-associated through fiber
    val leftAssoc = for {
      fiber1 <- EruRuntime.fork(m)
      exit1 <- fiber1.await
      value1 <- Eru.fromExit(exit1)
      intermediateEffect = f(value1)
      fiber2 <- EruRuntime.fork(intermediateEffect)
      exit2 <- fiber2.await
      value2 <- Eru.fromExit(exit2)
      result <- g(value2)
    } yield result

    // Right-associated through fiber
    val rightAssoc = for {
      fiber <- EruRuntime.fork(m)
      exit <- fiber.await
      value <- Eru.fromExit(exit)
      result <- f(value).flatMap(g)
    } yield result

    val leftResult = leftAssoc.unsafeRunSync()
    val rightResult = rightAssoc.unsafeRunSync()

    assertEquals(leftResult, rightResult)
    assertEquals(leftResult, "result: 20")
  }

  test("fork is deterministic for pure computations") {
    val computation = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.succeed(12)
    } yield a + b + c

    // Run the same computation through fiber multiple times
    val runs = (1 to 10).map { _ =>
      val fiber = EruRuntime.fork(computation).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()
      exit match {
        case Exit.Success(value) => value
        case other => fail(s"Expected success but got $other")
      }
    }

    // All runs should produce the same result
    assert(runs.forall(_ == 42))
    assertEquals(runs.toSet.size, 1) // All identical
  }

  test("fork preserves error types accurately") {
    sealed trait CustomError
    case object ErrorA extends CustomError
    case object ErrorB extends CustomError

    val errorEffect: Eru[CustomError, String] = Eru.fail(ErrorA)

    val fiberResult = for {
      fiber <- EruRuntime.fork(errorEffect)
      exit <- fiber.await
    } yield exit

    val result = fiberResult.unsafeRunSync()

    result match {
      case Exit.Failure(ErrorA) => // Expected
      case other => fail(s"Expected Failure(ErrorA) but got $other")
    }
  }

  test("fork/await composition is associative") {
    // ((a fork/await) flatMap f) fork/await should behave the same as
    // (a fork/await) flatMap (x => (f(x) fork/await))

    val a = Eru.succeed(5)
    val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x * x)

    def forkAwait[E, A](effect: Eru[E, A]): Eru[E | Throwable, A] = for {
      fiber <- EruRuntime.fork(effect)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    // First approach: fork/await then flatMap then fork/await
    val approach1 = for {
      step1 <- forkAwait(a)
      step2Effect = f(step1)
      step2 <- step2Effect
      result <- forkAwait(Eru.succeed(step2))
    } yield result

    // Second approach: fork/await then flatMap with inner fork/await
    val approach2 = for {
      step1 <- forkAwait(a)
      step2Effect = f(step1)
      result <- forkAwait(step2Effect)
    } yield result

    val result1 = approach1.unsafeRunSync()
    val result2 = approach2.unsafeRunSync()

    assertEquals(result1, result2)
    assertEquals(result1, 25) // 5 * 5
  }

  test("fork does not affect external side effects ordering within a computation") {
    import scala.collection.mutable
    val events = mutable.ListBuffer.empty[String]

    val computation = for {
      _ <- Eru.effect(events += "step1")
      _ <- Eru.effect(events += "step2")
      _ <- Eru.effect(events += "step3")
    } yield "done"

    // Execute through fiber
    val fiber = EruRuntime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success("done"))
    assertEquals(events.toList, List("step1", "step2", "step3"))
  }

  test("multiple fork/await operations maintain independence") {
    import scala.collection.mutable
    val events1 = mutable.ListBuffer.empty[String]
    val events2 = mutable.ListBuffer.empty[String]

    val computation1 = for {
      _ <- Eru.effect(events1 += "comp1-step1")
      _ <- Eru.effect(events1 += "comp1-step2")
    } yield "comp1-done"

    val computation2 = for {
      _ <- Eru.effect(events2 += "comp2-step1")
      _ <- Eru.effect(events2 += "comp2-step2")
    } yield "comp2-done"

    val parallelExecution = for {
      fiber1 <- EruRuntime.fork(computation1)
      fiber2 <- EruRuntime.fork(computation2)
      exit1 <- fiber1.await
      exit2 <- fiber2.await
      result1 <- Eru.fromExit(exit1)
      result2 <- Eru.fromExit(exit2)
    } yield (result1, result2)

    val (result1, result2) = parallelExecution.unsafeRunSync()

    assertEquals(result1, "comp1-done")
    assertEquals(result2, "comp2-done")
    assertEquals(events1.toList, List("comp1-step1", "comp1-step2"))
    assertEquals(events2.toList, List("comp2-step1", "comp2-step2"))
  }

  test("fiber identity: await(fork(a)) ≈ a for pure computations") {
    val values = List(42, "hello", List(1, 2, 3), Map("key" -> "value"))

    values.foreach { value =>
      val direct = Eru.succeed(value).unsafeRunSync()

      val throughFiber = for {
        fiber <- EruRuntime.fork(Eru.succeed(value))
        exit <- fiber.await
        result <- Eru.fromExit(exit)
      } yield result

      val fiberResult = throughFiber.unsafeRunSync()
      assertEquals(direct, fiberResult)
    }
  }

  test("fork/await preserves computation structure for complex effects") {
    case class User(id: Int, name: String)
    case class Order(userId: Int, amount: Double)

    val fetchUser: Int => Eru[String, User] = id =>
      if (id > 0) Eru.succeed(User(id, s"User$id"))
      else Eru.fail("Invalid user ID")

    val fetchOrders: Int => Eru[String, List[Order]] =
      userId => Eru.succeed(List(Order(userId, 100.0), Order(userId, 200.0)))

    val businessLogic = for {
      user <- fetchUser(1)
      orders <- fetchOrders(user.id)
      total = orders.map(_.amount).sum
    } yield (user, total)

    // Direct execution
    val direct = businessLogic.unsafeRunSync()

    // Through fiber
    val throughFiber = for {
      fiber <- EruRuntime.fork(businessLogic)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
    assertEquals(fiberResult._1.name, "User1")
    assertEquals(fiberResult._2, 300.0)
  }
}
