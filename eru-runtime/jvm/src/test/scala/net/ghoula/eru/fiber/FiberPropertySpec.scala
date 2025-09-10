package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Property-based tests for fiber operations.
  *
  * Tests that fork/await operations are referentially transparent and preserve fundamental
  * properties like monad laws in the unified fiber runtime.
  */
class FiberPropertySpec extends TestWithRuntime {

  /** Validates that fork/await operations are referentially transparent.
    *
    * Tests that executing a computation directly produces the same result as forking it into a
    * fiber and awaiting the result.
    */
  test("fork/await is referentially transparent") {
    val computation = Eru.succeed(42)

    val directResult = computation.unsafeRunSync()

    val fiberResult = for {
      fiber <- runtime.fork(computation)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val forkedResult = fiberResult.unsafeRunSync()

    assertEquals(directResult, forkedResult)
  }

  /** Validates that fork preserves referential transparency for pure values.
    *
    * Tests that multiple forks of the same pure computation produce identical results,
    * demonstrating referential transparency.
    */
  test("fork preserves referential transparency for pure values") {
    val value = "pure value"
    val pureEffect = Eru.succeed(value)

    val fiber1 = runtime.fork(pureEffect).unsafeRunSync()
    val fiber2 = runtime.fork(pureEffect).unsafeRunSync()
    val fiber3 = runtime.fork(pureEffect).unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()
    val exit3 = fiber3.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit2, exit3)
    assertEquals(exit1, Exit.Success(value))
  }

  /** Validates that fork preserves referential transparency for failing computations.
    *
    * Tests that multiple forks of the same failing computation produce identical error results,
    * demonstrating referential transparency for failures.
    */
  test("fork preserves referential transparency for failures") {
    val error = "test error"
    val failingEffect = Eru.fail(error)

    val fiber1 = runtime.fork(failingEffect).unsafeRunSync()
    val fiber2 = runtime.fork(failingEffect).unsafeRunSync()

    val exit1 = fiber1.await.unsafeRunSync()
    val exit2 = fiber2.await.unsafeRunSync()

    assertEquals(exit1, exit2)
    assertEquals(exit1, Exit.Failure(error))
  }

