package userland

import munit.FunSuite

import net.ghoula.eru.prelude.*

/** Integration test suite for the Eru public API surface and prelude functionality.
  *
  * Validates that all essential Eru functionality is properly exposed through the public API and
  * that the prelude import provides access to core operations without additional imports. These
  * tests serve as both API validation and documentation of the intended user experience, ensuring
  * that the public surface supports the Radical Ergonomics pillar of the Eru framework.
  */
final class PublicSurfaceSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.create()

  /** Validates that the prelude import provides access to core Eru functionality.
    *
    * Tests that essential Eru constructors and combinators are accessible through the prelude
    * import without requiring additional imports, supporting the Radical Ergonomics principle.
    */
  test("prelude provides core constructors and combinators") {
    val e = Eru.succeed(42).map(_ + 1).flatMap(n => Eru.succeed(n * 2))
    val exit = e.runExit()
    exit match {
      case Exit.Success(v) => assertEquals(v, 86)
      case _ => fail("expected success")
    }
  }

  /** Validates that internal implementation details are not exposed through prelude.
    *
    * Tests that the prelude import only exposes intended public API and does not leak internal
    * implementation types, maintaining clean API boundaries.
    */
  test("internal names are not pulled in by the prelude import") {
    assert(!scala.compiletime.testing.typeChecks("import net.ghoula.eru.prelude.*; val _: PreludeApi.type = ???"))
    assert(
      !scala.compiletime.testing.typeChecks("import net.ghoula.eru.prelude.*; val _: RuntimePreludeApi.type = ???")
    )
  }
}
