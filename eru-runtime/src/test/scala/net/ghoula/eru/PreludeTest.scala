package net.ghoula.eru

import munit.FunSuite

class PreludeTest extends FunSuite {

  test("unified prelude provides access to core functionality") {
    import net.ghoula.eru.prelude.*
    
    val effect = Eru.succeed(42)
    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("unified prelude provides access to fork extension method") {
    import net.ghoula.eru.prelude.*
    
    val effect = Eru.succeed(42)
    val fiber = effect.fork.unsafeRunSync()
    val exit = fiber.await.unsafeRunSync()
    
    exit match {
      case Exit.Success(v) => assertEquals(v, 42)
      case other => fail(s"Expected Success, got $other")
    }
  }
}