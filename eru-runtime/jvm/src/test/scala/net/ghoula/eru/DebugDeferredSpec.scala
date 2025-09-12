package net.ghoula.eru

import munit.FunSuite

import net.ghoula.eru.*
import net.ghoula.eru.prelude.*

/** Debug test to isolate the deferred hanging issue. */
class DebugDeferredSpec extends FunSuite {

  test("simple deferred complete/await should work") {
    val runtime = EruRuntime.create()
    given EruRuntime = runtime

    val simpleTest = for {
      deferred <- Eru.deferred[Int]
      _ <- deferred.complete(42)
      result <- deferred.await
    } yield result

    val result = simpleTest.unsafeRunSync()
    assertEquals(result, 42)
    runtime.cleanup()
  }

  test("deferred with fork/await pattern should work") {
    val runtime = EruRuntime.create()
    given EruRuntime = runtime

    val complexTest = for {
      deferred <- Eru.deferred[Int]
      waiterFiber <- runtime.fork(deferred.await)
      producerFiber <- runtime.fork {
        deferred.complete(100)
      }
      _ <- producerFiber.await
      result <- waiterFiber.await.map {
        case Exit.Success(value) => value
        case Exit.Failure(error) => throw new Exception(s"Waiter failed with error: $error")
        case Exit.Die(throwable) => throw throwable
        case Exit.Interrupt(_, _) => throw new Exception("Waiter was interrupted")
      }
    } yield result

    val result = complexTest.unsafeRunSync()
    assertEquals(result, 100)
    runtime.cleanup()
  }

  test("benchmark pattern reproduction") {
    val runtime = EruRuntime.create()
    given EruRuntime = runtime

    val operations = 10

    val benchmarkTest = for {
      deferred <- Eru.deferred[Int]
      waiterFiber <- runtime.fork(deferred.await)
      producerFiber <- runtime.fork {
        // Simulate some work before producing value
        Eru
          .foreachDiscard(1 to operations / 10)(_ => Eru.unit)
          .flatMap(_ => deferred.complete(operations))
      }
      _ <- producerFiber.await
      result <- waiterFiber.await.map {
        case Exit.Success(value) => value
        case _ => 0
      }
    } yield result

    val result = benchmarkTest.unsafeRunSync()
    assertEquals(result, operations)
    runtime.cleanup()
  }
}
