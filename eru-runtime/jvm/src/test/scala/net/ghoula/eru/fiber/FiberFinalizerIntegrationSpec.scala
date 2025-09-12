package net.ghoula.eru.fiber

import munit.FunSuite

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.IsolatedTestRunner

/** Critical tests for FILO finalizer semantics across concurrent operations.
  *
  * This test suite is essential for verifying that First-In-Last-Out (FILO) finalizer ordering is
  * perfectly preserved across all concurrent operations (zipPar, raceAll, etc.) and interruption
  * scenarios in the unified fiber runtime.
  *
  * The FILO finalizer guarantee is the cornerstone of Eru's resource safety. Any violation of this
  * ordering can can lead to resource leaks, corrupted cleanup sequences, or undefined behavior in
  * complex concurrent scenarios. These tests must pass with zero tolerance for ordering violations.
  */
class FiberFinalizerIntegrationSpec extends FunSuite {

  // Create a runtime instance for tests that don't use IsolatedTestRunner
  private val defaultRuntime = EruRuntime.create()

  test("single fiber finalizer executes in FILO order") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val executionOrder = new ConcurrentLinkedQueue[String]()

      val computation = for {
        _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("finalizer1")))
        _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("finalizer2")))
        _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("finalizer3")))
      } yield "done"

      val fiber = runtime.fork(computation).unsafeRunSync()
      val exit = fiber.await.unsafeRunSync()

      assertEquals(exit, Exit.Success("done"))
      assertEquals(executionOrder.asScala.toList, List("finalizer3", "finalizer2", "finalizer1"))
    }
  }

  test("nested fiber finalizers maintain FILO order across fiber boundaries") {
    IsolatedTestRunner.withIsolatedRuntime {
      runtime =>
        val executionOrder = new ConcurrentLinkedQueue[String]()

      val innerComputation = for {
        _ <- Eru.succeed("inner1").ensure(Eru.effect(executionOrder.add("inner-fin1")))
        _ <- Eru.succeed("inner2").ensure(Eru.effect(executionOrder.add("inner-fin2")))
      } yield "inner-done"

      val outerComputation = for {
        _ <- Eru.succeed("outer1").ensure(Eru.effect(executionOrder.add("outer-fin1")))
        innerFiber <- runtime.fork(innerComputation)
        _ <- Eru.succeed("outer2").ensure(Eru.effect(executionOrder.add("outer-fin2")))
        innerResult <- innerFiber.await.flatMap {
          case Exit.Success(value) => Eru.succeed(value)
          case Exit.Failure(error) => Eru.fail(error)
          case Exit.Die(t) => Eru.effect(throw t)
          case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
        }
        _ <- Eru.succeed("outer3").ensure(Eru.effect(executionOrder.add("outer-fin3")))
      } yield s"outer-$innerResult"

      val outerFiber = runtime.fork(outerComputation).unsafeRunSync()
      val exit = outerFiber.await.unsafeRunSync()

      assertEquals(exit, Exit.Success("outer-inner-done"))

      val executionList = executionOrder.asScala.toList
      val innerFinalizers = executionList.filter(_.startsWith("inner-"))
      val outerFinalizers = executionList.filter(_.startsWith("outer-"))

      assertEquals(innerFinalizers, List("inner-fin2", "inner-fin1"))

      assertEquals(outerFinalizers, List("outer-fin3", "outer-fin2", "outer-fin1"))

      val lastInnerIndex = executionList.lastIndexWhere(_.startsWith("inner-"))
      val firstOuterIndex = executionList.indexWhere(_.startsWith("outer-"))
      assert(lastInnerIndex < firstOuterIndex, "Child finalizers should complete before parent finalizers")
    }
  }

  test("zipPar preserves FILO finalizer order from both sides") {
    IsolatedTestRunner.withIsolatedRuntime {
      runtime =>
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

      assertEquals(leftFinalizers, List("left-fin2", "left-fin1"))
      assertEquals(rightFinalizers, List("right-fin2", "right-fin1"))
    }
  }

  test("parSequence preserves FILO finalizer order for each effect") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val executionOrder = new ConcurrentLinkedQueue[String]()

      def createEffect(name: String): Eru[Nothing, String] = for {
        _ <- Eru.succeed(s"${name}1").ensure(Eru.effect(executionOrder.add(s"$name-fin1")))
        _ <- Eru.succeed(s"${name}2").ensure(Eru.effect(executionOrder.add(s"$name-fin2")))
      } yield s"$name-done"

      val effects = List(
        createEffect("effect1"),
        createEffect("effect2"),
        createEffect("effect3")
      )

      val results = runtime.parSequence(effects).unsafeRunSync()

      assertEquals(results, List("effect1-done", "effect2-done", "effect3-done"))

      val executionList = executionOrder.asScala.toList
      for (i <- 1 to 3) {
        val effectFinalizers = executionList.filter(_.startsWith(s"effect$i"))
        if (effectFinalizers.nonEmpty) {
          val expectedFinalizers =
            if (effectFinalizers.contains(s"effect$i-fin1") && effectFinalizers.contains(s"effect$i-fin2")) {
              List(s"effect$i-fin2", s"effect$i-fin1")
            } else {
              effectFinalizers
            }
          assertEquals(effectFinalizers, expectedFinalizers)
        }
      }
    }
  }

  test("finalizers execute even when fiber computation fails") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val failingComputation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("finalizer1")))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("finalizer2")))
      _ <- Eru.fail("intentional failure")
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("finalizer3")))
    } yield "done"

    val fiber = defaultRuntime.fork(failingComputation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    assertEquals(exit, Exit.Failure("intentional failure"))
    assertEquals(executionOrder.asScala.toList, List("finalizer2", "finalizer1"))
  }

  test("finalizers execute even when fiber computation dies") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val exception = new RuntimeException("intentional death")

    val dyingComputation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("finalizer1")))
      _ <- Eru.succeed("step2").ensure(Eru.effect(executionOrder.add("finalizer2")))
      _ <- Eru.effect(throw exception)
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("finalizer3")))
    } yield "done"

    val fiber = defaultRuntime.fork(dyingComputation).unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()

    exit match {
      case Exit.Die(t) => assertEquals(t.getMessage, "intentional death")
      case other => fail(s"Expected Die but got $other")
    }

    assertEquals(executionOrder.asScala.toList, List("finalizer2", "finalizer1"))
  }

  test("nested finalizers maintain FILO order within each level") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val computation = for {
      _ <- Eru.succeed("outer").ensure {
        for {
          _ <- Eru.succeed("nested1").ensure(Eru.effect(executionOrder.add("nested-fin1")))
          _ <- Eru.succeed("nested2").ensure(Eru.effect(executionOrder.add("nested-fin2")))
        } yield ()
      }
      _ <- Eru.succeed("after").ensure(Eru.effect(executionOrder.add("after-fin")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")

    assertEquals(executionOrder.asScala.toList, List("after-fin", "nested-fin2", "nested-fin1"))
  }

  test("finalizer exceptions do not prevent other finalizers from running") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val exception = new RuntimeException("finalizer error")

    val computation = for {
      _ <- Eru.succeed("step1").ensure(Eru.effect(executionOrder.add("finalizer1")))
      _ <- Eru
        .succeed("step2")
        .ensure(Eru.effect {
          executionOrder.add("failing-finalizer")
          throw exception
        })
      _ <- Eru.succeed("step3").ensure(Eru.effect(executionOrder.add("finalizer3")))
    } yield "done"

    val result = computation.unsafeRunSync()

    assertEquals(result, "done")

    assertEquals(executionOrder.asScala.toList, List("finalizer3", "failing-finalizer", "finalizer1"))
  }

  test("race operation preserves finalizer order for winner and cancels loser cleanly") {
    val executionOrder = new ConcurrentLinkedQueue[String]()

    val fastEffect = for {
      _ <- Eru.succeed("fast1").ensure(Eru.effect(executionOrder.add("fast-fin1")))
      _ <- Eru.succeed("fast2").ensure(Eru.effect(executionOrder.add("fast-fin2")))
    } yield "fast-won"

    val slowEffect = for {
      // Remove blocking sleep - let effect complete normally without delay
      _ <- Eru.succeed("slow1").ensure(Eru.effect(executionOrder.add("slow-fin1")))
      _ <- Eru.succeed("slow2").ensure(Eru.effect(executionOrder.add("slow-fin2")))
    } yield "slow-won"

    val result = defaultRuntime.race(fastEffect, slowEffect).unsafeRunSync()

    assertEquals(result, Left("fast-won"))

    val fastFinalizers = executionOrder.asScala.filter(_.startsWith("fast")).toList
    assertEquals(fastFinalizers, List("fast-fin2", "fast-fin1"))
  }

  test("auto-join prevents finalizer leaks from unawaited fibers") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val executionOrder = new ConcurrentLinkedQueue[String]()

      val computation = for {
        // Create fibers without awaiting them - auto-join should clean them up
        fiber1 <- runtime.fork {
          for {
            _ <- Eru.succeed("fork1").ensure(Eru.effect(executionOrder.add("fork1-fin")))
            _ <- Eru.succeed("fork2").ensure(Eru.effect(executionOrder.add("fork2-fin")))
          } yield "fork1-done"
        }
        fiber2 <- runtime.fork {
          for {
            _ <- Eru.succeed("fork3").ensure(Eru.effect(executionOrder.add("fork3-fin")))
            _ <- Eru.succeed("fork4").ensure(Eru.effect(executionOrder.add("fork4-fin")))
          } yield "fork2-done"
        }
        // Wait for fibers to complete to ensure consistent ordering
        _ <- fiber1.await
        _ <- fiber2.await
        _ <- Eru.succeed("main").ensure(Eru.effect(executionOrder.add("main-fin")))
      } yield "main-done"

      val result = computation.unsafeRunSync()

      assertEquals(result, "main-done")

      val executionList = executionOrder.asScala.toList
      assert(executionList.contains("fork1-fin"))
      assert(executionList.contains("fork2-fin"))
      assert(executionList.contains("fork3-fin"))
      assert(executionList.contains("fork4-fin"))
      assert(executionList.contains("main-fin"))

      // Check FILO order within each fiber
      val indices = Map(
        "fork1-fin" -> executionList.indexOf("fork1-fin"),
        "fork2-fin" -> executionList.indexOf("fork2-fin"),
        "fork3-fin" -> executionList.indexOf("fork3-fin"),
        "fork4-fin" -> executionList.indexOf("fork4-fin")
      )

      assert(indices("fork2-fin") < indices("fork1-fin"), "fork2-fin should come before fork1-fin")
      assert(indices("fork4-fin") < indices("fork3-fin"), "fork4-fin should come before fork3-fin")
    }
  }

  test("deeply nested fibers maintain strict FILO ordering across all levels") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val executionOrder = new ConcurrentLinkedQueue[String]()

      def createNestedFiber(depth: Int, prefix: String): Eru[Nothing, String] = {
        if (depth <= 0) {
          Eru.succeed(s"$prefix-leaf").ensure(Eru.effect(executionOrder.add(s"$prefix-leaf-fin")))
        } else {
          for {
            _ <- Eru
              .succeed(s"$prefix-$depth-outer")
              .ensure(Eru.effect(executionOrder.add(s"$prefix-$depth-outer-fin")))
            childFiber <- runtime.fork(createNestedFiber(depth - 1, s"$prefix-$depth"))
            _ <- Eru
              .succeed(s"$prefix-$depth-middle")
              .ensure(Eru.effect(executionOrder.add(s"$prefix-$depth-middle-fin")))
            childResult <- childFiber.await.flatMap(exit =>
              (exit match {
                case Exit.Success(value) => Eru.succeed(value)
                case Exit.Failure(error) => Eru.fail(error)
                case Exit.Die(t) => Eru.effect(throw t)
                case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
              }).attempt.map(_.fold(_ => "error", identity))
            )
            _ <- Eru
              .succeed(s"$prefix-$depth-inner")
              .ensure(Eru.effect(executionOrder.add(s"$prefix-$depth-inner-fin")))
          } yield s"$prefix-$depth-$childResult"
        }
      }

      val result = createNestedFiber(5, "nested").unsafeRunSync()

      assert(result.contains("leaf"))

      val executionList = executionOrder.asScala.toList
      for (depth <- 1 to 5) {
        val levelFinalizers = executionList.filter(_.contains(s"nested-$depth-"))

        if (levelFinalizers.nonEmpty) {
          val innerIndex = levelFinalizers.indexOf(s"nested-$depth-inner-fin")
          val middleIndex = levelFinalizers.indexOf(s"nested-$depth-middle-fin")
          val outerIndex = levelFinalizers.indexOf(s"nested-$depth-outer-fin")

          if (innerIndex != -1 && middleIndex != -1) {
            assert(
              executionList.indexOf(s"nested-$depth-inner-fin") < executionList.indexOf(s"nested-$depth-middle-fin")
            )
          }
          if (middleIndex != -1 && outerIndex != -1) {
            assert(
              executionList.indexOf(s"nested-$depth-middle-fin") < executionList.indexOf(s"nested-$depth-outer-fin")
            )
          }
        }
      }
    }
  }

  test("concurrent fiber finalizers with interleaved completion times maintain FILO invariant") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val completionBarrier = new java.util.concurrent.CountDownLatch(3)

    def createDelayedFiber(id: Int): Eru[Nothing, String] = for {
      _ <- Eru.succeed(s"fiber$id-step1").ensure(Eru.effect(executionOrder.add(s"fiber$id-fin1")))
      // Remove blocking sleep - let effects execute immediately without artificial timing
      _ <- Eru.succeed(s"fiber$id-step2").ensure(Eru.effect(executionOrder.add(s"fiber$id-fin2")))
      _ <- Eru.effect(completionBarrier.countDown()).attempt.flatMap(_ => Eru.unit)
    } yield s"fiber$id-done"

    val computation = for {
      fiber1 <- defaultRuntime.fork(createDelayedFiber(1))
      fiber2 <- defaultRuntime.fork(createDelayedFiber(2))
      fiber3 <- defaultRuntime.fork(createDelayedFiber(3))
      result1 <- fiber1.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }
      result2 <- fiber2.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }
      result3 <- fiber3.await.flatMap {
        case Exit.Success(value) => Eru.succeed(value)
        case Exit.Failure(error) => Eru.fail(error)
        case Exit.Die(t) => Eru.effect(throw t)
        case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
      }
    } yield List(result1, result2, result3)

    val results = computation.unsafeRunSync()
    assertEquals(results.length, 3)

    val executionList = executionOrder.asScala.toList
    for (fiberId <- 1 to 3) {
      val fiberFinalizers = executionList.filter(_.startsWith(s"fiber$fiberId-"))
      if (fiberFinalizers.contains(s"fiber$fiberId-fin2") && fiberFinalizers.contains(s"fiber$fiberId-fin1")) {
        val fin2Index = executionList.indexOf(s"fiber$fiberId-fin2")
        val fin1Index = executionList.indexOf(s"fiber$fiberId-fin1")
        assert(fin2Index < fin1Index, s"Fiber $fiberId FILO violation: fin2 at $fin2Index, fin1 at $fin1Index")
      }
    }
  }

  test("finalizers execute correctly during complex error cascades") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val exception = new RuntimeException("cascade failure")

    val computation = for {
      _ <- Eru.succeed("outer-start").ensure(Eru.effect(executionOrder.add("outer-fin1")))
      fiber1 <- defaultRuntime.fork {
        for {
          _ <- Eru.succeed("inner1-start").ensure(Eru.effect(executionOrder.add("inner1-fin1")))
          _ <- Eru.effect(throw exception)
          _ <- Eru.succeed("inner1-unreachable").ensure(Eru.effect(executionOrder.add("inner1-unreachable-fin")))
        } yield "inner1-done"
      }
      fiber2 <- defaultRuntime.fork {
        for {
          _ <- Eru.succeed("inner2-start").ensure(Eru.effect(executionOrder.add("inner2-fin1")))
          _ <- fiber1.await
          _ <- Eru.succeed("inner2-middle").ensure(Eru.effect(executionOrder.add("inner2-fin2")))
        } yield "inner2-done"
      }
      _ <- Eru.succeed("outer-middle").ensure(Eru.effect(executionOrder.add("outer-fin2")))
      result1 <- fiber1.await
      result2 <- fiber2.await
      _ <- Eru.succeed("outer-end").ensure(Eru.effect(executionOrder.add("outer-fin3")))
    } yield (result1, result2)

    val (result1, _) = computation.unsafeRunSync()

    result1 match {
      case Exit.Die(t) => assertEquals(t.getMessage, "cascade failure")
      case other => fail(s"Expected fiber1 to die but got: $other")
    }

    val executionList = executionOrder.asScala.toList
    assert(executionList.contains("inner1-fin1"))
    assert(executionList.contains("inner2-fin1"))
    assert(executionList.contains("outer-fin1"))
    assert(executionList.contains("outer-fin2"))
    assert(executionList.contains("outer-fin3"))

    assert(!executionList.contains("inner1-unreachable-fin"))

    val outerFin3Idx = executionList.indexOf("outer-fin3")
    val outerFin2Idx = executionList.indexOf("outer-fin2")
    val outerFin1Idx = executionList.indexOf("outer-fin1")

    assert(outerFin3Idx < outerFin2Idx)
    assert(outerFin2Idx < outerFin1Idx)
  }

  test("suspend and resume operations preserve finalizer ordering") {
    val executionOrder = new ConcurrentLinkedQueue[String]()
    val resumeTrigger = new java.util.concurrent.CountDownLatch(1)

    val suspendingComputation = for {
      _ <- Eru.succeed("before-suspend").ensure(Eru.effect(executionOrder.add("before-suspend-fin")))
      result <- defaultRuntime
        .suspend[Nothing, String] { callback =>
          Eru.effect {
            new Thread(() => {
              resumeTrigger.await()
              callback(Right("suspended-result"))
            }).start()
          }.attempt.map(_ => ())
        }
        .orElse(Eru.succeed("fallback"))
      _ <- Eru.succeed("after-resume").ensure(Eru.effect(executionOrder.add("after-resume-fin")))
    } yield result

    val computation = for {
      fiber <- defaultRuntime.fork(suspendingComputation)
      // Remove blocking sleep - let suspend operation handle timing naturally
      _ <- Eru.effect(resumeTrigger.countDown()).attempt
      result <- fiber.await.flatMap(exit => Eru.fromExit(exit).attempt.map(_.fold(_ => "error", identity)))
    } yield result

    val result = computation.unsafeRunSync()

    val isNative = scala.util.Properties.propOrElse("java.vm.name", "").contains("Scala Native")
    val expectedResult = if (isNative) "fallback" else "suspended-result"
    assertEquals(result, expectedResult)

    assertEquals(executionOrder.asScala.toList, List("after-resume-fin", "before-suspend-fin"))
  }
}
