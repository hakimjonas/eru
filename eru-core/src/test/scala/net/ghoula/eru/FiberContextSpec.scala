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

  /** Validates that FiberState values are distinct from each other.
    *
    * Tests that each FiberState enumeration value has unique identity and proper equality semantics
    * for state comparisons.
    */
  test("FiberState values are distinct") {
    assertEquals(FiberState.Running, FiberState.Running)
    assertNotEquals(FiberState.Running, FiberState.Suspended)
    assertNotEquals(FiberState.Running, FiberState.Done)
    assertNotEquals(FiberState.Running, FiberState.Interrupted)
  }

  /** Validates that FiberContext.root creates root context with no parent.
    *
    * Tests that root contexts are properly initialized with Running state, no parent, unique ID,
    * and valid start time.
    */
  test("FiberContext.root creates root context with no parent") {
    val context = FiberContext.root()

    assertEquals(context.state, FiberState.Running)
    assertEquals(context.parentId, None)
    assertNotEquals(context.id, FiberId.fresh())
    assert(context.startTime > 0)
  }

  /** Validates that FiberContext.child creates child context with parent reference.
    *
    * Tests that child contexts are properly initialized with parent ID, Running state, unique ID
    * distinct from parent, and valid start time.
    */
  test("FiberContext.child creates child context with parent") {
    val parentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assertEquals(context.state, FiberState.Running)
    assertEquals(context.parentId, Some(parentId))
    assertNotEquals(context.id, FiberId.fresh())
    assertNotEquals(context.id, parentId)
    assert(context.startTime > 0)
  }

  /** Validates that FiberContext.withState updates state correctly.
    *
    * Tests that state updates create new context instances with modified state while preserving all
    * other context properties.
    */
  test("FiberContext.withState updates state") {
    val context = FiberContext.root()
    val updatedContext = context.withState(FiberState.Suspended)

    assertEquals(updatedContext.state, FiberState.Suspended)
    assertEquals(updatedContext.id, context.id)
    assertEquals(updatedContext.parentId, context.parentId)
    assertEquals(updatedContext.startTime, context.startTime)
  }

  /** Validates that FiberContext.withParent updates parent reference.
    *
    * Tests that parent updates create new context instances with modified parent while preserving
    * all other context properties.
    */
  test("FiberContext.withParent updates parent") {
    val context = FiberContext.root()
    val parentId = FiberId.fresh()
    val updatedContext = context.withParent(parentId)

    assertEquals(updatedContext.parentId, Some(parentId))
    assertEquals(updatedContext.id, context.id)
    assertEquals(updatedContext.state, context.state)
    assertEquals(updatedContext.startTime, context.startTime)
  }

  /** Validates that FiberContext.isChildOf returns true for correct parent.
    *
    * Tests that child contexts correctly identify their parent fiber through the isChildOf
    * predicate.
    */
  test("FiberContext.isChildOf returns true for correct parent") {
    val parentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assert(context.isChildOf(parentId))
  }

  /** Validates that FiberContext.isChildOf returns false for incorrect parent.
    *
    * Tests that child contexts correctly reject non-parent fibers through the isChildOf predicate.
    */
  test("FiberContext.isChildOf returns false for incorrect parent") {
    val parentId = FiberId.fresh()
    val wrongParentId = FiberId.fresh()
    val context = FiberContext.child(parentId)

    assert(!context.isChildOf(wrongParentId))
  }

  /** Validates that FiberContext.isChildOf returns false for root context.
    *
    * Tests that root contexts correctly indicate they have no parent through the isChildOf
    * predicate.
    */
  test("FiberContext.isChildOf returns false for root context") {
    val context = FiberContext.root()
    val someId = FiberId.fresh()

    assert(!context.isChildOf(someId))
  }

  /** Validates that FiberContext.ageNanos returns positive and increasing age.
    *
    * Tests that fiber age calculation produces positive values that increase over time since fiber
    * creation.
    */
  test("FiberContext.ageNanos returns positive age") {
    val context = FiberContext.root()

    val age1 = context.ageNanos
    assert(age1 >= 0)

    // Small delay to ensure time passes for age calculation
    Thread.sleep(1) // Keep minimal real delay since this tests actual time
    val age2 = context.ageNanos
    assert(age2 > age1)
  }

  /** Validates that multiple root contexts have different IDs.
    *
    * Tests that each root context creation produces a unique fiber identifier, ensuring proper
    * isolation between independent fibers.
    */
  test("Multiple root contexts have different IDs") {
    val context1 = FiberContext.root()
    val context2 = FiberContext.root()

    assertNotEquals(context1.id, context2.id)
  }

  /** Validates that multiple child contexts with same parent have different IDs.
    *
    * Tests that child contexts are assigned unique identifiers even when sharing the same parent,
    * ensuring proper fiber distinction.
    */
  test("Multiple child contexts with same parent have different IDs") {
    val parentId = FiberId.fresh()
    val context1 = FiberContext.child(parentId)
    val context2 = FiberContext.child(parentId)

    assertNotEquals(context1.id, context2.id)
    assertEquals(context1.parentId, context2.parentId)
  }

  /** Validates FiberContext immutability - withState doesn't mutate original.
    *
    * Tests that state updates preserve immutability by creating new instances rather than modifying
    * existing contexts.
    */
  test("FiberContext immutability - withState doesn't mutate original") {
    val originalContext = FiberContext.root()
    val originalState = originalContext.state
    val updatedContext = originalContext.withState(FiberState.Done)

    assertEquals(originalContext.state, originalState)
    assertEquals(updatedContext.state, FiberState.Done)
  }

  /** Validates FiberContext immutability - withParent doesn't mutate original.
    *
    * Tests that parent updates preserve immutability by creating new instances rather than
    * modifying existing contexts.
    */
  test("FiberContext immutability - withParent doesn't mutate original") {
    val originalContext = FiberContext.root()
    val originalParent = originalContext.parentId
    val newParentId = FiberId.fresh()
    val updatedContext = originalContext.withParent(newParentId)

    assertEquals(originalContext.parentId, originalParent)
    assertEquals(updatedContext.parentId, Some(newParentId))
  }
}
