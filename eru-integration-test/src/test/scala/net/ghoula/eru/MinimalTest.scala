package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.prelude.*

class MinimalTest extends FunSuite {

  test("can import prelude") {
    val effect = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)
  }
}