  /** Validates that fork/await preserves the monad left identity law.
    *
    * Tests the mathematical property: pure(a).flatMap(f) == f(a) when executed through the
    * fork/await mechanism.
    */
  test("fork/await preserves monad left identity law") {
    val a = 42
    val f: Int => Eru[Nothing, String] = x => Eru.succeed(s"value: $x")

    val direct = f(a).unsafeRunSync()

    val throughFiber = for {
      fiber <- runtime.fork(Eru.succeed(a))
      exit <- fiber.await
      value <- Eru.fromExit(exit)
      result <- f(value)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
  }

  /** Validates that fork/await preserves the monad right identity law.
    *
    * Tests the mathematical property: m.flatMap(pure) == m when executed through the fork/await
    * mechanism.
    */
  test("fork/await preserves monad right identity law") {
    val m = Eru.succeed("test value")

    val direct = m.unsafeRunSync()

    val throughFiber = for {
      fiber <- runtime.fork(m)
      exit <- fiber.await
      result <- Eru.fromExit(exit).flatMap(Eru.succeed)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
  }

  /** Validates that fork/await preserves the monad associativity law.
    *
    * Tests the mathematical property: (m.flatMap(f)).flatMap(g) == m.flatMap(x => f(x).flatMap(g))
    * when executed through the fork/await mechanism.
    */
  test("fork/await preserves monad associativity law") {
    val m = Eru.succeed(10)
    val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x * 2)
    val g: Int => Eru[Nothing, String] = x => Eru.succeed(s"result: $x")

    val leftAssoc = for {
      fiber1 <- runtime.fork(m)
      exit1 <- fiber1.await
      value1 <- Eru.fromExit(exit1)
      intermediateEffect = f(value1)
      fiber2 <- runtime.fork(intermediateEffect)
      exit2 <- fiber2.await
      value2 <- Eru.fromExit(exit2)
      result <- g(value2)
    } yield result

    val rightAssoc = for {
      fiber <- runtime.fork(m)
      exit <- fiber.await
      value <- Eru.fromExit(exit)
      result <- f(value).flatMap(g)
    } yield result

    val leftResult = leftAssoc.unsafeRunSync()
    val rightResult = rightAssoc.unsafeRunSync()

    assertEquals(leftResult, rightResult)
    assertEquals(leftResult, "result: 20")
  }

  /** Validates that fork operations are deterministic for pure computations.
    *
    * Tests that running the same pure computation through fork multiple times produces identical
    * results consistently.
    */
  test("fork is deterministic for pure computations") {
    val computation = for {
      a <- Eru.succeed(10)
      b <- Eru.succeed(20)
      c <- Eru.succeed(12)
    } yield a + b + c

    val runs = (1 to 10).map { _ =>
      val fiber = runtime.fork(computation).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()
      exit match {
        case Exit.Success(value) => value
        case other => fail(s"Expected success but got $other")
      }
    }

    assert(runs.forall(_ == 42))
    assertEquals(runs.toSet.size, 1)
  }

  /** Validates that fork operations preserve error types accurately.
    *
    * Tests that custom error types are maintained correctly through the fork/await mechanism
    * without type erasure or corruption.
    */
  test("fork preserves error types accurately") {
    sealed trait CustomError
    case object ErrorA extends CustomError
    case object ErrorB extends CustomError

    val errorEffect: Eru[CustomError, String] = Eru.fail(ErrorA)

    val fiberResult = for {
      fiber <- runtime.fork(errorEffect)
      exit <- fiber.await
    } yield exit

    val result = fiberResult.unsafeRunSync()

    result match {
      case Exit.Failure(ErrorA) =>
      case other => fail(s"Expected Failure(ErrorA) but got $other")
    }
  }

  /** Validates that fork/await composition maintains associativity.
    *
    * Tests that different approaches to composing fork/await operations produce equivalent results,
    * demonstrating compositional safety.
    */
  test("fork/await composition is associative") {

    val a = Eru.succeed(5)
    val f: Int => Eru[Nothing, Int] = x => Eru.succeed(x * x)

    def forkAwait[E, A](effect: Eru[E, A]): Eru[E | Throwable, A] = for {
      fiber <- runtime.fork(effect)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val approach1 = for {
      step1 <- forkAwait(a)
      step2Effect = f(step1)
      step2 <- step2Effect
      result <- forkAwait(Eru.succeed(step2))
    } yield result

    val approach2 = for {
      step1 <- forkAwait(a)
      step2Effect = f(step1)
      result <- forkAwait(step2Effect)
    } yield result

    val result1 = approach1.unsafeRunSync()
    val result2 = approach2.unsafeRunSync()

    assertEquals(result1, result2)
    assertEquals(result1, 25)
  }

  /** Validates that fork preserves side effect ordering within computations.
    *
    * Tests that the sequential order of side effects is maintained when a computation is executed
    * through the fork/await mechanism.
    */
  test("fork does not affect external side effects ordering within a computation") {
    import scala.collection.mutable
    val events = mutable.ListBuffer.empty[String]

    val computation = for {
      _ <- Eru.effect(events += "step1")
      _ <- Eru.effect(events += "step2")
      _ <- Eru.effect(events += "step3")
    } yield "done"

    val fiber = runtime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Success("done"))
    assertEquals(events.toList, List("step1", "step2", "step3"))
  }

  /** Validates that multiple fork/await operations maintain independence.
    *
    * Tests that concurrent fork/await operations do not interfere with each other's execution or
    * side effects, ensuring proper isolation.
    */
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
      fiber1 <- runtime.fork(computation1)
      fiber2 <- runtime.fork(computation2)
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

  /** Validates the fiber identity property for pure computations.
    *
    * Tests the mathematical identity: await(fork(a)) ≈ a, demonstrating that fork/await operations
    * form an identity for pure values.
    */
  test("fiber identity: await(fork(a)) ≈ a for pure computations") {
    val values = List(42, "hello", List(1, 2, 3), Map("key" -> "value"))

    values.foreach { value =>
      val direct = Eru.succeed(value).unsafeRunSync()

      val throughFiber = for {
        fiber <- runtime.fork(Eru.succeed(value))
        exit <- fiber.await
        result <- Eru.fromExit(exit)
      } yield result

      val fiberResult = throughFiber.unsafeRunSync()
      assertEquals(direct, fiberResult)
    }
  }

  /** Validates that fork/await preserves computation structure for complex effects.
    *
    * Tests that complex multi-step computations maintain their structure and behavior when executed
    * through the fork/await mechanism.
    */
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

    val direct = businessLogic.unsafeRunSync()

    val throughFiber = for {
      fiber <- runtime.fork(businessLogic)
      exit <- fiber.await
      result <- Eru.fromExit(exit)
    } yield result

    val fiberResult = throughFiber.unsafeRunSync()

    assertEquals(direct, fiberResult)
    assertEquals(fiberResult._1.name, "User1")
    assertEquals(fiberResult._2, 300.0)
  }
}
