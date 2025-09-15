package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Test suite for EruFiber implementation and fiber lifecycle management.
  *
  * Validates the concrete EruFiber implementation including fiber creation, state management,
  * interruption handling, and resource cleanup. These tests ensure that the EruFiber provides
  * reliable implementation of the Fiber interface with correct semantics for concurrent execution
  * and proper integration with the effect system's resource safety guarantees.
  */
class EruFiberSpec extends munit.FunSuite {

  test("EruFiber creates fibers with unique IDs") {
    val fiber1 = EruFiber.completed(Exit.Success(42), Nil)
    val fiber2 = EruFiber.completed(Exit.Success(24), Nil)

    assertNotEquals(fiber1.id, fiber2.id)
  }

  test("EruFiber.withId creates fiber with specified ID") {
    val id = FiberId.fresh()
    val fiber = EruFiber.withId(id, Exit.Success(42), Nil)

    assertEquals(fiber.id, id)
  }

  test("EruFiber equality is based on ID") {
    val id = FiberId.fresh()
    val fiber1 = EruFiber.withId(id, Exit.Success(42), Nil)
    val fiber2 = EruFiber.withId(id, Exit.Success(24), Nil)

    assertEquals(fiber1, fiber2)
  }

  test("EruFiber inequality for different IDs") {
    val fiber1 = EruFiber.completed(Exit.Success(42), Nil)
    val fiber2 = EruFiber.completed(Exit.Success(42), Nil)

    assertNotEquals(fiber1, fiber2)
  }

  test("EruFiber hashCode is based on ID") {
    val id = FiberId.fresh()
    val fiber1 = EruFiber.withId(id, Exit.Success(42), Nil)
    val fiber2 = EruFiber.withId(id, Exit.Success("hello"), Nil)

    assertEquals(fiber1.hashCode(), fiber2.hashCode())
  }

  test("EruFiber toString includes ID") {
    val id = FiberId.fresh()
    val fiber = EruFiber.withId(id, Exit.Success(42), Nil)
    val expected = s"EruFiber(FiberId($id))"

    assertEquals(fiber.toString, expected)
  }

  test("EruFiber.await creates Await effect") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val awaitEffect = fiber.await

    val _: Eru[Nothing, Exit[Nothing, Int]] = awaitEffect
  }

  test("EruFiber.interrupt(cause) creates effect") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val interruptEffect = fiber.interrupt(InterruptCause.Cancelled(Some("test")))

    val _: Eru[Nothing, Unit] = interruptEffect
  }

  test("EruFiber.interrupt() creates effect with UserInterrupt") {
    val fiber = EruFiber.completed(Exit.Success(42), Nil)
    val interruptEffect = fiber.interrupt

    val _: Eru[Nothing, Unit] = interruptEffect
  }

  test("EruFiber type parameters are covariant") {
    val fiber: EruFiber[Nothing, Int] = EruFiber.completed(Exit.Success(42), Nil)

    val widerFiber: EruFiber[Any, Any] = fiber
    assertEquals(fiber.id, widerFiber.id)
  }
}
