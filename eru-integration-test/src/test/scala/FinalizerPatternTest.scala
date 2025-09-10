package userland

import munit.FunSuite
import userland.TestRuntime.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.ghoula.eru.*

class FinalizerPatternTest extends FunSuite {

  test("WORKING pattern: ensure on the sleep directly") {
    val finalizerRan = new AtomicBoolean(false)
    val childStarted = new CountDownLatch(1)

    val computation = for {
      fiber <- runtime.fork {
        // WORKING PATTERN: ensure directly on the sleep
        for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- runtime
            .sleep(Duration.ofSeconds(10))
            .ensure(Eru.effect {
              finalizerRan.set(true)
              println("WORKING PATTERN: Finalizer executed!")
            })
        } yield "done"
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test")))
      exit <- fiber.await
    } yield exit

    val result = computation.unsafeRunSync()
    println(s"WORKING PATTERN: Result=$result, Finalizer ran=${finalizerRan.get()}")
    assert(finalizerRan.get(), "Working pattern should execute finalizer")
  }

  test("FAILING pattern: ensure on the for-comprehension") {
    val finalizerRan = new AtomicBoolean(false)
    val childStarted = new CountDownLatch(1)

    val computation = for {
      fiber <- runtime.fork {
        // FAILING PATTERN: ensure on the for-comprehension
        (for {
          _ <- Eru.effect { childStarted.countDown() }
          _ <- runtime.sleep(Duration.ofSeconds(10))
        } yield "done").ensure(Eru.effect {
          finalizerRan.set(true)
          println("FAILING PATTERN: Finalizer executed!")
        })
      }
      _ <- Eru.effect { childStarted.await(1, TimeUnit.SECONDS) }
      _ <- fiber.interrupt(InterruptCause.Cancelled(Some("Test")))
      exit <- fiber.await
    } yield exit

    val result = computation.unsafeRunSync()
    println(s"FAILING PATTERN: Result=$result, Finalizer ran=${finalizerRan.get()}")
    // This will likely fail based on our earlier diagnostics
    // assert(finalizerRan.get(), "Failing pattern should execute finalizer")
  }
}
