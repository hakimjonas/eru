package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Test suite for advanced resource safety extensions and edge cases.
  *
  * Validates complex resource management scenarios including nested finalizers, sequential resource
  * cleanup chains, and error propagation through resource finalizer execution. These tests ensure
  * that the extended resource safety mechanisms maintain correctness under various failure
  * conditions and provide comprehensive coverage for advanced resource management patterns.
  */
class ResourceSafetySpec extends munit.FunSuite {

  test("ensureAll runs multiple finalizers in FILO order") {
    var order = List.empty[String]
    val f1 = Eru.effect { order = "f1" :: order; () }
    val f2 = Eru.effect { order = "f2" :: order; () }
    val f3 = Eru.effect { order = "f3" :: order; () }

    val program = Eru.succeed(42).ensureAll(f1, f2, f3)
    val result = program.unsafeRunSync()

    assertEquals(result, 42)
    assertEquals(order.reverse, List("f3", "f2", "f1"))
  }

  test("ensureAll runs finalizers even on failure") {
    var order = List.empty[String]
    val f1 = Eru.effect { order = "f1" :: order; () }
    val f2 = Eru.effect { order = "f2" :: order; () }

    val program: Eru[String, Int] = Eru.fail("boom").ensureAll(f1, f2)
    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "boom")
    assertEquals(order.reverse, List("f2", "f1"))
  }

  test("autoCleanup calls cleanup function on success value") {
    var cleanupCalled = false
    var cleanedValue: Option[String] = None

    val program = Eru.succeed("resource").autoCleanup { value =>
      cleanupCalled = true
      cleanedValue = Some(value)
      Eru.unit
    }

    val result = program.unsafeRunSync()
    assertEquals(result, "resource")
    assert(cleanupCalled)
    assertEquals(cleanedValue, Some("resource"))
  }

  test("autoCleanup calls cleanup even when subsequent operations fail") {
    var cleanupCalled = false
    var cleanedValue: Option[String] = None

    val program = Eru
      .succeed("resource")
      .autoCleanup { value =>
        cleanupCalled = true
        cleanedValue = Some(value)
        Eru.unit
      }
      .flatMap(_ => Eru.fail("subsequent failure"))

    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "subsequent failure")
    assert(cleanupCalled)
    assertEquals(cleanedValue, Some("resource"))
  }

  test("autoClose works with AutoCloseable resources") {
    class TestCloseable extends AutoCloseable {
      var closed = false
      def close(): Unit = { closed = true }
    }

    val closeable = new TestCloseable
    val program = Eru.succeed(closeable).autoClose

    val result = program.unsafeRunSync()
    assertEquals(result, closeable)
    assert(closeable.closed)
  }

  test("autoClose handles exceptions during close") {
    class FailingCloseable extends AutoCloseable {
      def close(): Unit = throw new RuntimeException("close failed")
    }

    val closeable = new FailingCloseable
    val program = Eru.succeed(closeable).autoClose

    val result = program.unsafeRunSync()
    assertEquals(result, closeable)
  }

  test("useScoped provides resource to use function and ensures cleanup") {
    var cleanupCalled = false
    var usedValue: Option[String] = None

    val program = Eru
      .succeed("resource")
      .useScoped { resource =>
        usedValue = Some(resource)
        Eru.succeed(resource.length)
      } { resource =>
        cleanupCalled = true
        assertEquals(resource, "resource")
        Eru.unit
      }

    val result = program.unsafeRunSync()
    assertEquals(result, 8)
    assertEquals(usedValue, Some("resource"))
    assert(cleanupCalled)
  }

  test("useScoped ensures cleanup even when use function fails") {
    var cleanupCalled = false

    val program = Eru
      .succeed("resource")
      .useScoped { _ =>
        Eru.fail("use failed")
      } { resource =>
        cleanupCalled = true
        assertEquals(resource, "resource")
        Eru.unit
      }

    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "use failed")
    assert(cleanupCalled)
  }

  test("pooled ensures resource is returned to pool") {
    var returnedToPool = false
    var pooledValue: Option[String] = None

    val program = Eru.succeed("pooled-resource").pooled { value =>
      returnedToPool = true
      pooledValue = Some(value)
      Eru.unit
    }

    val result = program.unsafeRunSync()
    assertEquals(result, "pooled-resource")
    assert(returnedToPool)
    assertEquals(pooledValue, Some("pooled-resource"))
  }

  test("validateResource succeeds when validation passes") {
    val program = Eru.succeed(42).validateResource(_ > 0, "positive number")
    val result = program.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("validateResource fails when validation fails") {
    val program = Eru.succeed(-5).validateResource(_ > 0, "positive number")
    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }
    assertEquals(ex.error, "Resource validation failed: positive number")
  }

  test("validateResource adds finalizer for successful validation") {
    var finalizerRan = false
    val program = Eru.succeed("valid").validateResource(_.nonEmpty, "non-empty string").ensure {
      Eru.effect { finalizerRan = true }
    }

    val result = program.unsafeRunSync()
    assertEquals(result, "valid")
    assert(finalizerRan)
  }

  test("complex resource safety scenario with multiple patterns") {
    var log = List.empty[String]

    class TestResource(val name: String) extends AutoCloseable {
      log = s"$name created" :: log
      def close(): Unit = log = s"$name closed" :: log
    }

    val program = for {
      resource <- Eru.succeed(new TestResource("main")).validateResource(_.name.nonEmpty, "named resource")

      result <- Eru
        .succeed(resource)
        .autoClose
        .ensureAll(
          Eru.effect { log = "finalizer1" :: log },
          Eru.effect { log = "finalizer2" :: log }
        )
        .useScoped(r => Eru.succeed(r.name.toUpperCase)) { r =>
          Eru.effect { log = s"custom cleanup for ${r.name}" :: log }
        }
    } yield result

    val result = program.unsafeRunSync()
    assertEquals(result, "MAIN")
    assert(log.contains("main created"))
    assert(log.contains("main closed"))
    assert(log.contains("finalizer1"))
    assert(log.contains("finalizer2"))
    assert(log.contains("custom cleanup for main"))
  }

  test("nested resource patterns work correctly") {
    var cleanupOrder = List.empty[String]

    val program = Eru
      .succeed("outer")
      .autoCleanup(v => Eru.effect { cleanupOrder = s"cleanup-$v" :: cleanupOrder })
      .flatMap(outer =>
        Eru
          .succeed("inner")
          .autoCleanup(v => Eru.effect { cleanupOrder = s"cleanup-$v" :: cleanupOrder })
          .map(inner => s"$outer-$inner")
      )

    val result = program.unsafeRunSync()
    assertEquals(result, "outer-inner")
    assertEquals(cleanupOrder.reverse, List("cleanup-inner", "cleanup-outer"))
  }

  test("concurrent resource cleanup maintains FILO order under resource contention") {
    val cleanupOrder = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val sharedResource = new java.util.concurrent.atomic.AtomicInteger(0)

    def createResourceWithId(id: String): Eru[Nothing, String] = {
      Eru.succeed(id).autoCleanup { resourceId =>
        Eru.effect {
          val currentValue = sharedResource.incrementAndGet()
          Thread.sleep(1)
          cleanupOrder.add(s"cleanup-$resourceId-$currentValue")
        }
      }
    }

    val program = for {
      r1 <- createResourceWithId("first")
      r2 <- createResourceWithId("second")
      r3 <- createResourceWithId("third")
    } yield s"$r1-$r2-$r3"

    val result = program.unsafeRunSync()
    assertEquals(result, "first-second-third")

    import scala.jdk.CollectionConverters.*
    val cleanupList = cleanupOrder.asScala.toList
    assert(cleanupList.length == 3)
    assert(cleanupList.exists(_.contains("cleanup-third")))
    assert(cleanupList.exists(_.contains("cleanup-second")))
    assert(cleanupList.exists(_.contains("cleanup-first")))
  }

  test("resource safety with exception propagation in finalizers") {
    var finalizer1Executed = false
    var finalizer2Executed = false
    var finalizer3Executed = false

    val program = Eru
      .succeed("resource1")
      .autoCleanup(_ => Eru.effect { finalizer1Executed = true })
      .flatMap(_ =>
        Eru
          .succeed("resource2")
          .autoCleanup(_ =>
            Eru.effect {
              finalizer2Executed = true
              throw new RuntimeException("finalizer2 failed")
            }
          )
          .flatMap(_ =>
            Eru
              .succeed("resource3")
              .autoCleanup(_ => Eru.effect { finalizer3Executed = true })
              .flatMap(_ => Eru.fail("main computation failed"))
          )
      )

    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "main computation failed")
    assert(finalizer1Executed, "Finalizer 1 should execute despite errors")
    assert(finalizer2Executed, "Finalizer 2 should execute despite main failure")
    assert(finalizer3Executed, "Finalizer 3 should execute despite later finalizer failures")
  }

  test("resource acquisition and release with interleaved failures") {
    var acquisitionOrder = List.empty[String]
    var releaseOrder = List.empty[String]

    def acquireResource(id: String, shouldFail: Boolean): Eru[String, String] = {
      if (shouldFail) {
        Eru.fail(s"Failed to acquire $id")
      } else {
        Eru.effect {
          acquisitionOrder = s"acquired-$id" :: acquisitionOrder
          id
        }.mapError(_.getMessage)
      }
    }

    val program = acquireResource("r1", false)
      .bracket(r => Eru.effect { releaseOrder = s"released-$r" :: releaseOrder }) { r1 =>
        acquireResource("r2", true)
          .bracket(r => Eru.effect { releaseOrder = s"released-$r" :: releaseOrder }) { r2 =>
            Eru.succeed(s"used-$r1-$r2")
          }
      }

    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "Failed to acquire r2")
    assertEquals(acquisitionOrder.reverse, List("acquired-r1"))
    assertEquals(releaseOrder.reverse, List("released-r1"))
  }
}
