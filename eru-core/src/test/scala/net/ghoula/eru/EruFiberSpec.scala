package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.CorePrelude.*

class EruFiberSpec extends FunSuite {

  test("EruFiber.fresh creates fiber with unique ID") {
    val fiber1 = EruFiber.fresh[String, Int]
    val fiber2 = EruFiber.fresh[String, Int]

    assertNotEquals(fiber1.id, fiber2.id)
  }

  test("EruFiber.withId creates fiber with specified ID") {
    val id = FiberId.fresh()
    val fiber = EruFiber.withId[String, Int](id)

    assertEquals(fiber.id, id)
  }

  test("EruFiber equality is based on ID") {
    val id = FiberId.fresh()
    val fiber1 = EruFiber.withId[String, Int](id)
    val fiber2 = EruFiber.withId[String, Int](id) // Same types and ID

    assertEquals(fiber1, fiber2) // Should be equal because same ID
  }

  test("EruFiber inequality for different IDs") {
    val fiber1 = EruFiber.fresh[String, Int]
    val fiber2 = EruFiber.fresh[String, Int]

    assertNotEquals(fiber1, fiber2)
  }

  test("EruFiber hashCode is based on ID") {
    val id = FiberId.fresh()
    val fiber1 = EruFiber.withId[String, Int](id)
    val fiber2 = EruFiber.withId[Boolean, String](id)

    assertEquals(fiber1.hashCode(), fiber2.hashCode())
  }

  test("EruFiber toString includes ID") {
    val id = FiberId.fresh()
    val fiber = EruFiber.withId[String, Int](id)
    val expected = s"EruFiber(FiberId($id))"

    assertEquals(fiber.toString, expected)
  }

  test("EruFiber.await creates Await effect") {
    val fiber = EruFiber.fresh[String, Int]
    val awaitEffect = fiber.await

    // The await should be pure - it constructs the effect but doesn't execute
    // In Phase 1, we can't actually test execution since the interpreter throws
    // This tests the construction-time behavior
    val _: Eru[String, Exit[String, Int]] = awaitEffect
  }

  test("EruFiber.interrupt(cause) creates effect") {
    val fiber = EruFiber.fresh[String, Int]
    val interruptEffect = fiber.interrupt(InterruptCause.Cancelled(Some("test")))

    // The interrupt should be pure - it constructs the effect but doesn't execute
    val _: Eru[Nothing, Unit] = interruptEffect
  }

  test("EruFiber.interrupt() creates effect with UserInterrupt") {
    val fiber = EruFiber.fresh[String, Int]
    val interruptEffect = fiber.interrupt

    // The interrupt should be pure - it constructs the effect but doesn't execute
    val _: Eru[Nothing, Unit] = interruptEffect
  }

  test("EruFiber type parameters are covariant") {
    val fiber: EruFiber[String, Int] = EruFiber.fresh[String, Int]

    // This should compile due to covariance
    val widerFiber: EruFiber[Any, Any] = fiber
    assertEquals(fiber.id, widerFiber.id)
  }
}
