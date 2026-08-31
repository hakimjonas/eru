package net.ghoula.eru

import net.ghoula.eru.prelude.*

class MinimalSpec extends munit.FunSuite {

  test("can import prelude") {
    val effect = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)
  }
}
