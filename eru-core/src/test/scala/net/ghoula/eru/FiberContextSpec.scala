package net.ghoula.eru

import munit.FunSuite

/** Test suite for fiber context and state management.
  *
  * Validates the behavior of FiberState enumeration, FiberContext operations, and related fiber
  * management primitives. These tests ensure that fiber state transitions are correct and that
  * context information is properly maintained throughout fiber lifecycle operations, supporting
  * reliable concurrent execution in the runtime system.
  */
class FiberContextSpec extends FunSuite {

  test("FiberState values are distinct") {
    assertEquals(FiberState.Running, FiberState.Running)
    assertNotEquals(FiberState.Running, FiberState.Suspended)
    assertNotEquals(FiberState.Running, FiberState.Done)
    assertNotEquals(FiberState.Running, FiberState.Interrupted)
  }

  test("FiberContext.root creates root context with no parent") {
    val context = FiberContext.root()

    assertEquals(context.state, FiberState.Running)
    assertEquals(context.parentId, None)
    assertNotEquals(context.id, FiberId.fresh()) // Just verify we have some ID
    assert(context.startTime > 0)
  }

  test("FiberContext.child creates child context with parent") {
    val parentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assertEquals(context.state, FiberState.Running)
    assertEquals(context.parentId, Some(parentId))
    assertNotEquals(context.id, FiberId.fresh()) // Just verify we have some ID
    assertNotEquals(context.id, parentId)
    assert(context.startTime > 0)
  }

  test("FiberContext.withState updates state") {
    val context = FiberContext.root()
    val updatedContext = context.withState(FiberState.Suspended)

    assertEquals(updatedContext.state, FiberState.Suspended)
    assertEquals(updatedContext.id, context.id)
    assertEquals(updatedContext.parentId, context.parentId)
    assertEquals(updatedContext.startTime, context.startTime)
  }

  test("FiberContext.withParent updates parent") {
    val context = FiberContext.root()
    val parentId = FiberId.fresh()
    val updatedContext = context.withParent(parentId)

    assertEquals(updatedContext.parentId, Some(parentId))
    assertEquals(updatedContext.id, context.id)
    assertEquals(updatedContext.state, context.state)
    assertEquals(updatedContext.startTime, context.startTime)
  }

  test("FiberContext.isChildOf returns true for correct parent") {
    val parentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assert(context.isChildOf(parentId))
  }

  test("FiberContext.isChildOf returns false for incorrect parent") {
    val parentId = FiberId.fresh()
    val wrongParentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assert(!context.isChildOf(wrongParentId))
  }

  test("FiberContext.isChildOf returns false for root context") {
    val context = FiberContext.root()
    val someId = FiberId.fresh()

    assert(!context.isChildOf(someId))
  }

  test("FiberContext.ageNanos returns positive age") {
    val context = FiberContext.root()

    // Age should be positive and increasing
    val age1 = context.ageNanos
    assert(age1 >= 0)

    // Small delay to ensure time passes
    Thread.sleep(1)
    val age2 = context.ageNanos
    assert(age2 > age1)
  }

  test("Multiple root contexts have different IDs") {
    val context1 = FiberContext.root()
    val context2 = FiberContext.root()

    assertNotEquals(context1.id, context2.id)
  }

  test("Multiple child contexts with same parent have different IDs") {
    val parentId = FiberId.fresh()
    val context1 = FiberContext.child(parentId)
    val context2 = FiberContext.child(parentId)

    assertNotEquals(context1.id, context2.id)
    assertEquals(context1.parentId, context2.parentId)
  }

  test("FiberContext immutability - withState doesn't mutate original") {
    val originalContext = FiberContext.root()
    val originalState = originalContext.state
    val updatedContext = originalContext.withState(FiberState.Done)

    assertEquals(originalContext.state, originalState) // Original unchanged
    assertEquals(updatedContext.state, FiberState.Done) // New one updated
  }

  test("FiberContext immutability - withParent doesn't mutate original") {
    val originalContext = FiberContext.root()
    val originalParent = originalContext.parentId
    val newParentId = FiberId.fresh()
    val updatedContext = originalContext.withParent(newParentId)

    assertEquals(originalContext.parentId, originalParent) // Original unchanged
    assertEquals(updatedContext.parentId, Some(newParentId)) // New one updated
  }
}
