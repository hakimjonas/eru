package net.ghoula.eru

import net.ghoula.eru.prelude.*

class MinimalTest extends munit.FunSuite {

  test("can import prelude") {
    val effect = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)
  }
}
