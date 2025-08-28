package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

final class PublicSurfaceSpec extends FunSuite {
  test("prelude provides core constructors and combinators") {
    val e = Eru.succeed(42).map(_ + 1).flatMap(n => Eru.succeed(n * 2))
    val exit = e.runExit()
    exit match {
      case Exit.Success(v) => assertEquals(v, 86)
      case _ => fail("expected success")
    }
  }

  test("internal names are not pulled in by the prelude import") {
    assert(!scala.compiletime.testing.typeChecks("import net.ghoula.eru.prelude.*; val _: PreludeApi.type = ???"))
    assert(
      !scala.compiletime.testing.typeChecks("import net.ghoula.eru.prelude.*; val _: RuntimePreludeApi.type = ???")
    )
  }
}
