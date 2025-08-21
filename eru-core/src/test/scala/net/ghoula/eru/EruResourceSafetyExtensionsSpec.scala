package net.ghoula.eru

import munit.FunSuite

import scala.collection.mutable.ListBuffer

import net.ghoula.eru.CorePrelude.*

class EruResourceSafetyExtensionsSpec extends FunSuite {

  test("ensureAll runs multiple finalizers in FILO order") {
    val order = ListBuffer.empty[String]
    val f1 = Eru.effect { order += "f1"; () }
    val f2 = Eru.effect { order += "f2"; () }
    val f3 = Eru.effect { order += "f3"; () }

    val program = Eru.succeed(42).ensureAll(f1, f2, f3)
    val result = program.unsafeRunSync()

    assertEquals(result, 42)
    assertEquals(order.toList, List("f3", "f2", "f1")) // FILO order
  }

  test("ensureAll runs finalizers even on failure") {
    val order = ListBuffer.empty[String]
    val f1 = Eru.effect { order += "f1"; () }
    val f2 = Eru.effect { order += "f2"; () }

    val program: Eru[String, Int] = Eru.fail("boom").ensureAll(f1, f2)
    val ex = intercept[EruException[String]] {
      program.unsafeRunSync()
    }

    assertEquals(ex.error, "boom")
    assertEquals(order.toList, List("f2", "f1")) // Finalizers still ran in FILO order
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

    // Should succeed despite close() throwing
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
    assertEquals(result, 8) // "resource".length
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

  test("shareResource delegates to autoCleanup for now") {
    var cleanupCalled = false
    var sharedValue: Option[String] = None

    val program = Eru.succeed("shared").shareResource { value =>
      cleanupCalled = true
      sharedValue = Some(value)
      Eru.unit
    }

    val result = program.unsafeRunSync()
    assertEquals(result, "shared")
    assert(cleanupCalled)
    assertEquals(sharedValue, Some("shared"))
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
    val log = ListBuffer.empty[String]

    class TestResource(val name: String) extends AutoCloseable {
      log += s"$name created"
      def close(): Unit = log += s"$name closed"
    }

    val program = for {
      // Create resource with validation
      resource <- Eru.succeed(new TestResource("main")).validateResource(_.name.nonEmpty, "named resource")

      // Use resource with auto-cleanup and additional finalizers
      result <- Eru
        .succeed(resource)
        .autoClose
        .ensureAll(
          Eru.effect { log += "finalizer1" },
          Eru.effect { log += "finalizer2" }
        )
        .useScoped(r => Eru.succeed(r.name.toUpperCase)) { r =>
          Eru.effect { log += s"custom cleanup for ${r.name}" }
        }
    } yield result

    val result = program.unsafeRunSync()
    assertEquals(result, "MAIN")

    // Check that all cleanup operations happened
    val logList = log.toList
    assert(logList.contains("main created"))
    assert(logList.contains("main closed"))
    assert(logList.contains("finalizer1"))
    assert(logList.contains("finalizer2"))
    assert(logList.contains("custom cleanup for main"))
  }

  test("nested resource patterns work correctly") {
    val cleanupOrder = ListBuffer.empty[String]

    val program = Eru
      .succeed("outer")
      .autoCleanup(v => Eru.effect { cleanupOrder += s"cleanup-$v" })
      .flatMap(outer =>
        Eru
          .succeed("inner")
          .autoCleanup(v => Eru.effect { cleanupOrder += s"cleanup-$v" })
          .map(inner => s"$outer-$inner")
      )

    val result = program.unsafeRunSync()
    assertEquals(result, "outer-inner")
    assertEquals(cleanupOrder.toList, List("cleanup-inner", "cleanup-outer"))
  }
}
