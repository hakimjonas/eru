package net.ghoula.eru.fiber

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Tests to investigate parSequence finalizer execution behavior.
  *
  * This test suite investigates whether parSequence properly waits for all fiber finalizers
  * to execute before completing, even when it fails fast on the first error.
  */
class ParSequenceFinalizerSpec extends FunSuite {

  test("parSequence should wait for all finalizers even on early failure") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val completionOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def createTrackedResourceEffect(id: Int): Eru[String, String] = {
      Eru.effect {
        val count = resourceCounter.incrementAndGet()
        completionOrder.add(s"resource-$id acquired (count: $count)")
      }.mapError(_.toString).ensure {
        Eru.effect {
          val count = cleanupCounter.incrementAndGet()
          completionOrder.add(s"resource-$id cleaned (count: $count)")
        }
      }.flatMap { _ =>
        // Add small delay to make timing more predictable
        EruRuntime.sleep(Duration.ofMillis(id.toLong)).mapError(_.toString).flatMap { _ =>
          if (id == 2) { // Early failure
            completionOrder.add(s"resource-$id FAILING")
            Eru.fail(s"resource-$id failed")
          } else {
            completionOrder.add(s"resource-$id succeeding")
            Eru.succeed(s"resource-$id")
          }
        }
      }
    }

    // Test with small number to see exact behavior
    val effects = (1 to 5).map(createTrackedResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    // Give finalizers time to complete
    Thread.sleep(100)

    println("Completion order:")
    completionOrder.forEach(println)
    println(s"Resources: ${resourceCounter.get()}, Cleanups: ${cleanupCounter.get()}")
    println(s"Result: $result")

    // The key question: Do all finalizers execute even if parSequence fails fast?
    assertEquals(cleanupCounter.get(), resourceCounter.get(),
      "All resources should be cleaned up even on early parSequence failure")
  }

  test("isolated fiber finalizer execution timing") {
    val completionEvents = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def createSlowFinalizingEffect(id: Int): Eru[String, String] = {
      Eru.succeed(s"effect-$id").ensure {
        // Slow finalizer to test if it gets cut off
        EruRuntime.sleep(Duration.ofMillis(50)).mapError(_.toString).flatMap { _ =>
          Eru.effect {
            completionEvents.add(s"finalizer-$id completed")
          }
        }
      }
    }

    val effects = List(
      createSlowFinalizingEffect(1),
      Eru.fail("early-failure"), // This should cause immediate failure
      createSlowFinalizingEffect(3),
      createSlowFinalizingEffect(4)
    )

    val startTime = System.nanoTime()
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()
    val endTime = System.nanoTime()
    val durationMs = (endTime - startTime) / 1_000_000

    // Give finalizers time to complete
    Thread.sleep(150)

    println(s"Duration: ${durationMs}ms")
    println("Completion events:")
    completionEvents.forEach(println)
    println(s"Result: $result")

    // If parSequence waits for finalizers, we should see slow finalizers complete
    // If it doesn't wait, some finalizers might be cut off
    assert(completionEvents.contains("finalizer-1 completed"))
    // The question is: do we see finalizer-3 and finalizer-4?
  }

  test("compare sequential vs parallel finalizer completion") {
    val seqCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val parCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    def createCountingEffect(counter: java.util.concurrent.atomic.AtomicInteger, id: Int): Eru[String, String] = {
      Eru.succeed(s"effect-$id").ensure {
        Eru.effect(counter.incrementAndGet())
      }.flatMap { result =>
        if (id == 2) Eru.fail(s"effect-$id failed") else Eru.succeed(result)
      }
    }

    val effects1 = (1 to 10).map(id => createCountingEffect(seqCounter, id)).toList
    val effects2 = (1 to 10).map(id => createCountingEffect(parCounter, id)).toList

    // Sequential execution
    val seqResult = effects1.foldLeft[Eru[String, List[String]]](Eru.succeed(List.empty)) { (acc, effect) =>
      acc.flatMap(list => effect.attempt.map {
        case Result.Success(value) => list :+ value
        case Result.Failure(_) => list // Continue despite failures
      })
    }.attempt.unsafeRunSync()

    // Parallel execution
    val parResult = EruRuntime.parSequence(effects2).attempt.unsafeRunSync()

    println(s"Sequential finalizers: ${seqCounter.get()}")
    println(s"Parallel finalizers: ${parCounter.get()}")
    println(s"Sequential result: $seqResult")
    println(s"Parallel result: $parResult")

    // Both should execute the same number of finalizers
    assertEquals(parCounter.get(), seqCounter.get(),
      "Parallel execution should execute same number of finalizers as sequential")
  }
}