package net.ghoula.eru

import java.time.Duration

import net.ghoula.eru.prelude.*

/** Tests for Suspending timeout behavior on the Virtual Threads backend. */
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

    intercept[java.util.concurrent.TimeoutException] {
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
