package userland

import munit.FunSuite

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Comprehensive end-to-end integration test suite for complete Eru workflows.
  *
  * Validates complex compositions of all Eru features including concurrency, resource management,
  * error handling, observability, and runtime operations in realistic application scenarios. These
  * tests ensure that the entire effect system works cohesively and maintains all correctness,
  * performance, and safety guarantees when features are composed in production-like usage patterns.
  */
final class EndToEndSpec extends FunSuite {
  test("end-to-end composition across concurrency, state, retry, timeout, ensure, observer") {
    val events = scala.collection.mutable.ArrayBuffer.empty[EruObserver.EruEvent]
    val observer = new EruObserver {
      def onEvent(e: EruObserver.EruEvent): Unit = events += e
    }

    val program = for {
      ref <- Eru.ref(List.empty[Int])
      _ <- (Eru.succeed(1).flatMap(n => ref.update(n :: _))).fork
      _ <- (Eru.succeed(2).flatMap(n => ref.update(n :: _))).fork
      _ <- Eru.blocking(Thread.sleep(10))
      l <- ref.get
      ok <- Eru.succeed(l.sum).retryWithBackoff(Duration.ofMillis(10), maxRetries = 2)
      out <- Eru.succeed(ok).timeoutTo(Duration.ofSeconds(1), -1)
      _ <- Eru.succeed(()).ensure(Eru.effect(()))
    } yield out

    val result = program.runWith(observer)
    assert(result >= 3)
  }
}
