package net.ghoula.eru.fiber

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite

/** Essential tests for FILO finalizer semantics.
  *
  * This test suite verifies the core correctness property: finalizers execute in First-In-Last-Out
  * (FILO) order. This is the cornerstone of Eru's resource safety.
  *
  * Focus: Deterministic, essential correctness tests only. Removed: Complex concurrency patterns,
  * timing dependencies, deep nesting.
  */
class FiberFinalizerIntegrationSpec extends EruTestSuite {

  test("single fiber finalizer executes in FILO order") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("fin1")))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("fin2")))
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("fin3")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")
    assertEquals(executionOrder.asScala.toList, List("fin3", "fin2", "fin1"))
  }

  test("finalizers execute on failure") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("fin1")))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("fin2")))
      _ <- Eru.fail("intentional failure")
      _ <- Eru.succeed("unreachable").ensure(Eru.effect(executionOrder.add("unreachable")))
    } yield "done"

    val result = computation.attempt.unsafeRunSync()

    assertEquals(result, Result.Failure("intentional failure"))
    assertEquals(executionOrder.asScala.toList, List("fin2", "fin1"))
  }

  test("finalizers execute on death") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val exception = new RuntimeException("death")

    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("fin1")))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("fin2")))
      _ <- Eru.effect(throw exception)
      _ <- Eru.succeed("unreachable").ensure(Eru.effect(executionOrder.add("unreachable")))
    } yield "done"

    val result = computation.attempt.unsafeRunSync()

    result match {
      case Result.Failure(t: RuntimeException) => assertEquals(t.getMessage, "death")
      case other => fail(s"Expected death but got: $other")
    }
    assertEquals(executionOrder.asScala.toList, List("fin2", "fin1"))
  }

  test("nested finalizers maintain FILO order") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      _ <- Eru.succeed("outer").ensure {
        for {
          _ <- Eru.succeed("inner1").ensure(Eru.effect(executionOrder.add("inner-fin1")))
          _ <- Eru.succeed("inner2").ensure(Eru.effect(executionOrder.add("inner-fin2")))
        } yield ()
      }
      _ <- Eru.succeed("after").ensure(Eru.effect(executionOrder.add("outer-fin")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")
    assertEquals(executionOrder.asScala.toList, List("outer-fin", "inner-fin2", "inner-fin1"))
  }

  test("finalizer exceptions do not prevent other finalizers") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val exception = new RuntimeException("finalizer error")

    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("fin1")))
      _ <- Eru
        .succeed("step2")
        .ensure(Eru.effect {
          executionOrder.add("failing-fin")
          throw exception
        })
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("fin3")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")
    assertEquals(executionOrder.asScala.toList, List("fin3", "failing-fin", "fin1"))
  }

  test("basic fork/await preserves FILO in each fiber") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val childComputation = for {
      _ <- Eru.succeed("child1").ensure(Eru.effect(executionOrder.add("child-fin1")))
      _ <- Eru.succeed("child2").ensure(Eru.effect(executionOrder.add("child-fin2")))
    } yield "child-done"

    val parentComputation = for {
      _ <- Eru.succeed("parent1").ensure(Eru.effect(executionOrder.add("parent-fin1")))
      fiber <- runtime.fork(childComputation)
      _ <- Eru.succeed("parent2").ensure(Eru.effect(executionOrder.add("parent-fin2")))
      childResult <- fiber.await.flatMap(exit => Eru.fromExit(exit))
      _ <- Eru.succeed("parent3").ensure(Eru.effect(executionOrder.add("parent-fin3")))
    } yield s"parent-$childResult"

    val result = parentComputation.unsafeRunSync()

    assertEquals(result, "parent-child-done")

    val executionList = executionOrder.asScala.toList
    val childFinalizers = executionList.filter(_.startsWith("child"))
    val parentFinalizers = executionList.filter(_.startsWith("parent"))

    // Each fiber maintains FILO order
    assertEquals(childFinalizers, List("child-fin2", "child-fin1"))
    assertEquals(parentFinalizers, List("parent-fin3", "parent-fin2", "parent-fin1"))
  }

  test("simple zipPar preserves FILO in each side") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val leftComputation = for {
      _ <- Eru.succeed("left1").ensure(Eru.effect(executionOrder.add("left-fin1")))
      _ <- Eru.succeed("left2").ensure(Eru.effect(executionOrder.add("left-fin2")))
    } yield "left-done"

    val rightComputation = for {
      _ <- Eru.succeed("right1").ensure(Eru.effect(executionOrder.add("right-fin1")))
      _ <- Eru.succeed("right2").ensure(Eru.effect(executionOrder.add("right-fin2")))
    } yield "right-done"

    val result = runtime.zipPar(leftComputation, rightComputation).unsafeRunSync()

    assertEquals(result, ("left-done", "right-done"))

    val executionList = executionOrder.asScala.toList
    val leftFinalizers = executionList.filter(_.startsWith("left"))
    val rightFinalizers = executionList.filter(_.startsWith("right"))

    // Each side maintains FILO order
    assertEquals(leftFinalizers, List("left-fin2", "left-fin1"))
    assertEquals(rightFinalizers, List("right-fin2", "right-fin1"))
  }

  test("bracket finalizers execute in correct order") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val acquire = Eru.effect {
      executionOrder.add("acquire")
      "resource"
    }

    val computation = acquire.bracket(resource => Eru.effect(executionOrder.add(s"release-$resource"))) { resource =>
      for {
        _ <- Eru.succeed(s"using-$resource").ensure(Eru.effect(executionOrder.add("use-fin1")))
        _ <- Eru.succeed("more-use").ensure(Eru.effect(executionOrder.add("use-fin2")))
      } yield s"used-$resource"
    }

    val result = computation.unsafeRunSync()

    assertEquals(result, "used-resource")
    assertEquals(executionOrder.asScala.toList, List("acquire", "release-resource", "use-fin2", "use-fin1"))
  }

  test("bracket finalizers execute on failure") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val acquire = Eru.effect {
      executionOrder.add("acquire")
      "resource"
    }

    val computation = acquire.bracket(resource => Eru.effect(executionOrder.add(s"release-$resource"))) { resource =>
      for {
        _ <- Eru.succeed(s"using-$resource").ensure(Eru.effect(executionOrder.add("use-fin1")))
        _ <- Eru.fail("use failed")
        _ <- Eru.succeed("unreachable").ensure(Eru.effect(executionOrder.add("unreachable")))
      } yield s"used-$resource"
    }

    val result = computation.attempt.unsafeRunSync()

    assertEquals(result, Result.Failure("use failed"))
    assertEquals(executionOrder.asScala.toList, List("acquire", "release-resource", "use-fin1"))
  }

  test("multiple levels of ensure maintain strict FILO") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      _ <- Eru
        .succeed("level1")
        .ensure(Eru.effect(executionOrder.add("ensure1-fin")))
        .ensure(Eru.effect(executionOrder.add("ensure2-fin")))
        .ensure(Eru.effect(executionOrder.add("ensure3-fin")))
      _ <- Eru.succeed("after").ensure(Eru.effect(executionOrder.add("after-fin")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")
    assertEquals(executionOrder.asScala.toList, List("after-fin", "ensure3-fin", "ensure2-fin", "ensure1-fin"))
  }
}
