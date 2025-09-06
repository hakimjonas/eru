package net.ghoula.eru.fiber

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Test to reproduce the specific resource leak pattern identified in FiberStressSpec.
  *
  * This test isolates the exact race condition where resource counters and cleanup counters become
  * misaligned under high concurrency pressure.
  */
class ResourceLeakReproductionSpec extends FunSuite {

  test("reproduce resource counter mismatch under high concurrency") {
    // Use the exact same pattern as the failing test
    val operationCount = 150
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    def createResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        // This increment happens BEFORE failure checking
        _ <- Eru.effect(resourceCounter.incrementAndGet()).mapError(_ => s"resource-error-$id")
        resource = s"resource-$id"
        result <-
          if (id % 7 == 0) Eru.fail(s"resource-$id failed") // ~21 failures out of 150
          else Eru.succeed(resource)
      } yield result

      // Finalizer only gets attached if this point is reached
      resourceEffect.ensure {
        Eru.effect {
          cleanupCounter.incrementAndGet()
        }
      }
    }

    val effects = (1 to operationCount).map(createResourceEffect).toList

    // Run multiple times to catch the race condition
    var mismatchFound = false
    var attempt = 0

    while (!mismatchFound && attempt < 10) {
      attempt += 1
      resourceCounter.set(0)
      cleanupCounter.set(0)

      val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

      val resources = resourceCounter.get()
      val cleanups = cleanupCounter.get()

      println(s"Attempt $attempt: Resources=$resources, Cleanups=$cleanups, Result=${result.isFailure}")

      if (resources != cleanups) {
        mismatchFound = true
        println(s"MISMATCH FOUND! Resources: $resources, Cleanups: $cleanups")
        println(s"Missing cleanups: ${resources - cleanups}")
      }
    }

    // This assertion may fail, revealing the race condition
    assertEquals(
      cleanupCounter.get(),
      resourceCounter.get(),
      s"Attempt $attempt: Resource leak detected - not all resources cleaned up"
    )
  }

  test("demonstrate the root cause - finalizer attachment timing") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val finalizerAttachmentCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    def createResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect {
          val count = resourceCounter.incrementAndGet()
          println(s"Resource $id acquired (count: $count)")
        }.mapError(_ => s"resource-error-$id")
        result <-
          if (id % 7 == 0) {
            println(s"Resource $id failing before finalizer attachment")
            Eru.fail(s"resource-$id failed")
          } else {
            Eru.succeed(s"resource-$id")
          }
      } yield result

      // This ensure block only gets evaluated if we reach this point
      val withFinalizer = resourceEffect.ensure {
        Eru.effect {
          finalizerAttachmentCounter.incrementAndGet()
          cleanupCounter.incrementAndGet()
          println(s"Resource $id cleaned up")
        }
      }

      // Log when finalizer gets attached
      println(s"Finalizer attached for resource $id")
      withFinalizer
    }

    // Test with just a few operations to see the timing clearly
    val effects = (1 to 10).map(createResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    println(s"Resources acquired: ${resourceCounter.get()}")
    println(s"Finalizers attached: ${finalizerAttachmentCounter.get()}")
    println(s"Cleanups executed: ${cleanupCounter.get()}")
    println(s"Result: $result")

    // Key insight: finalizers are attached at construction time, not execution time
    assertEquals(finalizerAttachmentCounter.get(), 10, "All finalizers should be attached")
    assertEquals(cleanupCounter.get(), resourceCounter.get(), "All acquired resources should be cleaned up")
  }

  test("correct resource pattern - acquire and cleanup symmetrically") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    def createCorrectResourceEffect(id: Int): Eru[String, String] = {
      // Acquisition and cleanup should be symmetric
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

    val operationCount = 150
    val effects = (1 to operationCount).map(createCorrectResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    println(s"Correct pattern - Resources: ${resourceCounter.get()}, Cleanups: ${cleanupCounter.get()}")
    println(s"Result: $result")

    // This should always pass
    assertEquals(cleanupCounter.get(), resourceCounter.get(), "Correct pattern should never have resource leaks")
  }
}
