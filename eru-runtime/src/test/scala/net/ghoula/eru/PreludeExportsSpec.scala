package net.ghoula.eru

import munit.FunSuite
import net.ghoula.eru.prelude.*
import java.time.Duration

/** Exports proof test that verifies all major functionality is available through the single prelude import.
  *
  * This test serves as a compile-time verification that the unified prelude export provides
  * access to all essential Eru functionality without requiring additional imports.
  *
  * The test is organized by functional categories as specified in the issue requirements:
  * - Core constructors
  * - Core combinators  
  * - Domain/aux types
  * - Runtime ops
  * - Runtime data types
  * - Runner conveniences
  */
final class PreludeExportsSpec extends FunSuite {

  test("core constructors are available from prelude import") {
    val _ = Eru.succeed(42)
    val _ = Eru.fail("error")
    val _ = Eru.effect(println("side effect"))
    val _ = Eru.blocking(Thread.sleep(1))
    val _ = Eru.suspend[String, Int](callback => Eru.effect(callback(Right(42))))
    val _ = Eru.fromEither(Right(42))
    val _ = Eru.fromTry(scala.util.Success(42))
    val _ = Eru.fromOption(Some(42), "none")
    val _ = Eru.unit
  }

  test("core combinators are available from prelude import") {
    val effect = Eru.succeed(21)
    
    val _ = effect.map(_ * 2)
    val _ = effect.flatMap(x => Eru.succeed(x * 2))
    val _ = effect.zip(Eru.succeed(2))
    val _ = effect.orElse(Eru.succeed(0))
    val _ = effect.recover { case _ => 0 }
    val _ = effect.recoverWith { case _ => Eru.succeed(0) }
    val _ = effect.attempt
    val _ = effect.mapError(_.toString)
    val _ = effect.ensure(Eru.unit)
    val _ = effect.bracket(Eru.unit)(_ => Eru.unit)
    val _ = effect.debug("test")
  }

  test("domain and auxiliary types are available from prelude import") {
    import scala.compiletime.testing.typeChecks
    
    val _ = Result.succeed(42)
    val _ = Result.fail("error")
    
    val _ = EruException("test error")
    
    val exit: Exit[String, Int] = Exit.Success(42)
    exit match {
      case Exit.Success(value) => value
      case Exit.Failure(error) => 0
      case Exit.Die(throwable) => 0
    }
  }

  test("runtime operations are available from prelude import") {
    val effect = Eru.succeed(42)
    
    val _ = effect.fork
    val _ = effect.forkWithObserver(EruObserver.noop)
    val _ = effect.zipPar(Eru.succeed(2))
    val _ = effect.race(Eru.succeed("other"))
    
    val _ = effect.timeout(Duration.ofSeconds(1))
    val _ = effect.timeoutTo(Duration.ofSeconds(1), 0)
    
    val _ = effect.retryN(3)
    val _ = effect.retryWithBackoff(Duration.ofMillis(100), 3)
  }

  test("runtime data types are available from prelude import") {
    val _ = Eru.ref(42)
    val _ = Eru.deferred[String]
    val _ = Eru.semaphore(1L)
    
    import scala.compiletime.testing.typeChecks
    assert(typeChecks("val _: Ref[Int] = ???"))
    assert(typeChecks("val _: Deferred[String] = ???"))
    assert(typeChecks("val _: Semaphore = ???"))
    assert(typeChecks("val _: Fiber[String, Int] = ???"))
  }

  test("runner conveniences are available from prelude import") {
    val effect = Eru.succeed(42)
    val observer = EruObserver.noop
    
    val _ = effect.runExit()
    val _ = effect.runWith(observer)
  }

  test("extension methods from core are available from prelude import") {
    val effect = Eru.succeed(42)
    
    val _ = effect.cached
    val _ = effect.ensureAll(Eru.unit, Eru.unit)
    val _ = effect.autoCleanup(_ => Eru.unit)
  }

  test("observers and tracing are available from prelude import") {
    val _ = EruObserver.noop
    val _ = EruObserver.console
    
    import scala.compiletime.testing.typeChecks
    assert(typeChecks("val _: EruObserver.EruEvent = ???"))
  }
}