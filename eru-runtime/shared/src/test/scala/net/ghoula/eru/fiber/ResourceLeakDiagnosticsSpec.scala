package net.ghoula.eru.fiber

import munit.FunSuite

import java.time.Duration
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Diagnostic tests to investigate potential resource leak issues.
  *
  * This test suite isolates and diagnoses the resource cleanup issue identified in FiberStressSpec
  * where resourceCounter != cleanupCounter, indicating potential finalizer execution problems.
  */
class ResourceLeakDiagnosticsSpec extends FunSuite {

  test("minimal resource cleanup diagnostic - sequential execution") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val operationLog = mutable.ListBuffer.empty[String]

    def createTrackedResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect {
          val count = resourceCounter.incrementAndGet()
          operationLog += s"Resource $id acquired (total: $count)"
          count
        }.mapError(_ => s"resource-error-$id")
        result <-
          if (id % 3 == 0) {
            operationLog += s"Resource $id failing"
            Eru.fail(s"resource-$id failed")
          } else {
            operationLog += s"Resource $id succeeding"
            Eru.succeed(s"resource-$id")
          }
      } yield result

      resourceEffect.ensure {
        Eru.effect {
          val count = cleanupCounter.incrementAndGet()
          operationLog += s"Resource $id cleaned up (total: $count)"
        }
      }
    }

    // Test with just 6 operations sequentially to isolate the issue
    val effects = (1 to 6).map(createTrackedResourceEffect).toList
    val results = effects.map(_.attempt.unsafeRunSync())

    println(s"Results: $results")

    println(s"Resource counter: ${resourceCounter.get()}")
    println(s"Cleanup counter: ${cleanupCounter.get()}")
    println("Operation log:")
    operationLog.foreach(println)

    assertEquals(cleanupCounter.get(), resourceCounter.get(), s"Sequential cleanup failed. Log: ${operationLog.toList}")
  }

  test("parallel resource cleanup diagnostic - small scale") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val failureCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val resourceAcquisitionLog = new java.util.concurrent.ConcurrentHashMap[Int, String]()
    val cleanupLog = new java.util.concurrent.ConcurrentHashMap[Int, String]()

    def createDiagnosticResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect {
          val count = resourceCounter.incrementAndGet()
          val timestamp = System.nanoTime()
          resourceAcquisitionLog.put(id, s"acquired at $timestamp by thread ${Thread.currentThread().getName}")
          count
        }.mapError(_ => s"resource-error-$id")
        result <-
          if (id % 7 == 0) {
            failureCounter.incrementAndGet()
            Eru.fail(s"resource-$id failed")
          } else {
            Eru.succeed(s"resource-$id")
          }
      } yield result

      resourceEffect.ensure {
        Eru.effect {
          val count = cleanupCounter.incrementAndGet()
          val timestamp = System.nanoTime()
          cleanupLog.put(id, s"cleaned at $timestamp by thread ${Thread.currentThread().getName} (count: $count)")
        }
      }
    }

    // Test with 20 operations in parallel
    val operationCount = 20
    val effects = (1 to operationCount).map(createDiagnosticResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    println(s"ParSequence result: $result")

    println(s"Resource counter: ${resourceCounter.get()}")
    println(s"Cleanup counter: ${cleanupCounter.get()}")
    println(s"Failure counter: ${failureCounter.get()}")

    println("\nAcquisition log:")
    resourceAcquisitionLog.forEach((id, msg) => println(s"Resource $id: $msg"))

    println("\nCleanup log:")
    cleanupLog.forEach((id, msg) => println(s"Resource $id: $msg"))

    // Find which resources were acquired but not cleaned up
    val acquired = resourceAcquisitionLog.keySet().asScala.toSet
    val cleaned = cleanupLog.keySet().asScala.toSet
    val leaked = acquired -- cleaned

    if (leaked.nonEmpty) {
      println(s"\nLEAKED RESOURCES: $leaked")
      leaked.foreach { id =>
        println(s"Resource $id was acquired but never cleaned up")
        println(s"  Acquisition: ${resourceAcquisitionLog.get(id)}")
      }
    }

    // This test should help identify exactly which resources are not being cleaned up
    assertEquals(cleanupCounter.get(), resourceCounter.get(), s"Parallel cleanup failed. Leaked resources: $leaked")
  }

  test("parSequence early termination behavior with finalizers") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val startedOperations = new java.util.concurrent.ConcurrentHashMap[Int, Boolean]()
    val completedOperations = new java.util.concurrent.ConcurrentHashMap[Int, Boolean]()

    def createSlowResourceEffect(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect {
          startedOperations.put(id, true)
          resourceCounter.incrementAndGet()
        }.mapError(_.toString)
        // Add small delay to increase chance of interleaving
        _ <- EruRuntime.sleep(Duration.ofMillis(1)).mapError(_.toString)
        result <-
          if (id == 3) { // Fail early
            Eru.fail(s"early-failure-$id")
          } else {
            completedOperations.put(id, true)
            Eru.succeed(s"resource-$id")
          }
      } yield result

      resourceEffect.ensure {
        Eru.effect {
          cleanupCounter.incrementAndGet()
        }
      }
    }

    val operationCount = 10
    val effects = (1 to operationCount).map(createSlowResourceEffect).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    println(s"Started operations: ${startedOperations.size()}")
    println(s"Completed operations: ${completedOperations.size()}")
    println(s"Resource counter: ${resourceCounter.get()}")
    println(s"Cleanup counter: ${cleanupCounter.get()}")

    result match {
      case Result.Failure(error) => println(s"Failed as expected with: $error")
      case Result.Success(_) => println("Unexpectedly succeeded")
    }

    // The key insight: Are finalizers running for operations that started but didn't complete?
    assert(
      cleanupCounter.get() >= completedOperations.size(),
      "At least completed operations should have finalizers run"
    )

    // This is the critical test: Do ALL started operations get their finalizers executed?
    assertEquals(
      cleanupCounter.get(),
      resourceCounter.get(),
      "All started operations should have their finalizers executed, even if they don't complete normally"
    )
  }

  test("ensure finalizer exception handling doesn't prevent other finalizers") {
    val resourceCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val finalizerExceptionLog = mutable.ListBuffer.empty[String]

    def createResourceWithPotentiallyFailingFinalizer(id: Int): Eru[String, String] = {
      val resourceEffect: Eru[String, String] = for {
        _ <- Eru.effect(resourceCounter.incrementAndGet()).mapError(_.toString)
        result <- if (id % 5 == 0) Eru.fail(s"resource-$id failed") else Eru.succeed(s"resource-$id")
      } yield result

      resourceEffect.ensure {
        Eru.effect {
          if (id % 4 == 0) { // Some finalizers throw exceptions
            finalizerExceptionLog += s"Finalizer $id throwing exception"
            throw new RuntimeException(s"Finalizer exception for resource $id")
          } else {
            cleanupCounter.incrementAndGet()
          }
        }
      }
    }

    val operationCount = 12
    val effects = (1 to operationCount).map(createResourceWithPotentiallyFailingFinalizer).toList
    val result = EruRuntime.parSequence(effects).attempt.unsafeRunSync()

    println(s"Result with failing finalizers: $result")

    println(s"Resource counter: ${resourceCounter.get()}")
    println(s"Cleanup counter: ${cleanupCounter.get()}")
    println(s"Finalizer exceptions: ${finalizerExceptionLog.toList}")

    // Even with finalizer exceptions, the remaining finalizers should still execute
    val expectedCleanups = (1 to operationCount).count(_ % 4 != 0)
    assertEquals(
      cleanupCounter.get(),
      expectedCleanups,
      "Finalizer exceptions should not prevent other finalizers from running"
    )
  }
}
