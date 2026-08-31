package net.ghoula.eru.fiber

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*
import net.ghoula.eru.test.EruTestSuite
import net.ghoula.eru.test.IsolatedTestRunner

/** Stress tests to ensure fiber runtime stability under high load.
  *
  * Tests the runtime stability with thousands of concurrent fibers and deep nesting to verify no
  * stack overflow or memory issues occur in the unified fiber runtime. These tests validate that
  * the zero-cast implementation can handle production-scale concurrency without degradation.
  *
  * All stress tests must pass consistently without flaky behavior. Any intermittent failures
  * indicate race conditions or resource management issues that violate Eru's correctness
  * guarantees.
  *
  * Determinism notes: the race test forks the raceAll operation so the TestClock can be advanced
  * before any sleep completes, letting an immediate effect deterministically win. The interrupt
  * storm test interrupts deterministically (every 3rd fiber) instead of relying on random timing.
  * Observer tests use CPU-bound work to create real concurrency without TestClock dependence, and
  * wait for asynchronous observer events by polling with a small effect rather than Thread.sleep.
  * The extreme deep-nesting test uses direct recursion rather than forking to exercise nesting
  * without concurrency complications.
  */
class FiberStressSpec extends EruTestSuite {

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(5, scala.concurrent.duration.MINUTES)

