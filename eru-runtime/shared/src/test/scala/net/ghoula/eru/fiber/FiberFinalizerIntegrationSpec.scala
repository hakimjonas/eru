package net.ghoula.eru.fiber

import munit.FunSuite
import java.time.Duration
import scala.collection.mutable

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Critical tests for FILO finalizer semantics across concurrent operations.
  *
  * This test suite is essential for verifying that First-In-Last-Out (FILO) finalizer
  * ordering is perfectly preserved across all concurrent operations (zipPar, raceAll, etc.)
  * and interruption scenarios in the unified fiber runtime.
  */
class FiberFinalizerIntegrationSpec extends FunSuite {

  test("single fiber finalizer executes in FILO order") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder += "finalizer1"))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder += "finalizer2"))
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder += "finalizer3"))
    } yield "done"
    
    val fiber = EruRuntime.fork(computation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    
    assertEquals(exit, Exit.Success("done"))
    // FILO order: last registered runs first
    assertEquals(executionOrder.toList, List("finalizer3", "finalizer2", "finalizer1"))
  }

  test("nested fiber finalizers maintain FILO order across fiber boundaries") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val innerComputation = for {
      _ <- Eru.succeed("inner1").ensure(Eru.effect(executionOrder += "inner-fin1"))
      _ <- Eru.succeed("inner2").ensure(Eru.effect(executionOrder += "inner-fin2"))
    } yield "inner-done"
    
    val outerComputation = for {
      _ <- Eru.succeed("outer1").ensure(Eru.effect(executionOrder += "outer-fin1"))
      innerFiber <- EruRuntime.fork(innerComputation)
      _ <- Eru.succeed("outer2").ensure(Eru.effect(executionOrder += "outer-fin2"))
      innerResult <- innerFiber.await.flatMap(Eru.fromExit)
      _ <- Eru.succeed("outer3").ensure(Eru.effect(executionOrder += "outer-fin3"))
    } yield s"outer-$innerResult"
    
    val outerFiber = EruRuntime.fork(outerComputation).unsafeRunSync()
    val exit = outerFiber.await.unsafeRunSync()
    
    assertEquals(exit, Exit.Success("outer-inner-done"))
    
    // Expected FILO order: outer finalizers in reverse, then inner finalizers in reverse
    val expected = List(
      "outer-fin3",    // Last outer finalizer first
      "outer-fin2",    // Second outer finalizer 
      "inner-fin2",    // Inner finalizers from await merge (FILO)
      "inner-fin1",    // Inner finalizers from await merge (FILO)
      "outer-fin1"     // First outer finalizer last
    )
    assertEquals(executionOrder.toList, expected)
  }

  test("zipPar preserves FILO finalizer order from both sides") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val leftComputation = for {
      _ <- Eru.succeed("left1").ensure(Eru.effect(executionOrder += "left-fin1"))
      _ <- Eru.succeed("left2").ensure(Eru.effect(executionOrder += "left-fin2"))
    } yield "left-done"
    
    val rightComputation = for {
      _ <- Eru.succeed("right1").ensure(Eru.effect(executionOrder += "right-fin1"))
      _ <- Eru.succeed("right2").ensure(Eru.effect(executionOrder += "right-fin2"))
    } yield "right-done"
    
    val result = EruRuntime.zipPar(leftComputation, rightComputation).unsafeRunSync()
    
    assertEquals(result, ("left-done", "right-done"))
    
    // Both sides should execute their finalizers in FILO order
    // Order between left and right is not guaranteed, but within each side should be FILO
    val leftFinalizers = executionOrder.filter(_.startsWith("left")).toList
    val rightFinalizers = executionOrder.filter(_.startsWith("right")).toList
    
    assertEquals(leftFinalizers, List("left-fin2", "left-fin1"))
    assertEquals(rightFinalizers, List("right-fin2", "right-fin1"))
  }

  test("parSequence preserves FILO finalizer order for each effect") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    def createEffect(name: String): Eru[Nothing, String] = for {
      _ <- Eru.succeed(s"${name}1").ensure(Eru.effect(executionOrder += s"$name-fin1"))
      _ <- Eru.succeed(s"${name}2").ensure(Eru.effect(executionOrder += s"$name-fin2"))
    } yield s"$name-done"
    
    val effects = List(
      createEffect("effect1"),
      createEffect("effect2"),
      createEffect("effect3")
    )
    
    val results = EruRuntime.parSequence(effects).unsafeRunSync()
    
    assertEquals(results, List("effect1-done", "effect2-done", "effect3-done"))
    
    // Each effect should have its finalizers in FILO order
    for (i <- 1 to 3) {
      val effectFinalizers = executionOrder.filter(_.startsWith(s"effect$i")).toList
      assertEquals(effectFinalizers, List(s"effect$i-fin2", s"effect$i-fin1"))
    }
  }

  test("finalizers execute even when fiber computation fails") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val failingComputation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder += "finalizer1"))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder += "finalizer2"))
      _ <- Eru.fail("intentional failure")
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder += "finalizer3")) // Should not register
    } yield "done"
    
    val fiber = EruRuntime.fork(failingComputation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    
    assertEquals(exit, Exit.Failure("intentional failure"))
    // Only the finalizers that were registered before the failure should execute
    assertEquals(executionOrder.toList, List("finalizer2", "finalizer1"))
  }

  test("finalizers execute even when fiber computation dies") {
    val executionOrder = mutable.ListBuffer.empty[String]
    val exception = new RuntimeException("intentional death")
    
    val dyingComputation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder += "finalizer1"))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder += "finalizer2"))
      _ <- Eru.effect(throw exception)
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder += "finalizer3")) // Should not register
    } yield "done"
    
    val fiber = EruRuntime.fork(dyingComputation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    
    exit match {
      case Exit.Die(t) => assertEquals(t.getMessage, "intentional death")
      case other => fail(s"Expected Die but got $other")
    }
    
    // Finalizers should still execute in FILO order
    assertEquals(executionOrder.toList, List("finalizer2", "finalizer1"))
  }

  test("nested finalizers maintain FILO order within each level") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val computation = for {
      _ <- Eru.succeed("outer").ensure {
        for {
          _ <- Eru.succeed("nested1").ensure(Eru.effect(executionOrder += "nested-fin1"))
          _ <- Eru.succeed("nested2").ensure(Eru.effect(executionOrder += "nested-fin2"))
        } yield ()
      }
      _ <- Eru.succeed("after").ensure(Eru.effect(executionOrder += "after-fin"))
    } yield "done"
    
    val result = computation.unsafeRunSync()
    
    assertEquals(result, "done")
    
    // Expected order: after-fin, then nested finalizers in FILO order
    assertEquals(executionOrder.toList, List("after-fin", "nested-fin2", "nested-fin1"))
  }

  test("finalizer exceptions do not prevent other finalizers from running") {
    val executionOrder = mutable.ListBuffer.empty[String]
    val exception = new RuntimeException("finalizer error")
    
    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder += "finalizer1"))
      _ <- Eru.succeed("step2").ensure(Eru.effect { 
        executionOrder += "failing-finalizer"
        throw exception
      })
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder += "finalizer3"))
    } yield "done"
    
    val result = computation.unsafeRunSync()
    
    assertEquals(result, "done")
    
    // All finalizers should execute despite the exception in the middle one
    assertEquals(executionOrder.toList, List("finalizer3", "failing-finalizer", "finalizer1"))
  }

  test("race operation preserves finalizer order for winner and cancels loser cleanly") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val fastEffect = for {
      _ <- Eru.succeed("fast1").ensure(Eru.effect(executionOrder += "fast-fin1"))
      _ <- Eru.succeed("fast2").ensure(Eru.effect(executionOrder += "fast-fin2"))
    } yield "fast-won"
    
    val slowEffect = for {
      _ <- EruRuntime.sleep(Duration.ofSeconds(1))
      _ <- Eru.succeed("slow1").ensure(Eru.effect(executionOrder += "slow-fin1"))
      _ <- Eru.succeed("slow2").ensure(Eru.effect(executionOrder += "slow-fin2"))
    } yield "slow-won"
    
    val result = EruRuntime.race(fastEffect, slowEffect).unsafeRunSync()
    
    assertEquals(result, Left("fast-won"))
    
    // Fast effect's finalizers should execute in FILO order
    // Slow effect may or may not have started executing its finalizers
    val fastFinalizers = executionOrder.filter(_.startsWith("fast")).toList
    assertEquals(fastFinalizers, List("fast-fin2", "fast-fin1"))
  }

  test("auto-join prevents finalizer leaks from unawaited fibers") {
    val executionOrder = mutable.ListBuffer.empty[String]
    
    val computation = for {
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.succeed("fork1").ensure(Eru.effect(executionOrder += "fork1-fin"))
          _ <- Eru.succeed("fork2").ensure(Eru.effect(executionOrder += "fork2-fin"))
        } yield "fork-done"
      } // Note: not awaiting this fiber
      _ <- EruRuntime.fork {
        for {
          _ <- Eru.succeed("fork3").ensure(Eru.effect(executionOrder += "fork3-fin"))
          _ <- Eru.succeed("fork4").ensure(Eru.effect(executionOrder += "fork4-fin"))
        } yield "fork2-done"  
      } // Note: not awaiting this fiber either
      _ <- Eru.succeed("main").ensure(Eru.effect(executionOrder += "main-fin"))
    } yield "main-done"
    
    val result = computation.unsafeRunSync()
    
    assertEquals(result, "main-done")
    
    // Auto-join should ensure all finalizers execute
    assert(executionOrder.contains("fork1-fin"))
    assert(executionOrder.contains("fork2-fin"))
    assert(executionOrder.contains("fork3-fin"))
    assert(executionOrder.contains("fork4-fin"))
    assert(executionOrder.contains("main-fin"))
    
    // Each forked computation should have FILO finalizer order
    val indices = Map(
      "fork1-fin" -> executionOrder.indexOf("fork1-fin"),
      "fork2-fin" -> executionOrder.indexOf("fork2-fin"),
      "fork3-fin" -> executionOrder.indexOf("fork3-fin"),
      "fork4-fin" -> executionOrder.indexOf("fork4-fin")
    )
    
    // Within each fork, FILO order should be maintained
    assert(indices("fork2-fin") < indices("fork1-fin"))
    assert(indices("fork4-fin") < indices("fork3-fin"))
  }
}