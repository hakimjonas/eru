package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for the Exit data type and its operations.
  *
  * Validates all fundamental operations of Exit[E, A] including construction, transformation,
  * pattern matching, and combinatorial logic. The Exit type represents the final outcome of fiber
  * execution, encoding success, failure, and interruption states with complete type safety and
  * providing the foundation for fiber coordination and error propagation.
  *
  * FiberId values are always non-negative: the MSB is kept at 0 by the ID layout.
  */
class ExitSpec extends munit.FunSuite {

  /** Validates that Exit.Success properly holds the provided value.
    *
    * Tests that the Success variant of Exit correctly stores and allows pattern matching access to
    * the contained value.
    */
  test("Exit.Success holds the value") {
    val ex: Exit[Nothing, Int] = Exit.Success(42)
    ex match {
      case Exit.Success(v) => assertEquals(v, 42)
      case _ => fail("expected Success")
    }
  }

  /** Validates that Exit.Failure properly holds the provided error.
    *
    * Tests that the Failure variant of Exit correctly stores and allows pattern matching access to
    * the contained error.
    */
  test("Exit.Failure holds the error") {
    val ex: Exit[String, Nothing] = Exit.Failure("boom")
    ex match {
      case Exit.Failure(e) => assertEquals(e, "boom")
      case _ => fail("expected Failure")
    }
  }

  /** Validates that Exit.Die properly holds the provided throwable.
    *
    * Tests that the Die variant of Exit correctly stores and allows pattern matching access to the
    * contained throwable.
    */
  test("Exit.Die holds the throwable") {
    val t = new RuntimeException("x")
    val ex: Exit[Nothing, Nothing] = Exit.Die(t)
    ex match {
      case Exit.Die(tt) => assertEquals(tt, t)
      case _ => fail("expected Die")
    }
  }

  /** Validates that Exit.Interrupt properly holds fiber ID and interrupt cause.
    *
    * Tests that the Interrupt variant of Exit correctly stores and allows pattern matching access
    * to both the fiber ID and interrupt cause.
    */
  test("Exit.Interrupt holds fiber id and cause") {
    val fid = FiberId.fresh()
    val ex: Exit[Nothing, Nothing] = Exit.Interrupt(fid, InterruptCause.Cancelled())
    ex match {
      case Exit.Interrupt(id, cause) =>
        assertEquals(id, fid)
        assertEquals(cause, InterruptCause.Cancelled())
      case _ => fail("expected Interrupt")
    }
  }

  test("FiberId.fresh generates unique identifiers") {
    val id1 = FiberId.fresh()
    val id2 = FiberId.fresh()
    val id3 = FiberId.fresh()

    assertNotEquals(id1, id2)
    assertNotEquals(id2, id3)
    assertNotEquals(id1, id3)
  }

  test("FiberId.toLong returns underlying Long value") {
    val id = FiberId.fresh()
    val longValue = id.toLong

    assert(longValue >= 0L)
  }

  test("InterruptCause.Cancelled with optional reason") {
    val cause1 = InterruptCause.Cancelled()
    val cause2 = InterruptCause.Cancelled(Some("User requested"))

    cause1 match {
      case InterruptCause.Cancelled(reason) => assert(reason.isEmpty)
      case _ => fail("expected Cancelled")
    }

    cause2 match {
      case InterruptCause.Cancelled(reason) => assertEquals(reason, Some("User requested"))
      case _ => fail("expected Cancelled")
    }
  }

  test("InterruptCause.Timeout with duration and optional operation") {
    import java.time.Duration

    val duration = Duration.ofSeconds(30)
    val cause1 = InterruptCause.Timeout(duration)
    val cause2 = InterruptCause.Timeout(duration, Some("database query"))

    cause1 match {
      case InterruptCause.Timeout(d, operation) =>
        assertEquals(d, duration)
        assert(operation.isEmpty)
      case _ => fail("expected Timeout")
    }

    cause2 match {
      case InterruptCause.Timeout(d, operation) =>
        assertEquals(d, duration)
        assertEquals(operation, Some("database query"))
      case _ => fail("expected Timeout")
    }
  }

  test("InterruptCause.ParentTerminated with parent info") {
    val parentId = FiberId.fresh()
    val parentExit = Exit.Success(42)
    val cause = InterruptCause.ParentTerminated(parentId, parentExit)

    cause match {
      case InterruptCause.ParentTerminated(pid, pexit) =>
        assertEquals(pid, parentId)
        assertEquals(pexit, parentExit)
      case _ => fail("expected ParentTerminated")
    }
  }

  test("InterruptCause.ResourceExhausted with resource and optional details") {
    val cause1 = InterruptCause.ResourceExhausted("memory")
    val cause2 = InterruptCause.ResourceExhausted("memory", Some("JVM heap usage exceeded 90%"))

    cause1 match {
      case InterruptCause.ResourceExhausted(resource, details) =>
        assertEquals(resource, "memory")
        assert(details.isEmpty)
      case _ => fail("expected ResourceExhausted")
    }

    cause2 match {
      case InterruptCause.ResourceExhausted(resource, details) =>
        assertEquals(resource, "memory")
        assertEquals(details, Some("JVM heap usage exceeded 90%"))
      case _ => fail("expected ResourceExhausted")
    }
  }

  test("InterruptCause.Custom with name, context, and metadata") {
    val cause1 = InterruptCause.Custom("circuit_breaker_open")
    val cause2 = InterruptCause.Custom(
      name = "scheduled_maintenance",
      context = Some("System entering maintenance window"),
      metadata = Map("maintenance_id" -> "MAINT-2023-001", "scheduled_time" -> "2023-01-15T02:00:00Z")
    )

    cause1 match {
      case InterruptCause.Custom(name, context, metadata) =>
        assertEquals(name, "circuit_breaker_open")
        assert(context.isEmpty)
        assert(metadata.isEmpty)
      case _ => fail("expected Custom")
    }

    cause2 match {
      case InterruptCause.Custom(name, context, metadata) =>
        assertEquals(name, "scheduled_maintenance")
        assertEquals(context, Some("System entering maintenance window"))
        assertEquals(metadata.size, 2)
        assertEquals(metadata("maintenance_id"), "MAINT-2023-001")
      case _ => fail("expected Custom")
    }
  }

  test("Complex InterruptCause pattern matching") {
    import java.time.Duration

    val causes = List(
      InterruptCause.Cancelled(Some("user request")),
      InterruptCause.Timeout(Duration.ofMinutes(5), Some("API call")),
      InterruptCause.ResourceExhausted("file descriptors", Some("limit reached")),
      InterruptCause.Custom("custom_reason", Some("context"), Map("key" -> "value"))
    )

    val results = causes.map {
      case InterruptCause.Cancelled(reason) => s"cancelled: ${reason.getOrElse("none")}"
      case InterruptCause.Timeout(duration, operation) =>
        s"timeout: ${duration.toSeconds}s, op: ${operation.getOrElse("unknown")}"
      case InterruptCause.ResourceExhausted(resource, details) =>
        s"resource: $resource, details: ${details.getOrElse("none")}"
      case InterruptCause.Custom(name, context, metadata) =>
        s"custom: $name, ctx: ${context.getOrElse("none")}, meta: ${metadata.size}"
      case InterruptCause.ParentTerminated(_, _) => "parent terminated"
    }

    assertEquals(
      results,
      List(
        "cancelled: user request",
        "timeout: 300s, op: API call",
        "resource: file descriptors, details: limit reached",
        "custom: custom_reason, ctx: context, meta: 1"
      )
    )
  }
}