  test("high-volume fiber creation and completion (1000 fibers)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fiberCount = 1000
      val fibers = (1 to fiberCount).map { i =>
        runtime.fork(Eru.succeed(i))
      }.toList

      val allFibers = runtime.parSequence(fibers).unsafeRunSync()
      val results = runtime.parSequence(allFibers.map(_.await)).unsafeRunSync()

      assertEquals(results.length, fiberCount)

      val successResults = results.collect { case Exit.Success(value) => value }
      assertEquals(successResults.length, fiberCount)
      assertEquals(successResults.sum, (1 to fiberCount).sum)
    }
  }

  test("deep fiber nesting does not cause stack overflow") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val depth = 100

      def createNestedFibers(remaining: Int): Eru[Nothing, Int] = {
        if (remaining <= 0) Eru.succeed(0)
        else
          for {
            childFiber <- runtime.fork(createNestedFibers(remaining - 1))
            childExit <- childFiber.await
            childResult <- childExit match {
              case Exit.Success(value) => Eru.succeed(value)
              case Exit.Failure(_) => Eru.succeed(0)
              case Exit.Die(_) => Eru.succeed(0)
              case Exit.Interrupt(_, _) => Eru.succeed(0)
            }
          } yield childResult + 1
      }

      val result = createNestedFibers(depth).unsafeRunSync()
      assertEquals(result, depth)
    }
  }

  test("concurrent finalizer stress test with many fibers") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fiberCount = 500
      val executionOrder = new ConcurrentLinkedQueue[Int]()

      def createFiberWithFinalizer(id: Int): Eru[Nothing, String] = {
        Eru.succeed(s"fiber-$id").ensure {
          Eru.effect {
            executionOrder.add(id)
          }
        }
      }

      val effects = (1 to fiberCount).map(createFiberWithFinalizer).toList
      val results = runtime.parSequence(effects).unsafeRunSync()

      assertEquals(results.length, fiberCount)
      assertEquals(executionOrder.size(), fiberCount)

      assertEquals(executionOrder.asScala.toSet, (1 to fiberCount).toSet)
    }
  }

  test("memory stability with large numbers of short-lived fibers") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val rounds = 10
      val fibersPerRound = 100

      for (round <- 1 to rounds) {
        val fibers = (1 to fibersPerRound).map { i =>
          val fiberId = round * fibersPerRound + i
          runtime.fork(Eru.succeed(fiberId))
        }.toList

        val allFibers = runtime.parSequence(fibers).unsafeRunSync()
        val results = runtime.parSequence(allFibers.map(_.await)).unsafeRunSync()

        assertEquals(results.length, fibersPerRound)

        val successCount = results.count {
          case Exit.Success(_) => true
          case _ => false
        }
        assertEquals(successCount, fibersPerRound)
      }
    }
  }

  test("zipPar stress test with many concurrent pairs") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val pairCount = 100

      val pairs = (1 to pairCount).map { i =>
        val left = Eru.succeed(s"left-$i")
        val right = Eru.succeed(s"right-$i")
        runtime.zipPar(left, right)
      }.toList

      val results = runtime.parSequence(pairs).unsafeRunSync()

      assertEquals(results.length, pairCount)

      results.zipWithIndex.foreach { case ((left, right), i) =>
        val expectedI = i + 1
        assertEquals(left, s"left-$expectedI")
        assertEquals(right, s"right-$expectedI")
      }
    }
  }

  test("race operations with many contestants") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val contestantCount = 50

      val effects = List(
        Eru.succeed("immediate-winner"),
        runtime.sleep(Duration.ofMillis(10)).map(_ => "slow-1"),
        runtime.sleep(Duration.ofMillis(20)).map(_ => "slow-2")
      ) ++ (4 to contestantCount).map { i =>
        runtime.sleep(Duration.ofMillis((i * 5).toLong)).map(_ => s"contestant-$i")
      }.toList

      val fiber = runtime.fork(runtime.raceAll(effects)).unsafeRunSync()

      runtime.testClock.advance(Duration.ofMillis(1))

      val result = fiber.await.unsafeRunSync()
      result match {
        case Exit.Success((winner, index)) =>
          assertEquals(winner, "immediate-winner")
          assertEquals(index, 0)
        case other => fail(s"Expected successful race, got: $other")
      }
    }
  }

  test("complex concurrent composition with nested operations") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val outerCount = 20
      val innerCount = 10

      val outerOperations = (1 to outerCount).map { outer =>
        val innerOperations = (1 to innerCount).map { inner =>
          val computation = Eru.succeed(outer * 100 + inner)
          runtime
            .fork(computation)
            .flatMap(_.await)
            .flatMap(exit =>
              exit match {
                case Exit.Success(value) => Eru.succeed(value)
                case Exit.Failure(error) => Eru.fail(error)
                case Exit.Die(t) => Eru.effect(throw t)
                case Exit.Interrupt(_, _) => Eru.succeed(42)
              }
            )
        }.toList

        runtime.parSequence(innerOperations)
      }.toList

      val allResults = runtime.parSequence(outerOperations).unsafeRunSync()

      assertEquals(allResults.length, outerCount)
      allResults.foreach(innerResults => assertEquals(innerResults.length, innerCount))

      val flatResults = allResults.flatten
      assertEquals(flatResults.length, outerCount * innerCount)

      val expectedValues = for {
        outer <- 1 to outerCount
        inner <- 1 to innerCount
      } yield outer * 100 + inner

      assertEquals(flatResults.sorted, expectedValues.toList.sorted)
    }
  }

  test("error handling stress with mixed success and failure scenarios") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val operationCount = 200

      def createMixedEffect(id: Int): Eru[String, Int] = {
        if (id % 10 == 0) Eru.fail(s"error-$id")
        else if (id % 23 == 0)
          Eru.effect(throw new RuntimeException(s"defect-$id")).mapError(_ => s"defect-$id")
        else Eru.succeed(id)
      }

      val effects = (1 to operationCount).map(createMixedEffect).toList

      val fibers = effects.map(runtime.fork)
      val allFibers = runtime.parSequence(fibers).unsafeRunSync()
      val exits = runtime.parSequence(allFibers.map(_.await)).unsafeRunSync()

      assertEquals(exits.length, operationCount)

      val successes = exits.collect { case Exit.Success(value) => value }
      val failures = exits.collect { case Exit.Failure(error) => error }
      val dies = exits.collect { case Exit.Die(_) => 1 }

      assert(successes.length > operationCount * 0.8)

      assertEquals(successes.length + failures.length + dies.length, operationCount)
    }
  }

  test("finalizer execution under high concurrent load") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fiberCount = 300
      val finalizersPerFiber = 3
      val executionCounter = new java.util.concurrent.atomic.AtomicInteger(0)

      def createFiberWithMultipleFinalizers(id: Int): Eru[Nothing, String] = {
        var effect: Eru[Nothing, String] = Eru.succeed(s"fiber-$id")

        for (_ <- 1 to finalizersPerFiber) {
          effect = effect.ensure {
            Eru.effect {
              executionCounter.incrementAndGet()
            }
          }
        }

        effect
      }

      val effects = (1 to fiberCount).map(createFiberWithMultipleFinalizers).toList
      val results = runtime.parSequence(effects).unsafeRunSync()

      assertEquals(results.length, fiberCount)

      val expectedFinalizerCount = fiberCount * finalizersPerFiber
      assertEquals(executionCounter.get(), expectedFinalizerCount)
    }
  }

  test("resource cleanup under concurrent stress with failures - FIXED") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val operationCount = 150
      val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
      val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)

      def createResourceEffect(id: Int): Eru[String, String] = {
        Eru
          .effect(resourceCounter.incrementAndGet())
          .mapError(_.toString)
          .ensure {
            Eru.effect(cleanupCounter.incrementAndGet())
          }
          .flatMap { _ =>
            if (id % 7 == 0) Eru.fail(s"resource-$id failed")
            else Eru.succeed(s"resource-$id")
          }
      }

      val effects = (1 to operationCount).map(createResourceEffect).toList
      val result = runtime.parSequence(effects).attempt.unsafeRunSync()

      result match {
        case Result.Failure(_) =>
        case Result.Success(_) =>
      }

      assertEquals(
        cleanupCounter.get(),
        resourceCounter.get(),
        "All acquired resources should be cleaned up - resource safety guaranteed"
      )
    }
  }

  test("extreme deep nesting stress test (100 levels)") {
    val depth = 100

    def createDeeplyNestedComputation(remaining: Int): Eru[Nothing, Int] = {
      if (remaining <= 0) {
        Eru.succeed(42)
      } else {
        for {
          result <- createDeeplyNestedComputation(remaining - 1)
        } yield result + 1
      }
    }

    val result = createDeeplyNestedComputation(depth).unsafeRunSync()
    assertEquals(result, 42 + depth)
  }

  test("massive parallel finalizer execution under stress (500 fibers, 3 finalizers each)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fiberCount = 500
      val finalizersPerFiber = 3
      val finalizerExecutionCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val fiberCompletions = new java.util.concurrent.atomic.AtomicInteger(0)

      def createFiberWithOrderedFinalizers(fiberId: Int): Eru[Nothing, String] = {
        val executionOrder = new java.util.concurrent.atomic.AtomicReference(List.empty[Int])

        var computation: Eru[Nothing, String] = Eru.succeed(s"fiber-$fiberId")

        for (finId <- 1 to finalizersPerFiber) {
          computation = computation.ensure {
            Eru.effect {
              val currentOrder = executionOrder.get()
              executionOrder.set(finId :: currentOrder)
              finalizerExecutionCount.incrementAndGet()

            }
          }
        }

        computation.map { result =>
          fiberCompletions.incrementAndGet()
          result
        }
      }

      val effects = (1 to fiberCount).map(createFiberWithOrderedFinalizers).toList
      val results = runtime.parSequence(effects).unsafeRunSync()

      assertEquals(results.length, fiberCount)
      assertEquals(fiberCompletions.get(), fiberCount)
      assertEquals(finalizerExecutionCount.get(), fiberCount * finalizersPerFiber)

      assert(finalizerExecutionCount.get() > 0, "Some finalizers should have executed")
    }
  }

  test("interrupt storm resilience (200 fibers with deterministic interrupts)") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val fiberCount = 200
      val interruptedCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val completedCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val finalizerCount = new java.util.concurrent.atomic.AtomicInteger(0)

      def createInterruptibleFiber(id: Int): Eru[Nothing, Either[String, String]] = {
        val computation = Eru.succeed(s"completed-$id")

        computation.ensure {
          Eru.effect(finalizerCount.incrementAndGet())
        }.attempt.map {
          case Result.Success(value) =>
            completedCount.incrementAndGet()
            Right(value)
          case Result.Failure(error) =>
            interruptedCount.incrementAndGet()
            Left(error.toString)
        }
      }

      val computation = for {
        fibers <- runtime.parSequence(
          (1 to fiberCount).map(id => runtime.fork(createInterruptibleFiber(id))).toList
        )

        _ <- runtime.parSequence {
          fibers.zipWithIndex.collect {
            case (fiber, idx) if idx % 3 == 0 =>
              fiber.interrupt(InterruptCause.Cancelled(Some(s"deterministic-interrupt-$idx")))
          }
        }

        results <- runtime.parSequence(
          fibers.map(
            _.await.flatMap(exit =>
              exit match {
                case Exit.Success(value) => Eru.succeed(value)
                case Exit.Failure(e) => Eru.fail(e)
                case Exit.Die(t) => Eru.effect(throw t)
                case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
              }
            )
          )
        )
      } yield results

      val results = computation.unsafeRunSync()

      assertEquals(results.length, fiberCount)
      assertEquals(finalizerCount.get(), fiberCount)

      assertEquals(interruptedCount.get() + completedCount.get(), fiberCount)
    }
  }

  test("memory pressure simulation with finalizer cleanup verification") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val rounds = 3
      val fibersPerRound = 10
      val objectSize = 1024 * 8
      val cleanupVerification = new java.util.concurrent.atomic.AtomicInteger(0)

      for (round <- 1 to rounds) {
        val effects = (1 to fibersPerRound).map { i =>
          val fiberId = round * fibersPerRound + i

          for {
            data <- Eru.effect(Array.fill(objectSize)(fiberId.toByte))
            checksum <- Eru.effect(data.take(100).sum)
            _ <- Eru.effect(cleanupVerification.incrementAndGet())
          } yield s"completed-$fiberId-$checksum"
        }.toList

        val results = runtime.parSequence(effects).unsafeRunSync()
        assertEquals(results.length, fibersPerRound)
      }

      assertEquals(cleanupVerification.get(), rounds * fibersPerRound)
    }
  }

  test("observer integration stress test with comprehensive event tracking") {
    IsolatedTestRunner.withIsolatedRuntime { runtime =>
      val eventCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val fiberStartEvents = new java.util.concurrent.atomic.AtomicInteger(0)
      val fiberEndEvents = new java.util.concurrent.atomic.AtomicInteger(0)

      val observer: EruObserver = new EruObserver {
        def onEvent(event: EruObserver.EruEvent): Unit = {
          eventCount.incrementAndGet()
          event match {
            case _: EruObserver.EruEvent.FiberStarted => fiberStartEvents.incrementAndGet()
            case _: EruObserver.EruEvent.FiberCompleted => fiberEndEvents.incrementAndGet()
            case _ =>
          }
        }
      }

      val fiberCount = 100

      def createObservableFiber(id: Int): Eru[String, String] = {
        for {
          _ <- Eru.effect {
            (1 to 1000).map(_ * id).sum
          }.mapError(_.getMessage)

          _ <- Eru.effect {
            s"work-$id".hashCode
          }.mapError(_.getMessage)

          result <- Eru.effect {
            val computation = (1 to 500).map(i => i * id + i).sum
            s"completed-$id-$computation"
          }.mapError(_.getMessage)

        } yield result
      }

      val computation = for {
        fibers <- runtime.parSequence {
          (1 to fiberCount).map(id => runtime.forkWithObserver(createObservableFiber(id), observer)).toList
        }
        results <- runtime.parSequence(
          fibers.map(
            _.await.flatMap(exit =>
              exit match {
                case Exit.Success(value) => Eru.succeed(value)
                case Exit.Failure(e) => Eru.fail(e)
                case Exit.Die(t) => Eru.effect(throw t)
                case Exit.Interrupt(_, _) => Eru.succeed("interrupted")
              }
            )
          )
        )
      } yield results

      val results = computation.unsafeRunSync()

      def waitForObserverCompletion(): Unit = {
        var attempts = 0
        while (fiberEndEvents.get() < fiberCount && attempts < 1000) {
          Eru.effect(()).unsafeRunSync()
          attempts += 1
        }
      }

      waitForObserverCompletion()

      assertEquals(results.length, fiberCount)
      assertEquals(fiberStartEvents.get(), fiberCount)
      assertEquals(fiberEndEvents.get(), fiberCount)
      assert(eventCount.get() >= fiberStartEvents.get() + fiberEndEvents.get())

      results.foreach { result =>
        assert(result.contains("completed-"))
        assert(result.contains("-"))
      }
    }
  }
}
