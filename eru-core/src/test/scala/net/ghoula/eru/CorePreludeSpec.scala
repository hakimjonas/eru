package net.ghoula.eru

import net.ghoula.eru.CorePrelude.*

/** Comprehensive test suite for CorePrelude exports and accessibility.
  *
  * Validates that all exported types, factory methods, and extension methods from CorePrelude are
  * correctly accessible and functional. CorePrelude serves as the main entry point for the Eru
  * effect system, providing a curated set of imports for common usage patterns.
  */
class CorePreludeSpec extends munit.FunSuite {

  test("CorePrelude exports Eru type and companion object") {
    val effect: Eru[String, Int] = Eru.succeed(42)
    assertEquals(effect.unsafeRunSync(), 42)

    val failed = Eru.fail("error")
    val exception = intercept[EruException[String]] {
      failed.unsafeRunSync()
    }
    assertEquals(exception.error, "error")
  }

  test("CorePrelude exports Eru factory methods directly") {
    val s = succeed(42)
    assertEquals(s.unsafeRunSync(), 42)

    val f = Eru.fail("error")
    val ex = intercept[EruException[String]] {
      f.unsafeRunSync()
    }
    assertEquals(ex.error, "error")

    val e = effect(21 * 2)
    assertEquals(e.unsafeRunSync(), 42)

    val b = blocking(10 + 32)
    assertEquals(b.unsafeRunSync(), 42)

    val either: Either[String, Int] = Right(42)
    val fromE = fromEither(either)
    assertEquals(fromE.unsafeRunSync(), 42)

    val fromOpt = fromOption(Some(42), "none")
    assertEquals(fromOpt.unsafeRunSync(), 42)

    val u = unit
    assertEquals(u.unsafeRunSync(), ())
  }

  test("CorePrelude provides access to extension methods") {
    val effect = succeed(42)

    val withFinalizer = effect.ensure(succeed(()))
    assertEquals(withFinalizer.unsafeRunSync(), 42)

    val resource = succeed("resource")
    val used = resource.bracket(_ => succeed(()))(res => succeed(res.length))
    assertEquals(used.unsafeRunSync(), 8)

    val debugged = effect.debug("test label")
    assertEquals(debugged.unsafeRunSync(), 42)
  }

  test("CorePrelude provides complete prelude for typical usage") {
    def businessLogic(): Eru[String | Throwable, Int] = {
      val result = for {
        value <- succeed(10)
        doubled <- effect(value * 2)
        validated <- if (doubled > 15) succeed(doubled) else fail("too small")
        finalValue <- fromOption(Some(validated * 2), "missing")
      } yield finalValue

      result.recover { case "too small" => 100 }
        .ensure(succeed(()))
    }

    assertEquals(businessLogic().unsafeRunSync(), 40)
  }

}
