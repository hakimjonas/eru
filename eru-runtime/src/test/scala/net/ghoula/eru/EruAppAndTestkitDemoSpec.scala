package net.ghoula.eru

import munit.FunSuite
import net.ghoula.eru.prelude.*
import net.ghoula.eru.testkit.*
import net.ghoula.eru.testkit.syntax.value.*

/** Demonstration of EruAppDefault and testkit functionality.
  *
  * This test serves multiple purposes:
  * 1. Demonstrates how to use EruAppDefault for applications
  * 2. Shows EruSuite and EruAssertions in action
  * 3. Provides examples of the optional terse syntax
  * 4. Serves as living documentation for the new functionality
  */
final class EruAppAndTestkitDemoSpec extends EruSuite with EruAssertions {

  // ===== ERUAPPDEFAULT DEMONSTRATION =====
  
  test("EruAppDefault provides ergonomic app entry point") {
    // Create a sample application using EruAppDefault
    object SampleApp extends net.ghoula.eru.app.EruAppDefault {
      def run: Eru[Throwable, Unit] =
        for {
          _ <- Eru.effect(println("Hello from Eru!"))
          result <- Eru.succeed(42)
          _ <- Eru.effect(assertEquals(result, 42))
        } yield ()
    }
    
    // Verify the app has the expected structure
    val app = SampleApp
    assert(app.isInstanceOf[net.ghoula.eru.app.EruAppDefault])
    
    // The main method would call run.unsafeRunSync() automatically
    // We can't test main directly, but we can test the run method
    val runResult = app.run.unsafeRunSync()
    assertEquals(runResult, ())
  }

  // ===== ERUSUITE DEMONSTRATION =====

  testE("testE allows effect-based test bodies") {
    for {
      x <- Eru.succeed(21)
      y <- Eru.succeed(2)
      result <- Eru.succeed(x * y)
      _ <- Eru.effect(assertEquals(result, 42))
    } yield ()
  }

  testE("testE handles errors gracefully") {
    Eru.effect {
      val result = Eru.succeed("test").unsafeRunSync()
      assertEquals(result, "test")
    }
  }

  // ===== ERUASSERTIONS DEMONSTRATION =====

  test("assertRunsEquals verifies effect results") {
    val computation = for {
      x <- Eru.succeed(21)
      y <- Eru.succeed(2)
    } yield x * y

    assertRunsEquals(computation, 42)
  }

  test("assertRuns verifies effect results with predicates") {
    val computation = Eru.succeed(42)
    assertRuns(computation)(_ > 40, "result should be greater than 40")
    assertRuns(computation)(_ % 2 == 0, "result should be even")
  }

  test("interceptRun captures expected failures") {
    val failingEffect = Eru.fail("expected error").mapError(EruException.apply)
    
    val exception = interceptRun[EruException[String]](failingEffect)
    assertEquals(exception.error, "expected error")
  }

  // ===== TERSE SYNTAX DEMONSTRATION =====

  test("optional value syntax provides concise effect evaluation") {
    // Using the terse .value syntax for concise test code
    val result = Eru.succeed(42).value
    assertEquals(result, 42)

    val computed = (for {
      x <- Eru.succeed(21)
      y <- Eru.succeed(2)
    } yield x * y).value
    assertEquals(computed, 42)

    val converted = Eru.fromOption(Some("test"), "none").value
    assertEquals(converted, "test")
  }

  test("value syntax works with complex effect chains") {
    val complexEffect = for {
      numbers <- Eru.succeed(List(1, 2, 3, 4, 5))
      doubled <- Eru.succeed(numbers.map(_ * 2))
      sum <- Eru.succeed(doubled.sum)
    } yield sum

    val result = complexEffect.value
    assertEquals(result, 30) // (1+2+3+4+5) * 2 = 30
  }

  // ===== RUNNER CONVENIENCES DEMONSTRATION =====

  test("runExit provides structured error handling") {
    val successEffect = Eru.succeed(42)
    val successExit = successEffect.runExit()
    
    successExit match {
      case Exit.Success(value) => assertEquals(value, 42)
      case _ => fail("Expected success exit")
    }

    val failureEffect = Eru.fail("test error")
    val failureExit = failureEffect.runExit()
    
    failureExit match {
      case Exit.Failure(error) => assertEquals(error, "test error")
      case _ => fail("Expected failure exit")
    }
  }

  test("runWith provides observer-aware execution") {
    val effect = Eru.succeed(42)
    val observer = EruObserver.noop
    
    val result = effect.runWith(observer)
    assertEquals(result, 42)
  }
}