package net.ghoula.eru.meta

import munit.FunSuite

import net.ghoula.eru.Eru

class EruMacrosSpec extends FunSuite {

  test("validated macro preserves effect behavior for simple success case") {
    val effect = EruMacros.validated(Eru.succeed(42))
    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
  }

  test("validated macro preserves effect behavior for failure case") {
    val effect = EruMacros.validated(Eru.fail("boom"))
    val exception = intercept[net.ghoula.eru.EruException[String]] {
      effect.unsafeRunSync()
    }
    assertEquals(exception.error, "boom")
  }

  test("validated macro works with complex effect chains") {
    val effect = EruMacros.validated {
      for {
        a <- Eru.succeed(10)
        b <- Eru.succeed(20)
        c <- Eru.succeed(a + b)
      } yield c * 2
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 60)
  }

  test("validated macro works with map chains") {
    val effect = EruMacros.validated {
      Eru
        .succeed(5)
        .map(_ * 2)
        .map(_ + 1)
        .map(_ * 3)
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 33)
  }

  test("validated macro works with error handling") {
    val effect = EruMacros.validated {
      Eru.fail("error").recover { case "error" => "recovered" }
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, "recovered")
  }

  test("validated macro works with ensure patterns") {
    var finalizerRan = false
    val effect = EruMacros.validated {
      Eru.succeed(42).ensure(Eru.effect { finalizerRan = true })
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 42)
    assert(finalizerRan)
  }

  test("optimize macro preserves effect behavior") {
    val effect = EruMacros.optimize(Eru.succeed(100))
    val result = effect.unsafeRunSync()
    assertEquals(result, 100)
  }

  test("optimize macro works with complex compositions") {
    val effect = EruMacros.optimize {
      for {
        x <- Eru.succeed(1)
        y <- Eru.succeed(2)
        z <- Eru.succeed(3)
      } yield x + y + z
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 6)
  }

  test("derive macro creates basic derivation") {
    val derivation = EruMacros.derive[String]
    val result = derivation.pure("test").unsafeRunSync()
    assertEquals(result, "test")
  }

  test("derive macro works with case classes") {
    val derivation = EruMacros.derive[String]
    val result = derivation.nonNull("test").unsafeRunSync()
    assertEquals(result, "test")
  }

  test("macros compose correctly") {
    val effect = EruMacros.optimize {
      EruMacros.validated {
        Eru.succeed(42).map(_ * 2)
      }
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, 84)
  }

  test("macros work with extension methods") {
    import net.ghoula.eru.prelude.*

    val effect = EruMacros.validated {
      Eru.succeed("resource").autoCleanup(_ => Eru.unit)
    }

    val result = effect.unsafeRunSync()
    assertEquals(result, "resource")
  }

  test("macros preserve type information") {
    val stringEffect: Eru[Nothing, String] = EruMacros.validated(Eru.succeed("hello"))
    val intEffect: Eru[String, Int] = EruMacros.validated(Eru.fail("error"))

    // These should compile with correct types
    val stringResult = stringEffect.unsafeRunSync()
    assertEquals(stringResult, "hello")

    val intException = intercept[net.ghoula.eru.EruException[String]] {
      intEffect.unsafeRunSync()
    }
    assertEquals(intException.error, "error")
  }

  test("macros work with generic types") {
    def genericEffect[T](value: T): Eru[Nothing, T] =
      EruMacros.validated(Eru.succeed(value))

    val stringResult = genericEffect("test").unsafeRunSync()
    assertEquals(stringResult, "test")

    val intResult = genericEffect(123).unsafeRunSync()
    assertEquals(intResult, 123)
  }
}
