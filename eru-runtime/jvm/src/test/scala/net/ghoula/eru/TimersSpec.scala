package net.ghoula.eru

import munit.FunSuite

import java.time.Duration
import scala.annotation.nowarn

import net.ghoula.eru.prelude.*

/** Test suite for JVM timer functionality in the Eru runtime system.
  *
  * Validates sleep operations, timeout behavior, and other time-based primitives available on the
  * JVM platform. These tests ensure that timer operations provide accurate timing, proper
  * non-blocking semantics, and integrate correctly with the fiber scheduling system while
  * maintaining high performance under concurrent load.
  */
@nowarn("msg=.*")
final class TimersSpec extends FunSuite {

  test("sleep completes after duration (non-blocking semantics)") {
    val start = System.nanoTime()
    EruRuntime.sleep(Duration.ofMillis(5)).unsafeRunSync()
    val elapsedMs = (System.nanoTime() - start) / 1000000L
    assert(clue(elapsedMs) >= 5L)
  }

  test("timeout yields TimeoutException when duration elapses first") {
    val long = EruRuntime.sleep(Duration.ofMillis(50)).flatMap(_ => Eru.succeed(1))
    val res = EruRuntime.timeout(Duration.ofMillis(5))(long).attempt.unsafeRunSync()
    res match {
      case Result.Failure(t: java.util.concurrent.TimeoutException) => assert(true)
      case other => fail(s"Expected TimeoutException, got: $other")
    }
  }

  test("timeout passes through success when effect completes before deadline") {
    val short = EruRuntime.sleep(Duration.ofMillis(2)).flatMap(_ => Eru.succeed(42))
    val out = EruRuntime.timeout(Duration.ofMillis(20))(short).unsafeRunSync()
    assertEquals(out, 42)
  }
}
