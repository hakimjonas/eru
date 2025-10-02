package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** JVM-specific tests for Suspending timeout behavior.
  *
  * These tests require true concurrency and are only valid on the JVM platform where Virtual
  * Threads enable real timeout functionality. The Native platform uses synchronous execution and
  * does not support timeouts.
  */
class SuspendingTimeoutSpec extends munit.FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    try {
      EruRuntime.shared.cleanup()
    } catch {
      case _: Exception => ()
    }
    super.afterAll()
  }

  test("timeout fails if computation exceeds duration") {
    val queue = Eru.queue[String](10).unsafeRunSync()
    // Don't put anything - queue.take will suspend indefinitely

    intercept[TimeoutError] {
      queue.take.timeout(Duration.ofMillis(100)).unsafeRunSync()
    }
  }

  test("timeout can be chained with other operations") {
    val queue = Eru.queue[Int](10).unsafeRunSync()
    queue.tryPut(42).unsafeRunSync()

    val program = queue.take
      .map(_ * 2)
      .timeout(Duration.ofSeconds(1))
      .map(_ + 10)

    val result = program.unsafeRunSync()
    assertEquals(result, 94)
  }

  test("timeout preserves errors from the computation") {
    val promise = Eru.promise[String, Int].unsafeRunSync()
    promise.fail("computation error").unsafeRunSync()

    val result = promise.await
      .timeout(Duration.ofSeconds(1))
      .attempt
      .unsafeRunSync()

    assertEquals(result, Result.Failure("computation error"))
  }
}
